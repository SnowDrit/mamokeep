package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** Ephemeral keys and tiny model bytes; no release keys or downloaded weights enter tests. */
internal class CatalogTestFixture {
    var nowSeconds = 2_000_000_000L
    val network = CatalogTestTransport()
    val baseline = release(1)
    val policy = CatalogPolicy(
        1, ModelSource(REPOSITORY, "ocr-catalog"), "catalog.json",
        listOf(CatalogKey(keyId, Base64.getEncoder().encodeToString(keys.public.encoded))),
        listOf("test-ocr-v1"), mapOf("test-ocr-v1" to listOf("det.onnx", "rec.onnx")),
        60_000, 128 * 1024, 1024 * 1024, 20, 10, 1, 30,
    )
    val downloadPolicy = ModelDownloadPolicy(1000, 1000, 3, listOf("github.com"), 4, 1, 0)
    val verifier get() = CatalogVerifier(policy) { nowSeconds }
    val seed = sign(catalog(1, listOf(baseline)))

    fun bytes(version: Long, name: String) = "test-model-$version-$name".toByteArray()

    fun release(version: Long): CatalogRelease {
        val files = listOf("det.onnx", "rec.onnx").map { name ->
            val data = bytes(version, name)
            ModelFile(name, data.size.toLong(), sha256(data))
        }
        return CatalogRelease(
            ModelPackage(1, "ocr", version.toString(), ModelSource(REPOSITORY, "ocr-v$version"), files),
            version, "stable", "test-ocr-v1", 1, notes = mapOf("ru" to "Версия $version", "en" to "Version $version"),
        )
    }

    fun catalog(sequence: Long, releases: List<CatalogRelease> = listOf(baseline)) = ModelCatalog(
        1, REPOSITORY, sequence, nowSeconds - 60, nowSeconds + 3600, releases,
    )

    fun sign(catalog: ModelCatalog): ByteArray {
        val payload = scannerJson.encodeToString(catalog).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(payload)
            sign()
        }
        return scannerJson.encodeToString(CatalogEnvelope(
            1, keyId, Base64.getEncoder().encodeToString(payload), Base64.getEncoder().encodeToString(signature),
        )).toByteArray()
    }

    fun repository(root: File, appVersion: Long = 1) = ModelCatalogRepository(
        root, verifier, seed, baseline.model, appVersion, network, { nowSeconds * 1000 },
    )

    fun respond(catalog: ModelCatalog) { network.contents[policy.url().toString()] = sign(catalog) }

    fun offerModel(release: CatalogRelease) {
        release.model.files.forEach { network.contents[release.model.url(it).toString()] = bytes(release.versionCode, it.name) }
    }

    fun store(root: File, repository: ModelCatalogRepository) = VersionedModelStore(root, repository, downloadPolicy, network)

    companion object {
        const val REPOSITORY = "test-owner/test-models"
        private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair() }
        private val keyId get() = sha256(keys.public.encoded)
    }
}

internal class CatalogTestTransport : ModelTransport {
    val contents = mutableMapOf<String, ByteArray>()
    val calls = mutableListOf<String>()
    var failingUrl: String? = null
    var contentLength: Long? = null
    var closed = 0

    override fun open(uri: URI): ModelResponse {
        calls += uri.toString()
        if (uri.toString() == failingUrl) throw IOException("Test network interrupted")
        val bytes = contents[uri.toString()] ?: throw IOException("No test response for $uri")
        return object : ModelResponse {
            override val body = ByteArrayInputStream(bytes)
            override val contentLength = this@CatalogTestTransport.contentLength ?: bytes.size.toLong()
            override fun close() { body.close(); closed++ }
        }
    }
}
