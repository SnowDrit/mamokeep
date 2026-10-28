package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkModelRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = ApkModelTestFixture()

    @Test fun `APK proof pins exact descriptor and leaves v1 approval unchanged`() = runBlocking {
        val repository = fixture.repository(temporary.newFolder())
        val key = fixture.release.model.directoryName
        val approval = repository.approval(key)
        assertEquals(sha256(fixture.descriptor()), approval.apkDescriptorSha256)
        assertNull(approval.envelope)
        assertFalse(fixture.release.model.source.isPublished)
        assertEquals("preview", repository.resolve(approval).channel)
        assertTrue(repository.isApkLocal(key))
        val baseline = repository.approval(fixture.signed.baseline.model.directoryName)
        assertEquals(ModelApproval(fixture.signed.baseline.model.directoryName), baseline)
        assertNull(baseline.apkDescriptorSha256)
        assertEquals("{\"key\":\"ocr-1\"}", scannerJson.encodeToString(baseline))
        assertEquals(fixture.signed.baseline, repository.resolve(baseline))
        assertTrue(fixture.signed.network.calls.isEmpty())
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `unknown key wrong digest mixed proofs and unsigned local approval fail closed`() = runBlocking {
        val repository = fixture.repository(temporary.newFolder())
        val approval = repository.approval(fixture.release.model.directoryName)
        val invalid = listOf(
            approval.copy(key = "unknown-2"),
            approval.copy(apkDescriptorSha256 = "0".repeat(64)),
            approval.copy(envelope = fixture.signed.seed.toString(Charsets.UTF_8)),
            approval.copy(apkDescriptorSha256 = null),
        )
        invalid.forEach { candidate -> assertTrue(runCatching { repository.resolve(candidate) }.isFailure) }
        assertTrue(fixture.signed.network.calls.isEmpty())
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `registry rejects published path duplicate incomplete and incompatible descriptors`() {
        val good = ApkModelDescriptor(1, fixture.release.model.directoryName, fixture.release)
        val invalid = listOf(
            good.copy(schemaVersion = 2),
            good.copy(assetDirectory = "../outside"),
            good.copy(release = fixture.release.copy(model = fixture.release.model.copy(source = ModelSource("owner/repo", "v2")))),
            good.copy(release = fixture.release.copy(model = fixture.release.model.copy(files = fixture.release.model.files.take(1)))),
            good.copy(release = fixture.release.copy(formatId = "unsupported")),
            good.copy(release = fixture.release.copy(channel = "stable")),
            good.copy(release = fixture.release.copy(maxAppVersionCode = 0)),
        )
        invalid.forEach { descriptor ->
            assertTrue(runCatching { fixture.registry(listOf(scannerJson.encodeToString(descriptor).toByteArray())) }.isFailure)
        }
        assertTrue(runCatching { fixture.registry(listOf(fixture.descriptor(), fixture.descriptor())) }.isFailure)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `APK cannot reuse baseline identity`() {
        val baseline = fixture.signed.baseline
        val sameIdentity = baseline.copy(channel = "preview", model = baseline.model.copy(source = ModelSource("", "")))
        assertTrue(runCatching {
            fixture.repository(temporary.newFolder(), fixture.registry(listOf(fixture.descriptor(sameIdentity))))
        }.exceptionOrNull() is CatalogException)
    }

    @Test fun `signed network catalog cannot claim APK identity or directory`() = runBlocking {
        val repository = fixture.repository(temporary.newFolder())
        val local = fixture.release
        val claimed = local.copy(model = local.model.copy(source = ModelSource(CatalogTestFixture.REPOSITORY, "claim-apk")))
        val directoryAlias = claimed.copy(model = claimed.model.copy(id = "ocr-apk-2", version = "apk-v5"))
        assertEquals(local.model.directoryName, directoryAlias.model.directoryName)
        for (candidate in listOf(claimed, directoryAlias)) {
            fixture.signed.respond(fixture.signed.catalog(2, listOf(fixture.signed.baseline, candidate)))
            try { repository.refresh(true); fail("Expected identity/directory collision") }
            catch (error: CatalogException) { assertEquals(CatalogError.ROLLBACK, error.reason) }
            assertEquals(1L, repository.current.catalog.sequence)
        }
        assertEquals(local, repository.resolve(repository.approval(local.model.directoryName)))
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `APK registry cannot override a collision retained in signed cache history`() = runBlocking {
        val root = temporary.newFolder()
        val networkOnly = fixture.repository(root, ApkModelRegistry.EMPTY)
        val claimed = fixture.release.copy(model = fixture.release.model.copy(
            source = ModelSource(CatalogTestFixture.REPOSITORY, "previous-network-version"),
        ))
        fixture.signed.respond(fixture.signed.catalog(2, listOf(fixture.signed.baseline, claimed)))
        networkOnly.refresh(true)
        val reopened = fixture.repository(root)
        assertNotNull(reopened.loadError)
        try { reopened.approval(fixture.release.model.directoryName); fail("Expected history collision") }
        catch (error: CatalogException) { assertEquals(CatalogError.ROLLBACK, error.reason) }
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `signed network catalog still rejects unpublished packages`() {
        val bytes = fixture.signed.sign(fixture.signed.catalog(2, listOf(fixture.signed.baseline, fixture.release)))
        try { fixture.signed.verifier.verify(bytes); fail("Expected invalid signed catalog") }
        catch (error: CatalogException) { assertEquals(CatalogError.INVALID, error.reason) }
    }
}
