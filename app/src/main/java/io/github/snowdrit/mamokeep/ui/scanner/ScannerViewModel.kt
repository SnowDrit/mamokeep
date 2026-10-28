package io.github.snowdrit.mamokeep.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.snowdrit.mamokeep.BuildConfig
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.scanner.ModelInstallException
import io.github.snowdrit.mamokeep.data.scanner.OcrException
import io.github.snowdrit.mamokeep.data.scanner.OcrFailure
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager
import io.github.snowdrit.mamokeep.data.scanner.readScannerImage
import io.github.snowdrit.mamokeep.data.scanner.readScannerText
import io.github.snowdrit.mamokeep.data.scanner.readScannerPdf
import io.github.snowdrit.mamokeep.data.scanner.checkedScannerText
import io.github.snowdrit.mamokeep.data.scanner.MAX_SCANNER_TEXT
import io.github.snowdrit.mamokeep.data.scanner.ScannerDocumentException
import io.github.snowdrit.mamokeep.data.scanner.ScannerDocumentFailure
import io.github.snowdrit.mamokeep.domain.scanner.ReceiptDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal enum class ScannerRoute { SOURCE, FUEL, WORK }

internal class ScannerViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == ScannerViewModel::class.java)
        return modelClass.cast(ScannerViewModel(context, extras.createSavedStateHandle()))
    }
}

