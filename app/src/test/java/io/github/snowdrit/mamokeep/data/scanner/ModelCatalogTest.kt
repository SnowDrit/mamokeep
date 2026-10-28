package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ModelCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = CatalogTestFixture()

    @Test fun `signed raw payload validates and unknown key fails closed`() {
        val verified = fixture.verifier.verify(fixture.seed)
        assertEquals(fixture.baseline, verified.catalog.releases.single())
        val envelope = scannerJson.decodeFromString<CatalogEnvelope>(fixture.seed.toString(Charsets.UTF_8))
        assertCatalogFailure(CatalogError.SIGNATURE) {
            fixture.verifier.verify(scannerJson.encodeToString(envelope.copy(keyId = "unknown-key")).toByteArray())
        }
    }

    @Test fun `tampered payload or signature is rejected before trusting release fields`() {
        val envelope = scannerJson.decodeFromString<CatalogEnvelope>(fixture.seed.toString(Charsets.UTF_8))
        val payload = Base64.getDecoder().decode(envelope.payload)
        val altered = payload.toString(Charsets.UTF_8).replace("ocr-v1", "ocr-v9").toByteArray()
        assertFalse(payload.contentEquals(altered))
        assertCatalogFailure(CatalogError.SIGNATURE) {
            fixture.verifier.verify(scannerJson.encodeToString(envelope.copy(payload = Base64.getEncoder().encodeToString(altered))).toByteArray())
        }
        val signature = Base64.getDecoder().decode(envelope.signature).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertCatalogFailure(CatalogError.SIGNATURE) {
            fixture.verifier.verify(scannerJson.encodeToString(envelope.copy(signature = Base64.getEncoder().encodeToString(signature))).toByteArray())
        }
    }

    @Test fun `expiry blocks new catalog but preserves verifiable offline descriptions`() {
        val catalog = fixture.catalog(1).copy(expiresAtEpochSeconds = fixture.nowSeconds)
        val signed = fixture.sign(catalog)
        assertCatalogFailure(CatalogError.EXPIRED) { fixture.verifier.verify(signed) }
        assertEquals(catalog, fixture.verifier.verify(signed, requireFresh = false).catalog)
        val future = fixture.catalog(1).copy(generatedAtEpochSeconds = fixture.nowSeconds + fixture.policy.clockSkewSeconds + 1)
        assertCatalogFailure(CatalogError.INVALID) { fixture.verifier.verify(fixture.sign(future), requireFresh = false) }
    }

    @Test fun `catalog byte package file and version bounds reject oversized descriptions`() {
        val second = fixture.release(2)
        val cases = listOf(
            fixture.policy.copy(maxCatalogBytes = fixture.seed.size - 1) to fixture.seed,
            fixture.policy.copy(maxPackageBytes = fixture.baseline.model.totalBytes - 1) to fixture.seed,
            fixture.policy.copy(maxFiles = 1) to fixture.seed,
            fixture.policy.copy(maxVersions = 1) to fixture.sign(fixture.catalog(2, listOf(fixture.baseline, second))),
        )
        cases.forEach { (policy, bytes) ->
            assertCatalogFailure(CatalogError.INVALID) { CatalogVerifier(policy) { fixture.nowSeconds }.verify(bytes) }
        }
    }

    @Test fun `repository origin and in-catalog storage collisions are rejected`() {
        assertCatalogFailure(CatalogError.INVALID) {
            fixture.verifier.verify(fixture.sign(fixture.catalog(1).copy(repository = "other-owner/other-models")))
        }
        val first = fixture.release(2).let { it.copy(model = it.model.copy(id = "ocr-a", version = "b")) }
        val second = fixture.release(3).let { it.copy(model = it.model.copy(id = "ocr", version = "a-b")) }
        assertEquals(first.model.directoryName, second.model.directoryName)
        assertCatalogFailure(CatalogError.INVALID) {
            fixture.verifier.verify(fixture.sign(fixture.catalog(2, listOf(first, second))))
        }
    }

    @Test fun `compatibility checks app range format and required file roles`() {
        val release = fixture.release(2).copy(minAppVersionCode = 3, maxAppVersionCode = 5)
        assertFalse(release.compatible(fixture.policy, 2))
        assertTrue(release.compatible(fixture.policy, 3))
        assertTrue(release.compatible(fixture.policy, 5))
        assertFalse(release.compatible(fixture.policy, 6))
        assertFalse(release.copy(formatId = "future-format").compatible(fixture.policy, 4))
        assertFalse(release.copy(model = release.model.copy(files = release.model.files.take(1))).compatible(fixture.policy, 4))
    }

    @Test fun `lower and equal conflicting sequence leave persisted current catalog unchanged`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val newer = fixture.catalog(3, listOf(fixture.baseline, fixture.release(2)))
        fixture.respond(newer)
        assertTrue(repository.refresh(true))
        val cache = File(root, "catalog-cache.json").readBytes()
        val digest = repository.current.payloadDigest
        val invalid = listOf(fixture.catalog(2), newer.copy(releases = listOf(fixture.baseline)))
        invalid.forEach { next ->
            fixture.respond(next)
            assertCatalogFailureSuspending(CatalogError.ROLLBACK) { repository.refresh(true) }
            assertEquals(digest, repository.current.payloadDigest)
            assertArrayEquals(cache, File(root, "catalog-cache.json").readBytes())
        }
        fixture.respond(newer)
        assertTrue(repository.refresh(true))
        assertEquals(digest, fixture.repository(root).current.payloadDigest)
    }

    @Test fun `version dropped then readded with different bytes is rejected after restart`() = runBlocking {
        val root = temporary.newFolder()
        val second = fixture.release(2)
        val repository = fixture.repository(root)
        fixture.respond(fixture.catalog(2, listOf(fixture.baseline, second)))
        repository.refresh(true)
        fixture.respond(fixture.catalog(3))
        repository.refresh(true)
        val restarted = fixture.repository(root)
        val changed = second.copy(model = second.model.copy(files = second.model.files.map { it.copy(sha256 = "a".repeat(64)) }))
        fixture.respond(fixture.catalog(4, listOf(fixture.baseline, changed)))
        assertCatalogFailureSuspending(CatalogError.ROLLBACK) { restarted.refresh(true) }
        assertEquals(3L, restarted.current.catalog.sequence)
    }

    @Test fun `historical directory collision cannot replace removed version`() = runBlocking {
        val root = temporary.newFolder()
        val first = fixture.release(2).let { it.copy(model = it.model.copy(id = "ocr-a", version = "b")) }
        val second = fixture.release(3).let { it.copy(model = it.model.copy(id = "ocr", version = "a-b")) }
        val repository = fixture.repository(root)
        fixture.respond(fixture.catalog(2, listOf(first)))
        repository.refresh(true)
        fixture.respond(fixture.catalog(3))
        repository.refresh(true)
        fixture.respond(fixture.catalog(4, listOf(second)))
        assertCatalogFailureSuspending(CatalogError.ROLLBACK) { fixture.repository(root).refresh(true) }
    }

    @Test fun `bounded chunked read fails without replacing offline catalog`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        fixture.network.contentLength = -1
        fixture.network.contents[fixture.policy.url().toString()] = ByteArray(fixture.policy.maxCatalogBytes + 1)
        assertCatalogFailureSuspending(CatalogError.INVALID) { repository.refresh(true) }
        assertEquals(1L, repository.current.catalog.sequence)
        assertEquals(1, fixture.network.closed)
        assertFalse(File(root, "catalog-cache.json").exists())
    }

    @Test fun `successful checks are throttled and offline failure preserves cached versions`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        fixture.respond(fixture.catalog(2, listOf(fixture.baseline, fixture.release(2))))
        assertTrue(repository.refresh(false))
        assertFalse(repository.refresh(false))
        assertEquals(1, fixture.network.calls.size)
        fixture.network.failingUrl = fixture.policy.url().toString()
        assertCatalogFailureSuspending(CatalogError.NETWORK) { repository.refresh(true) }
        val restarted = fixture.repository(root)
        assertEquals(2L, restarted.current.catalog.sequence)
        assertEquals(2, restarted.releases().size)
        assertFalse(restarted.refresh(false))
    }

    @Test fun `corrupt cached history blocks network overwrite while baseline stays available`() = runBlocking {
        val root = temporary.newFolder()
        File(root, "catalog-cache.json").writeText("broken")
        val repository = fixture.repository(root)
        assertEquals(CatalogError.STORAGE, repository.loadError)
        assertEquals(fixture.baseline, repository.resolve(repository.approval(fixture.baseline.model.directoryName)))
        assertCatalogFailureSuspending(CatalogError.STORAGE) { repository.refresh(true) }
        assertTrue(fixture.network.calls.isEmpty())
    }

    private fun assertCatalogFailure(reason: CatalogError, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: CatalogException) { assertEquals(reason, e.reason) }
    }

    private suspend fun assertCatalogFailureSuspending(reason: CatalogError, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: CatalogException) { assertEquals(reason, e.reason) }
    }
}
