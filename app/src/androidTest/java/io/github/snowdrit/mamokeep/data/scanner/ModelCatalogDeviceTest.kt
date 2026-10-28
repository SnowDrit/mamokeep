package io.github.snowdrit.mamokeep.data.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** Isolated metadata/model directories; never clears the garage or changes the real installed package. */
@RunWith(AndroidJUnit4::class)
class ModelCatalogDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String) = context.assets.open("scanner/$name").use { it.readBytes() }

    @Test fun production_seed_signature_and_baseline_are_valid_on_android() {
        val policy = scannerJson.decodeFromString<CatalogPolicy>(asset("catalog-policy.json").toString(Charsets.UTF_8))
        val seed = CatalogVerifier(policy).verify(asset("catalog-seed.json"), requireFresh = false)
        val baseline = scannerJson.decodeFromString<ModelPackage>(asset("model-package.json").toString(Charsets.UTF_8))
        assertTrue(seed.catalog.releases.any { it.model == baseline && it.compatible(policy, 1) })
        val envelope = scannerJson.decodeFromString<CatalogEnvelope>(seed.envelope)
        val tampered = envelope.copy(payload = Base64.getEncoder().encodeToString("tampered".toByteArray()))
        try {
            CatalogVerifier(policy).verify(scannerJson.encodeToString(tampered).toByteArray())
            fail("Tampered signed data must fail")
        } catch (expected: CatalogException) { assertEquals(CatalogError.SIGNATURE, expected.reason) }
    }

    @Test fun signed_update_survives_restart_and_rolls_back_without_network() = runBlocking {
        val root = File(context.noBackupFilesDir, "scanner-catalog-test-${UUID.randomUUID()}")
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val publicKey = CatalogKey(sha256(pair.public.encoded), Base64.getEncoder().encodeToString(pair.public.encoded))
        val policy = CatalogPolicy(1, ModelSource("fixture/repo", "catalog"), "catalog.json", listOf(publicKey),
            listOf("fixture-v1"), mapOf("fixture-v1" to listOf("model.bin")), 1000, 262144, 100000, 5, 3, 1, 300)
        val downloadPolicy = ModelDownloadPolicy(1000, 1000, 1, listOf("github.com"), 256, 1, 0)
        val contents = mapOf("v1" to "first model".toByteArray(), "v2" to "second model".toByteArray())
        fun release(number: Int): CatalogRelease {
            val data = contents.getValue("v$number")
            return CatalogRelease(ModelPackage(1, "fixture", number.toString(), ModelSource("fixture/repo", "v$number"),
                listOf(ModelFile("model.bin", data.size.toLong(), sha256(data)))), number.toLong(), "stable", "fixture-v1", 1, null, mapOf("en" to "Fixture"))
        }
        val first = release(1)
        val second = release(2)
        val now = System.currentTimeMillis() / 1000
        fun sign(releases: List<CatalogRelease>, sequence: Long): ByteArray {
            val payload = scannerJson.encodeToString(ModelCatalog(1, "fixture/repo", sequence, now, now + 3600, releases)).toByteArray()
            val signer = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload) }
            return scannerJson.encodeToString(CatalogEnvelope(1, publicKey.id, Base64.getEncoder().encodeToString(payload),
                Base64.getEncoder().encodeToString(signer.sign()))).toByteArray()
        }
        val seed = sign(listOf(first), 1)
        var catalogBytes = sign(listOf(first, second), 2)
        var networkCalls = 0
        var offline = false
        val transport = ModelTransport { uri ->
            if (offline) throw java.io.IOException("offline fixture")
            networkCalls++
            val data = if (uri.path.endsWith("catalog.json")) catalogBytes else contents.getValue(uri.path.split('/').dropLast(1).last())
            object : ModelResponse {
                override val body = ByteArrayInputStream(data)
                override val contentLength = data.size.toLong()
                override fun close() = body.close()
            }
        }
        try {
            val repository = ModelCatalogRepository(root, CatalogVerifier(policy), seed, first.model, 1, transport)
            val store = VersionedModelStore(root, repository, downloadPolicy, transport)
            store.initialize()
            val initial = store.prepare(repository.approval(first.model.directoryName))
            store.installPending(initial) { _, _ -> }
            repository.refresh(true)
            val next = store.prepare(repository.approval(second.model.directoryName))
            // Simulate a process restart after queue persistence, before downloading.
            val reopenedRepository = ModelCatalogRepository(root, CatalogVerifier(policy), seed, first.model, 1, transport)
            val reopenedStore = VersionedModelStore(root, reopenedRepository, downloadPolicy, transport)
            reopenedStore.initialize()
            reopenedStore.installPending(next) { _, _ -> }
            val installed = reopenedStore.snapshot(reopenedRepository.releases())
            assertEquals(second, installed.active)
            assertEquals(first, installed.previous)
            val callsBeforeRollback = networkCalls
            offline = true
            reopenedStore.rollback()
            assertEquals(first, reopenedStore.snapshot(reopenedRepository.releases()).active)
            assertEquals(callsBeforeRollback, networkCalls)
            reopenedStore.removeAll()
            assertFalse(reopenedStore.snapshot(reopenedRepository.releases()).hasStoredFiles)
            assertTrue(File(root, "catalog-cache.json").exists())
        } finally { root.deleteRecursively() }
    }
}
