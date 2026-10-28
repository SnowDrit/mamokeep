package io.github.snowdrit.mamokeep.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.writeDocumentText
import io.github.snowdrit.mamokeep.domain.export.ExportPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ExportOperation(@StringRes val label: Int) {
    PREPARING(R.string.export_preparing),
    CHOOSING_FILE(R.string.export_choosing_file),
    WRITING_FILE(R.string.export_writing_file),
}

/** Holds only the open dialog's data; explicit close clears it, rotation keeps it. */
internal class ExportDialogViewModel : ViewModel() {
    var preset by mutableStateOf(ExportPreset.MAINTENANCE_ADVICE)
        private set
    var anonymize by mutableStateOf(false)
        private set
    var question by mutableStateOf("")
        private set
    var preview by mutableStateOf<String?>(null)
        private set
    var previewLines by mutableStateOf<List<String>>(emptyList())
        private set
    var operation by mutableStateOf<ExportOperation?>(null)
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    var statusIsError by mutableStateOf(false)
        private set
    private var pendingFileText: String? = null
    private var job: Job? = null
    private var generation = 0L

    fun selectPreset(value: ExportPreset) {
        if (operation != null) return
        preset = value
        status = null
    }

    fun setHideIdentity(value: Boolean) {
        if (operation != null) return
        anonymize = value
        status = null
    }

    fun updateQuestion(value: String) {
        if (operation != null) return
        question = value
        status = null
    }

    fun prepare(buildText: suspend (ExportPreset, Boolean, String) -> String) {
        if (operation != null) return
        val selectedPreset = preset
        val hideIdentity = anonymize
        val selectedQuestion = question
        val current = ++generation
        status = null
        operation = ExportOperation.PREPARING
        job = viewModelScope.launch {
            try {
                val text = buildText(selectedPreset, hideIdentity, selectedQuestion)
                ensureActive()
                check(text.isNotBlank())
                val lines = withContext(Dispatchers.Default) { text.lines() }
                if (generation == current) {
                    previewLines = lines
                    preview = text
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == current) notice(R.string.export_prepare_failed, error = true)
            } finally {
                if (generation == current) operation = null
            }
        }
    }

    fun copy(safeChars: Int, putInClipboard: (String) -> Unit) {
        withPreviewText(safeChars, R.string.export_too_big, R.string.export_copy_failed) { text ->
            putInClipboard(text)
            notice(R.string.export_copied, error = false)
        }
    }

    fun beginFileSelection(): Boolean {
        if (operation != null) return false
        pendingFileText = preview ?: return false
        status = null
        operation = ExportOperation.CHOOSING_FILE
        return true
    }

    fun share(safeChars: Int, openChooser: (String) -> Unit) {
        withPreviewText(safeChars, R.string.export_share_too_big, R.string.export_share_failed) { text ->
            openChooser(text)
            // Opening the chooser does not mean that the user sent the text.
            status = null
        }
    }

    private fun withPreviewText(
        safeChars: Int,
        @StringRes tooLargeMessage: Int,
        @StringRes failureMessage: Int,
        action: (String) -> Unit,
    ) {
        if (operation != null) return
        val text = preview ?: return
        if (text.length > safeChars) {
            notice(tooLargeMessage, error = false)
            return
        }
        try {
            action(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice(failureMessage, error = true)
        }
    }

    fun fileSelectionFailed() {
        pendingFileText = null
        operation = null
        notice(R.string.export_write_failed, error = true)
    }

    fun saveFile(uri: Uri?, resolver: ContentResolver) {
        val text = pendingFileText
        pendingFileText = null
        if (uri == null) {
            operation = null
            return
        }
        if (text == null) {
            operation = null
            // Process death may restore the picker but not our in-memory snapshot.
            notice(R.string.export_preview_lost, error = true)
            return
        }
        val current = ++generation
        operation = ExportOperation.WRITING_FILE
        job = viewModelScope.launch {
            try {
                resolver.writeDocumentText(uri, text)
                if (generation == current) notice(R.string.export_file_saved, error = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == current) notice(R.string.export_write_failed, error = true)
            } finally {
                if (generation == current) operation = null
            }
        }
    }

    fun back() {
        if (operation != null) return
        preview = null
        previewLines = emptyList()
        status = null
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        pendingFileText = null
        preview = null
        previewLines = emptyList()
        operation = null
        status = null
        statusIsError = false
        preset = ExportPreset.MAINTENANCE_ADVICE
        anonymize = false
        question = ""
    }

    private fun notice(@StringRes message: Int, error: Boolean) {
        status = message
        statusIsError = error
    }
}
