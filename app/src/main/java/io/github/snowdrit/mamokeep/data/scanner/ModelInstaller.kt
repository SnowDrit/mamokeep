package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Installs only complete verified data files. No extraction, dynamic libraries or executable code. */
class ModelInstaller(
    private val root: File,
    val model: ModelPackage,
    private val policy: ModelDownloadPolicy,
    private val transport: ModelTransport,
) {
    init {
        model.validate()
        policy.validate()
    }
    private val mutex = Mutex()
    private val installed get() = File(root, model.directoryName)
    private val staging get() = File(root, ".install-${model.directoryName}")

    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { verifyDirectory(installed) }
    }

    suspend fun hasStoredFiles(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            listOf(installed, staging).any { directory -> directory.listFiles()?.any { it.isFile } == true }
        }
    }

    suspend fun install(progress: suspend (ScannerModelPhase, Long) -> Unit) {
        if (!model.source.isPublished) throw ModelInstallException(ScannerModelError.SOURCE)
        installVerified(progress, ScannerModelPhase.DOWNLOADING, ScannerModelError.NETWORK) { spec ->
            transport.open(model.url(spec))
        }
    }

    /** Called only after VersionedModelStore resolves a pinned APK registry approval. */
    internal suspend fun installFromApk(
        openFile: (ModelFile) -> InputStream,
        progress: suspend (ScannerModelPhase, Long) -> Unit,
    ) {
        if (model.source != ModelSource("", "")) throw ModelInstallException(ScannerModelError.SOURCE)
        installVerified(progress, ScannerModelPhase.VERIFYING, ScannerModelError.STORAGE) { spec ->
            object : ModelResponse {
                override val body = openFile(spec)
                override val contentLength = spec.bytes
                override fun close() = body.close()
            }
        }
    }

    private suspend fun installVerified(
        progress: suspend (ScannerModelPhase, Long) -> Unit,
        copyingPhase: ScannerModelPhase,
        readError: ScannerModelError,
        openFile: (ModelFile) -> ModelResponse,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (verifyDirectory(installed)) return@withLock
            if (!staging.isDirectory && !staging.mkdirs()) throw ModelInstallException(ScannerModelError.STORAGE)
            var completedBytes = 0L
            for (spec in model.files) {
                currentCoroutineContext().ensureActive()
                val target = File(staging, spec.name)
                if (matches(target, spec)) {
                    completedBytes += spec.bytes
                    continue
                }
                deleteFile(target)
                if (root.usableSpace - policy.storageReserveBytes < spec.bytes) {
                    throw ModelInstallException(ScannerModelError.STORAGE)
                }
                val part = File(staging, ".download.part")
                deleteFile(part)
                progress(copyingPhase, completedBytes)
                try {
                    val response = try { openFile(spec) } catch (e: IOException) {
                        throw if (e is ModelInstallException || e is CatalogException) e else ModelInstallException(readError, e)
                    }
                    response.use {
                        if (it.contentLength >= 0 && it.contentLength != spec.bytes) {
                            throw ModelInstallException(ScannerModelError.INTEGRITY)
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        var reported = 0L
                        val buffer = ByteArray(policy.bufferBytes)
                        storage {
                            FileOutputStream(part).use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = try { it.body.read(buffer) } catch (e: IOException) {
                                        throw ModelInstallException(readError, e)
                                    }
                                    if (read < 0) break
                                    if (read == 0) continue
                                    if (read > spec.bytes - count) throw ModelInstallException(ScannerModelError.INTEGRITY)
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    count += read
                                    val now = System.nanoTime() / NANOS_PER_MILLISECOND
                                    if (now - reported >= policy.progressIntervalMillis) {
                                        progress(copyingPhase, completedBytes + count)
                                        reported = now
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                        progress(ScannerModelPhase.VERIFYING, completedBytes + count)
                        if (count != spec.bytes || digest.digest().hex() != spec.sha256) {
                            throw ModelInstallException(ScannerModelError.INTEGRITY)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!part.renameTo(target)) throw ModelInstallException(ScannerModelError.STORAGE)
                        completedBytes += count
                    }
                } finally {
                    // A retry reuses complete verified files; the interrupted file restarts.
                    part.delete()
                }
            }
            progress(ScannerModelPhase.VERIFYING, model.totalBytes)
            if (!verifyDirectory(staging)) throw ModelInstallException(ScannerModelError.INTEGRITY)
            currentCoroutineContext().ensureActive()
            if (installed.exists() && !installed.deleteRecursively()) throw ModelInstallException(ScannerModelError.STORAGE)
            if (!staging.renameTo(installed)) throw ModelInstallException(ScannerModelError.STORAGE)
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (directory in listOf(installed, staging)) {
                if (directory.exists() && !directory.deleteRecursively()) throw ModelInstallException(ScannerModelError.STORAGE)
            }
        }
    }

    private suspend fun verifyDirectory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        return model.files.all { matches(File(directory, it.name), it) }
    }

    private suspend fun matches(file: File, spec: ModelFile): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        return storage {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(policy.bufferBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().hex() == spec.sha256
        }
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw ModelInstallException(ScannerModelError.STORAGE)
    }

    private suspend inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw if (e is ModelInstallException) e else ModelInstallException(ScannerModelError.STORAGE, e)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object { private const val NANOS_PER_MILLISECOND = 1_000_000L }
}
