package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.serialization.Serializable
import java.net.URI

/** Approved by the bundled descriptor or by a catalog signed with an APK-trusted key. */
@Serializable
data class ModelPackage(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val source: ModelSource,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val directoryName: String get() = "$id-$version"

    fun validate(): ModelPackage = apply {
        require(schemaVersion == 1)
        require(safeName(id) && safeName(version))
        require(safeName(directoryName))
        source.validate()
        require(files.isNotEmpty() && files.map { it.name }.distinct().size == files.size)
        var total = 0L
        files.forEach {
            require(safeName(it.name) && it.bytes > 0)
            require(it.sha256.matches(Regex("[a-f0-9]{64}")))
            total = Math.addExact(total, it.bytes)
        }
    }

    fun url(file: ModelFile): URI {
        require(source.isPublished && file in files)
        return URI("https://github.com/${source.repository}/releases/download/${source.releaseTag}/${file.name}")
    }
}

@Serializable
data class ModelSource(val repository: String, val releaseTag: String) {
    val isPublished: Boolean get() = repository.isNotBlank() && releaseTag.isNotBlank()

    fun validate() {
        if (repository.isEmpty() && releaseTag.isEmpty()) return
        val parts = repository.split('/')
        require(parts.size == 2 && parts.all(::safeName))
        require(safeName(releaseTag) && !releaseTag.equals("latest", ignoreCase = true))
    }
}

@Serializable
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

internal fun safeName(name: String): Boolean =
    name.length <= 128 && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && name != "." && name != ".."

@Serializable
data class ModelDownloadPolicy(
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maxRedirects: Int,
    val allowedRedirectHosts: List<String>,
    val bufferBytes: Int,
    val progressIntervalMillis: Long,
    val storageReserveBytes: Long,
) {
    fun validate(): ModelDownloadPolicy = apply {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxRedirects >= 0 && bufferBytes > 0 && progressIntervalMillis > 0)
        require(storageReserveBytes >= 0 && allowedRedirectHosts.isNotEmpty())
    }
}

enum class ScannerModelPhase {
    CHECKING, UNAVAILABLE, NOT_INSTALLED, QUEUED, DOWNLOADING, VERIFYING,
    INSTALLED, CANCELLING, REMOVING, FAILED,
}

enum class ScannerModelError { NETWORK, INTEGRITY, STORAGE, SOURCE, UNKNOWN }

data class ScannerModelState(
    val phase: ScannerModelPhase = ScannerModelPhase.CHECKING,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val sourceRepository: String = "",
    val version: String = "",
    val hasStoredFiles: Boolean = false,
    val error: ScannerModelError? = null,
    val installedVersion: String? = null,
    val previousVersion: String? = null,
    val versions: List<ScannerVersionOption> = emptyList(),
    val catalogPhase: CatalogPhase = CatalogPhase.IDLE,
    val catalogError: CatalogError? = null,
    val catalogCheckedAtEpochMillis: Long? = null,
    val updateAvailable: Boolean = false,
    val catalogExpired: Boolean = false,
    val isApkLocal: Boolean = false,
)

class ModelInstallException(val reason: ScannerModelError, cause: Throwable? = null) :
    java.io.IOException(reason.name, cause)