/** Image and OCR text stay in memory across rotation, never in the activity's state Bundle. */
internal class ScannerViewModel(
    private val context: Context,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    var image by mutableStateOf<Bitmap?>(null)
        private set
    var text by mutableStateOf("")
        private set
    var originalText by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var route by mutableStateOf(ScannerRoute.SOURCE)
        private set
    var draft by mutableStateOf<ReceiptDraft?>(null)
        private set
    var workNote by mutableStateOf("")
    var recordSaved by mutableStateOf(false)
        private set
    var error by mutableStateOf<Int?>(if (savedState.get<Boolean>("active") == true) R.string.ocr_interrupted else null)
        private set
    private var job: Job? = null
    private var generation = 0
    private var pdfSource by mutableStateOf<Uri?>(null)
    val hasPdfSource: Boolean get() = pdfSource != null
    val reviewSourceText: String get() = originalText.ifBlank { text }

    init { savedState["active"] = false }

    fun updateText(value: String) {
        if (busy) return
        if (value.length > MAX_SCANNER_TEXT) { error = R.string.scanner_import_too_large; return }
        text = value
        if (error == R.string.scanner_import_too_large) error = null
        savedState["active"] = true
    }

    fun review(target: ScannerRoute) {
        if (busy || text.isBlank()) return
        draft = ReceiptDraft.parse(text)
        workNote = text
        route = target
    }

    fun backToSource() { route = ScannerRoute.SOURCE }
    fun markSaved() { recordSaved = true }

    fun openCamera(launch: (Uri) -> Unit) {
        if (busy) return
        val request = ++generation
        busy = true
        error = null
        savedState["active"] = true
        job = viewModelScope.launch {
            var created: File? = null
            var launched = false
            try {
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "scanner-camera")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Camera directory unavailable")
                    created = File.createTempFile("receipt-", ".jpg", directory)
                }
                ensureActive()
                val file = requireNotNull(created)
                savedState["camera_path"] = file.path
                launch(FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.scanner-images", file))
                launched = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                removeCameraFile()
                if (generation == request) error = R.string.ocr_camera_failed
            } finally {
                if (!launched) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.delete() }
                if (generation == request) busy = false
            }
        }
    }

    fun cameraResult(success: Boolean, manager: ScannerModelManager) {
        val path = savedState.get<String>("camera_path")
        savedState["camera_path"] = null
        if (path == null) {
            if (success) error = R.string.ocr_interrupted
            return
        }
        val file = File(path)
        if (!success) {
            file.delete()
            return
        }
        recognize(Uri.fromFile(file), manager, file)
    }

    fun pickerFailed() { error = R.string.ocr_import_failed }

    fun importInput(source: ScannerInputSource, manager: ScannerModelManager) {
        start(null) { showImage ->
            when (source) {
                is ScannerInputSource.Text -> checkedScannerText(source.text)
                is ScannerInputSource.Content -> readContent(source.uri, source.mimeType, manager, showImage)
            }
        }
    }

    fun importUri(uri: Uri, manager: ScannerModelManager) {
        start(null) { showImage ->
            val type = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) }
            readContent(uri, type.orEmpty(), manager, showImage)
        }
    }

    private suspend fun readContent(uri: Uri, mimeType: String, manager: ScannerModelManager, showImage: (Bitmap) -> Unit): String =
        when {
            mimeType == "application/pdf" -> {
                pdfSource = uri
                readScannerPdf(context, uri, manager)
            }
            mimeType == "text/plain" -> readScannerText(context, uri)
            mimeType.startsWith("image/") -> {
                val bitmap = readScannerImage(context, uri)
                showImage(bitmap)
                manager.recognize(bitmap).text
            }
            else -> throw ScannerDocumentException(ScannerDocumentFailure.UNSUPPORTED)
        }

    fun recognize(uri: Uri, manager: ScannerModelManager, temporary: File? = null) {
        start(temporary) { showImage ->
            val bitmap = readScannerImage(context, uri)
            showImage(bitmap)
            manager.recognize(bitmap).text
        }
    }

    fun recognizeWholePdf(manager: ScannerModelManager) {
        if (busy) return
        val uri = pdfSource ?: return
        start(null) {
            pdfSource = uri
            readScannerPdf(context, uri, manager, forceRaster = true)
        }
    }

    fun retry(manager: ScannerModelManager) {
        val current = image ?: return
        start(null) { showImage -> showImage(current); manager.recognize(current).text }
    }

    fun rotate(manager: ScannerModelManager) {
        if (busy) return
        val current = image ?: return
        start(null) { showImage ->
            val rotated = withContext(Dispatchers.Default) {
                ensureActive()
                Bitmap.createBitmap(current, 0, 0, current.width, current.height,
                    Matrix().apply { postRotate(90f) }, true)
            }
            showImage(rotated)
            manager.recognize(rotated).text
        }
    }

    private fun start(temporary: File?, read: suspend ((Bitmap) -> Unit) -> String) {
        job?.cancel()
        val request = ++generation
        busy = true
        error = null
        text = ""
        image = null
        pdfSource = null
        originalText = ""
        draft = null
        recordSaved = false
        route = ScannerRoute.SOURCE
        savedState["active"] = true
        job = viewModelScope.launch {
            try {
                val sourceText = read { bitmap -> if (generation == request) image = bitmap }
                ensureActive()
                if (generation == request) {
                    originalText = sourceText
                    text = sourceText
                    if (text.isBlank()) error = R.string.ocr_empty
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == request) error = failure.messageResource()
            } finally {
                if (temporary != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { temporary.delete() }
                if (generation == request) busy = false
            }
        }
    }

    fun cancelRecognition() {
        generation++
        job?.cancel()
        busy = false
        error = null
    }

    fun reset() {
        cancelRecognition()
        removeCameraFile()
        image = null
        pdfSource = null
        text = ""
        originalText = ""
        draft = null
        workNote = ""
        recordSaved = false
        route = ScannerRoute.SOURCE
        savedState["active"] = false
    }

    private fun removeCameraFile() {
        savedState.get<String>("camera_path")?.let { File(it).delete() }
        savedState["camera_path"] = null
    }

    override fun onCleared() { removeCameraFile() }
}

@StringRes
private fun Exception.messageResource(): Int = when (this) {
    is ScannerDocumentException -> when (reason) {
        ScannerDocumentFailure.UNSUPPORTED -> R.string.scanner_import_unsupported
        ScannerDocumentFailure.TOO_LARGE -> R.string.scanner_import_too_large
        ScannerDocumentFailure.TOO_MANY_PAGES -> R.string.ocr_import_pdf_pages
        ScannerDocumentFailure.INVALID -> R.string.ocr_import_failed
    }
    is ModelInstallException -> R.string.ocr_model_failed
    is OcrException -> when (reason) {
        OcrFailure.INPUT, OcrFailure.LIMIT -> R.string.ocr_image_failed
        OcrFailure.INCOMPATIBLE_MODEL -> R.string.ocr_model_failed
        OcrFailure.ENGINE -> R.string.ocr_recognition_failed
    }
    is IOException, is SecurityException -> R.string.ocr_import_failed
    else -> R.string.ocr_recognition_failed
}
