package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VersionedModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = CatalogTestFixture()

    @Test fun `damaged active pointer is preserved and cannot silently activate baseline`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val original = fixture.store(root, repository)
        original.initialize()
        install(original, repository, fixture.baseline)
        val pointer = File(root, "active-models.json")
        pointer.writeText("{damaged active model")
        val restarted = fixture.store(root, repository)
        restarted.initialize()
        assertEquals(ScannerModelError.INTEGRITY, restarted.pointerError)
        assertEquals("{damaged active model", pointer.readText())
        assertNull(restarted.snapshot(repository.releases()).active)
        assertInstallFailure(ScannerModelError.INTEGRITY) { restarted.withActiveModel { fail("Must not infer") } }
        assertInstallFailure(ScannerModelError.INTEGRITY) { restarted.prepare(repository.approval(fixture.baseline.model.directoryName)) }
        restarted.removeAll()
        assertNull(restarted.pointerError)
        install(restarted, repository, fixture.baseline)
        assertEquals(fixture.baseline, restarted.snapshot(repository.releases()).active)
    }

    @Test fun `model removal waits for inference lease and cancellation releases it`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val acquired = CompletableDeferred<File>()
        val waiting = CompletableDeferred<Unit>()
        val inference = async {
            store.withActiveModel { directory -> acquired.complete(directory); waiting.await() }
        }
        val directory = acquired.await()
        val removal = async { store.removeAll() }
        yield()
        assertFalse(removal.isCompleted)
        assertTrue(File(directory, "det.onnx").exists())
        inference.cancel()
        inference.join()
        removal.await()
        assertFalse(directory.exists())
    }

    @Test fun `inference rechecks bytes and cannot use a damaged installed model`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        File(root, "packages/${fixture.baseline.model.directoryName}/rec.onnx").writeText("damaged")
        assertInstallFailure(ScannerModelError.INTEGRITY) { store.withActiveModel { fail("Must not infer") } }
    }

    @Test fun `update activates verified target preserves previous and rollback works offline`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val second = fixture.release(2)
        offer(repository, second)
        install(store, repository, second)
        val updated = store.snapshot(repository.releases())
        assertEquals(second, updated.active)
        assertEquals(fixture.baseline, updated.previous)
        assertEquals(setOf(fixture.baseline.model.directoryName, second.model.directoryName), updated.installedKeys)
        val networkCalls = fixture.network.calls.size
        val restarted = fixture.store(root, fixture.repository(root))
        restarted.initialize()
        restarted.rollback()
        val reverted = restarted.snapshot(repository.releases())
        assertEquals(fixture.baseline, reverted.active)
        assertEquals(second, reverted.previous)
        assertEquals(networkCalls, fixture.network.calls.size)
    }

    @Test fun `failed update preserves active bytes and retry survives process restart`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val second = fixture.release(2)
        offer(repository, second)
        fixture.offerModel(second)
        val digest = store.prepare(repository.approval(second.model.directoryName))
        val detectorUrl = second.model.url(second.model.files.first()).toString()
        fixture.network.failingUrl = second.model.url(second.model.files.last()).toString()
        assertInstallFailure(ScannerModelError.NETWORK) { store.installPending(digest) { _, _ -> } }
        assertEquals(fixture.baseline, store.snapshot(repository.releases()).active)
        assertArrayEquals(fixture.bytes(1, "det.onnx"), File(root, "packages/${fixture.baseline.model.directoryName}/det.onnx").readBytes())
        fixture.network.failingUrl = null
        val restartedRepository = fixture.repository(root)
        val restarted = fixture.store(root, restartedRepository)
        restarted.initialize()
        assertEquals(second.model.directoryName, restarted.pendingKey())
        restarted.installPending(digest) { _, _ -> }
        assertEquals(second, restarted.snapshot(restartedRepository.releases()).active)
        assertEquals(1, fixture.network.calls.count { it == detectorUrl })
    }

    @Test fun `cancel after complete verification leaves original model active`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val second = fixture.release(2)
        offer(repository, second)
        fixture.offerModel(second)
        val digest = store.prepare(repository.approval(second.model.directoryName))
        var finalVerification = 0
        try {
            store.installPending(digest) { phase, bytes ->
                if (phase == ScannerModelPhase.VERIFYING && bytes == second.model.totalBytes) {
                    // The first callback precedes the last file's hash check; the second follows it.
                    finalVerification++
                    if (finalVerification == 2) throw CancellationException("User canceled")
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(2, finalVerification)
        val snapshot = store.snapshot(repository.releases())
        assertEquals(fixture.baseline, snapshot.active)
        assertNull(snapshot.previous)
        assertFalse(second.model.directoryName in snapshot.installedKeys)
        assertFalse(root.walk().any { it.name.endsWith(".part") })
    }

    @Test fun `pending approval remains bound to selected signed version when catalog changes`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val second = fixture.release(2)
        offer(repository, second)
        fixture.offerModel(second)
        val store = fixture.store(root, repository)
        store.initialize()
        val digest = store.prepare(repository.approval(second.model.directoryName))
        fixture.respond(fixture.catalog(3, listOf(fixture.baseline, fixture.release(3))))
        repository.refresh(true)
        val restartedRepository = fixture.repository(root)
        val restarted = fixture.store(root, restartedRepository)
        restarted.initialize()
        restarted.installPending(digest) { _, _ -> }
        assertEquals(second, restarted.snapshot(restartedRepository.releases()).active)
        assertFalse(fixture.network.calls.any { "/ocr-v3/" in it })
    }

    @Test fun `pending file tampering cannot change queued target or active model`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val second = fixture.release(2)
        offer(repository, second)
        val digest = store.prepare(repository.approval(second.model.directoryName))
        File(root, "pending-model.json").writeText(scannerJson.encodeToString(repository.approval(fixture.baseline.model.directoryName)))
        val before = fixture.network.calls.size
        val restarted = fixture.store(root, fixture.repository(root))
        restarted.initialize()
        assertInstallFailure(ScannerModelError.SOURCE) { restarted.installPending(digest) { _, _ -> } }
        assertEquals(before, fixture.network.calls.size)
        assertEquals(fixture.baseline, restarted.snapshot(repository.releases()).active)
    }

    @Test fun `expired pending network approval is blocked but installed rollback stays offline`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val second = fixture.release(2)
        offer(repository, second)
        fixture.offerModel(second)
        val digest = store.prepare(repository.approval(second.model.directoryName))
        fixture.nowSeconds += 3600
        val before = fixture.network.calls.size
        try { store.installPending(digest) { _, _ -> }; fail("Expected expiry") }
        catch (e: CatalogException) { assertEquals(CatalogError.EXPIRED, e.reason) }
        assertEquals(before, fixture.network.calls.size)
        assertEquals(fixture.baseline, store.snapshot(repository.releases()).active)
        fixture.nowSeconds -= 3600
        store.installPending(digest) { _, _ -> }
        fixture.nowSeconds += 3600
        store.rollback()
        assertEquals(fixture.baseline, store.snapshot(repository.releases()).active)
        store.rollback()
        assertEquals(second, store.snapshot(repository.releases()).active)
    }

    @Test fun `new APK compatibility is rechecked before resuming pending download`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val second = fixture.release(2).copy(maxAppVersionCode = 1)
        offer(repository, second)
        val store = fixture.store(root, repository)
        store.initialize()
        val digest = store.prepare(repository.approval(second.model.directoryName))
        val restartedRepository = fixture.repository(root, appVersion = 2)
        val restarted = fixture.store(root, restartedRepository)
        restarted.initialize()
        val before = fixture.network.calls.size
        try { restarted.installPending(digest) { _, _ -> }; fail("Expected incompatible model") }
        catch (e: CatalogException) { assertEquals(CatalogError.INVALID, e.reason) }
        assertEquals(before, fixture.network.calls.size)
        assertNull(restarted.snapshot(restartedRepository.releases()).active)
    }

    @Test fun `corrupted previous files prevent rollback without losing healthy active version`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        install(store, repository, fixture.baseline)
        val second = fixture.release(2)
        offer(repository, second)
        install(store, repository, second)
        File(root, "packages/${fixture.baseline.model.directoryName}/rec.onnx").writeText("damaged")
        assertInstallFailure(ScannerModelError.INTEGRITY) { store.rollback() }
        assertEquals(second, store.snapshot(repository.releases()).active)
    }

    @Test fun `legacy baseline migration uses local files and removal keeps catalog history`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        fixture.offerModel(fixture.baseline)
        ModelInstaller(root, fixture.baseline.model, fixture.downloadPolicy, fixture.network).install { _, _ -> }
        val calls = fixture.network.calls.size
        val store = fixture.store(root, repository)
        store.initialize()
        assertEquals(fixture.baseline, store.snapshot(repository.releases()).active)
        assertEquals(calls, fixture.network.calls.size)
        offer(repository, fixture.release(2))
        store.removeAll()
        assertNull(store.snapshot(repository.releases()).active)
        assertFalse(store.snapshot(repository.releases()).hasStoredFiles)
        assertTrue(File(root, "catalog-cache.json").isFile)
        assertEquals(2L, fixture.repository(root).current.catalog.sequence)
    }

    private suspend fun offer(repository: ModelCatalogRepository, release: CatalogRelease) {
        fixture.respond(fixture.catalog(release.versionCode, listOf(fixture.baseline, release)))
        repository.refresh(true)
    }

    private suspend fun install(store: VersionedModelStore, repository: ModelCatalogRepository, release: CatalogRelease) {
        fixture.offerModel(release)
        val digest = store.prepare(repository.approval(release.model.directoryName))
        store.installPending(digest) { _, _ -> }
    }

    private suspend fun assertInstallFailure(reason: ScannerModelError, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: ModelInstallException) { assertEquals(reason, e.reason) }
    }
    @Test fun `new APK seed and sequence floor preserve existing compatible models`() = runBlocking {
        val fixture = CatalogTestFixture()
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val second = fixture.release(2)
        fixture.respond(fixture.catalog(2, listOf(fixture.baseline, second)))
        repository.refresh(true)
        fixture.offerModel(second)
        val store = fixture.store(root, repository)
        store.initialize()
        val digest = store.prepare(repository.approval(second.model.directoryName))
        store.installPending(digest) { _, _ -> }
        val policy = fixture.policy.copy(minimumSequence = 3)
        val verifier = CatalogVerifier(policy) { fixture.nowSeconds }
        val newSeed = fixture.sign(fixture.catalog(3, listOf(fixture.baseline, second)))
        val reopened = ModelCatalogRepository(root, verifier, newSeed, fixture.baseline.model, 1, fixture.network)
        assertNull(reopened.loadError)
        assertEquals(3L, reopened.current.catalog.sequence)
        val restored = VersionedModelStore(root, reopened, fixture.downloadPolicy, fixture.network)
        restored.initialize()
        assertEquals(second, restored.snapshot(reopened.releases()).active)
    }

}
