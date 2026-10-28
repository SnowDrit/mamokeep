package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal val scannerJson = Json { ignoreUnknownKeys = false }
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
internal fun ModelPackage.digest(): String = sha256(scannerJson.encodeToString(copy(files = files.sortedBy { it.name })).toByteArray())

@Serializable
data class CatalogKey(val id: String, val publicKey: String)

@Serializable
data class CatalogPolicy(
    val schemaVersion: Int,
    val source: ModelSource,
    val assetName: String,
    val trustedKeys: List<CatalogKey>,
    val supportedFormats: List<String>,
    val requiredFilesByFormat: Map<String, List<String>>,
    val checkIntervalMillis: Long,
    val maxCatalogBytes: Int,
    val maxPackageBytes: Long,
    val maxVersions: Int,
    val maxFiles: Int,
    val minimumSequence: Long,
    val clockSkewSeconds: Long,
) {
    fun validate(): CatalogPolicy = apply {
        require(schemaVersion == 1)
        source.validate()
        require(source.isPublished && safeName(assetName))
        require(trustedKeys.isNotEmpty() && trustedKeys.map { it.id }.distinct().size == trustedKeys.size)
        require(supportedFormats.isNotEmpty() && supportedFormats.all(::safeName))
        require(supportedFormats.all { requiredFilesByFormat[it]?.isNotEmpty() == true })
        require(requiredFilesByFormat.values.flatten().all(::safeName))
        require(checkIntervalMillis > 0 && maxCatalogBytes > 0 && maxPackageBytes > 0)
        require(maxVersions > 0 && maxFiles > 0 && minimumSequence > 0 && clockSkewSeconds >= 0)
    }

    fun url() = URI("https://github.com/${source.repository}/releases/download/${source.releaseTag}/$assetName")
}

@Serializable
data class CatalogEnvelope(val schemaVersion: Int, val keyId: String, val payload: String, val signature: String)

@Serializable
data class ModelCatalog(
    val schemaVersion: Int,
    val repository: String,
    val sequence: Long,
    val generatedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val releases: List<CatalogRelease>,
)

@Serializable
data class CatalogRelease(
    val model: ModelPackage,
    val versionCode: Long,
    val channel: String,
    val formatId: String,
    val minAppVersionCode: Long,
    val maxAppVersionCode: Long? = null,
    val notes: Map<String, String>,
) {
    fun compatible(policy: CatalogPolicy, appVersion: Long): Boolean =
        formatId in policy.supportedFormats && appVersion >= minAppVersionCode &&
            (maxAppVersionCode == null || appVersion <= maxAppVersionCode) &&
            policy.requiredFilesByFormat[formatId]?.all { name -> model.files.any { it.name == name } } == true
}

data class VerifiedCatalog(val catalog: ModelCatalog, val envelope: String, val payloadDigest: String)

enum class CatalogPhase { IDLE, CHECKING, READY, FAILED }
enum class CatalogError { NETWORK, SIGNATURE, INVALID, EXPIRED, ROLLBACK, STORAGE, UNAVAILABLE }
class CatalogException(val reason: CatalogError, cause: Throwable? = null) : java.io.IOException(reason.name, cause)

data class ScannerVersionOption(
    val key: String,
    val version: String,
    val totalBytes: Long,
    val channel: String,
    val notes: Map<String, String>,
    val compatible: Boolean,
    val installed: Boolean,
    val selected: Boolean,
    val isActive: Boolean,
)

/** The signature covers raw payload bytes, avoiding cross-language JSON canonicalization. */
class CatalogVerifier(val policy: CatalogPolicy, private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    init { policy.validate() }

    fun verify(bytes: ByteArray, requireFresh: Boolean = true): VerifiedCatalog {
        try {
            require(bytes.size <= policy.maxCatalogBytes)
            val envelopeText = bytes.toString(Charsets.UTF_8)
            val envelope = scannerJson.decodeFromString<CatalogEnvelope>(envelopeText)
            require(envelope.schemaVersion == 1)
            val key = policy.trustedKeys.firstOrNull { it.id == envelope.keyId }
                ?: throw CatalogException(CatalogError.SIGNATURE)
            val payload: ByteArray
            try {
                val der = Base64.getDecoder().decode(key.publicKey)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
                require(publicKey.modulus.bitLength() >= MIN_RSA_BITS && sha256(der) == key.id)
                payload = Base64.getDecoder().decode(envelope.payload)
                val verifier = Signature.getInstance("SHA256withRSA")
                verifier.initVerify(publicKey)
                verifier.update(payload)
                if (!verifier.verify(Base64.getDecoder().decode(envelope.signature))) throw CatalogException(CatalogError.SIGNATURE)
            } catch (e: CatalogException) { throw e
            } catch (e: Exception) { throw CatalogException(CatalogError.SIGNATURE, e) }
            val catalog = scannerJson.decodeFromString<ModelCatalog>(payload.toString(Charsets.UTF_8))
            validate(catalog)
            if (requireFresh && catalog.sequence < policy.minimumSequence) throw CatalogException(CatalogError.ROLLBACK)
            if (requireFresh && expired(catalog)) throw CatalogException(CatalogError.EXPIRED)
            return VerifiedCatalog(catalog, envelopeText, sha256(payload))
        } catch (e: CatalogException) { throw e
        } catch (e: Exception) { throw CatalogException(CatalogError.INVALID, e) }
    }

    fun expired(catalog: ModelCatalog) = nowSeconds() >= catalog.expiresAtEpochSeconds

    private fun validate(catalog: ModelCatalog) {
        require(catalog.schemaVersion == 1 && catalog.repository == policy.source.repository)
        require(catalog.sequence > 0)
        require(catalog.generatedAtEpochSeconds > 0 && catalog.expiresAtEpochSeconds > catalog.generatedAtEpochSeconds)
        require(catalog.generatedAtEpochSeconds <= nowSeconds() + policy.clockSkewSeconds)
        require(catalog.releases.isNotEmpty() && catalog.releases.size <= policy.maxVersions)
        require(catalog.releases.map { it.model.directoryName }.distinct().size == catalog.releases.size)
        require(catalog.releases.map { it.versionCode }.distinct().size == catalog.releases.size)
        require(catalog.releases.map { it.model.source.releaseTag }.distinct().size == catalog.releases.size)
        catalog.releases.forEach { release ->
            release.model.validate()
            require(release.model.source.repository == catalog.repository && release.model.source.isPublished)
            require(release.model.source.releaseTag != policy.source.releaseTag)
            require(release.model.files.size <= policy.maxFiles && release.model.totalBytes <= policy.maxPackageBytes)
            require(release.versionCode > 0 && release.channel in setOf("stable", "preview"))
            require(safeName(release.formatId) && release.minAppVersionCode > 0)
            require(release.maxAppVersionCode == null || release.maxAppVersionCode >= release.minAppVersionCode)
            require(release.notes.isNotEmpty() && release.notes.size <= MAX_NOTE_LANGUAGES)
            require(release.notes.all { (language, note) -> safeName(language) && note.length <= MAX_NOTE_LENGTH })
        }
    }

    companion object {
        private const val MIN_RSA_BITS = 3072
        private const val MAX_NOTE_LENGTH = 4000
        private const val MAX_NOTE_LANGUAGES = 32
    }
}
