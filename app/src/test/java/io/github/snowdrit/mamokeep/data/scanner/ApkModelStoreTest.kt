package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = ApkModelTestFixture()

    @Test fun `registry availability does not auto install and explicit install is completely offline`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        assertNull(store.snapshot(repository.releases()).active)
        assertTrue(fixture.opened.isEmpty())
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        assertNull(store.snapshot(repository.releases()).active)
        val phases = mutableListOf<ScannerModelPhase>()
        store.installPending(digest) { phase, _ -> phases += phase }
        assertEquals(fixture.release, store.snapshot(repository.releases()).active)
        assertTrue(fixture.signed.network.calls.isEmpty())
        assertFalse(ScannerModelPhase.DOWNLOADING in phases)
        fixture.release.model.files.forEach { file ->
            assertEquals(file.sha256, sha256(File(root, "packages/${fixture.release.model.directoryName}/${file.name}").readBytes()))
        }
        val opened = fixture.opened.size
        store.installPending(digest) { _, _ -> fail("Installed bytes should only be rechecked") }
        assertEquals(opened, fixture.opened.size)
        val installer = ModelInstaller(File(root, "packages"), fixture.release.model, fixture.signed.downloadPolicy, fixture.signed.network)
        expectInstall(ScannerModelError.SOURCE) { installer.install { _, _ -> } }
        assertTrue(fixture.signed.network.calls.isEmpty())
    }

    @Test fun `verified local update preserves v1 and restart rollback uses no asset or network`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        installBaseline(store, repository)
        val networkCalls = fixture.signed.network.calls.size
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        store.installPending(digest) { _, _ -> }
        val current = store.snapshot(repository.releases())
        assertEquals(fixture.release, current.active)
        assertEquals(fixture.signed.baseline, current.previous)
        assertEquals(2, current.installedKeys.size)
        val assetCalls = fixture.opened.size
        val reopenedRepository = fixture.repository(root)
        val reopened = fixture.store(root, reopenedRepository)
        reopened.initialize()
        reopened.rollback()
        assertEquals(fixture.signed.baseline, reopened.snapshot(reopenedRepository.releases()).active)
        reopened.rollback()
        assertEquals(fixture.release, reopened.snapshot(reopenedRepository.releases()).active)
        assertEquals(networkCalls, fixture.signed.network.calls.size)
        assertEquals(assetCalls, fixture.opened.size)
    }

    @Test fun `wrong truncated and oversized asset bytes never activate and keep v1 intact`() = runBlocking {
        for (replacement in listOf(byteArrayOf(0), fixture.signed.bytes(2, "rec.onnx") + byteArrayOf(0),
            fixture.signed.bytes(2, "rec.onnx").copyOf().apply { this[0] = 0 })) {
            val root = temporary.newFolder()
            val repository = fixture.repository(root)
            val store = fixture.store(root, repository)
            store.initialize()
            installBaseline(store, repository)
            fixture.assets[fixture.assetPath("rec.onnx")] = replacement
            val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
            expectInstall(ScannerModelError.INTEGRITY) { store.installPending(digest) { _, _ -> } }
            assertEquals(fixture.signed.baseline, store.snapshot(repository.releases()).active)
            assertFalse(root.walk().any { it.name.endsWith(".part") })
        }
    }

    @Test fun `asset interruption resumes only verified files and reports storage error`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        fixture.failingAsset = fixture.assetPath("rec.onnx")
        expectInstall(ScannerModelError.STORAGE) { store.installPending(digest) { _, _ -> } }
        assertNull(store.snapshot(repository.releases()).active)
        fixture.failingAsset = null
        val restarted = fixture.store(root, fixture.repository(root))
        restarted.initialize()
        restarted.installPending(digest) { _, _ -> }
        assertEquals(1, fixture.opened.count { it == fixture.assetPath("det.onnx") })
        assertEquals(2, fixture.opened.count { it == fixture.assetPath("rec.onnx") })
        assertTrue(fixture.signed.network.calls.isEmpty())
    }

    @Test fun `missing APK file fails without network fallback or activation`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        fixture.assets.remove(fixture.assetPath("det.onnx"))
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        expectInstall(ScannerModelError.STORAGE) { store.installPending(digest) { _, _ -> } }
        assertNull(store.snapshot(repository.releases()).active)
        assertTrue(fixture.signed.network.calls.isEmpty())
        assertFalse(root.walk().any { it.name.endsWith(".part") })
    }

    @Test fun `cancellation before activation retains v1 and removes partial file`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        installBaseline(store, repository)
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        try {
            store.installPending(digest) { _, bytes -> if (bytes > 0) throw CancellationException("User cancelled local install") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(fixture.signed.baseline, store.snapshot(repository.releases()).active)
        assertNull(store.snapshot(repository.releases()).previous)
        assertFalse(root.walk().any { it.name.endsWith(".part") })
    }

    @Test fun `changed or missing descriptor cannot resume old pending approval`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        installBaseline(store, repository)
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        val changed = fixture.release.copy(notes = mapOf("en" to "Changed bytes, unchanged model identity"))
        for (registry in listOf(fixture.registry(listOf(fixture.descriptor(changed))), ApkModelRegistry.EMPTY)) {
            val reopenedRepository = fixture.repository(root, registry)
            val reopened = fixture.store(root, reopenedRepository)
            reopened.initialize()
            try { reopened.installPending(digest) { _, _ -> }; fail("Expected stale APK proof") }
            catch (error: CatalogException) { assertEquals(CatalogError.INVALID, error.reason) }
            assertEquals(fixture.signed.baseline, reopened.snapshot(reopenedRepository.releases()).active)
        }
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `new APK compatibility still blocks pending local installation`() = runBlocking {
        val root = temporary.newFolder()
        val limited = fixture.release.copy(maxAppVersionCode = 1)
        val registry = fixture.registry(listOf(fixture.descriptor(limited)))
        val repository = fixture.repository(root, registry)
        val store = fixture.store(root, repository)
        store.initialize()
        val digest = store.prepare(repository.approval(limited.model.directoryName))
        val newRepository = fixture.repository(root, registry, appVersion = 2)
        val reopened = fixture.store(root, newRepository)
        reopened.initialize()
        try { reopened.installPending(digest) { _, _ -> }; fail("Expected incompatible local package") }
        catch (error: CatalogException) { assertEquals(CatalogError.INVALID, error.reason) }
        assertNull(reopened.snapshot(newRepository.releases()).active)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun `local activation waits for existing inference lease and tampering stays observable`() = runBlocking {
        val root = temporary.newFolder()
        val repository = fixture.repository(root)
        val store = fixture.store(root, repository)
        store.initialize()
        installBaseline(store, repository)
        val digest = store.prepare(repository.approval(fixture.release.model.directoryName))
        val acquired = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val inference = async { store.withActiveModel { acquired.complete(Unit); hold.await() } }
        acquired.await()
        val update = async { store.installPending(digest) { _, _ -> } }
        yield()
        assertFalse(update.isCompleted)
        assertTrue(fixture.opened.isEmpty())
        hold.complete(Unit)
        inference.await()
        update.await()
        File(root, "packages/${fixture.release.model.directoryName}/rec.onnx").writeText("damaged")
        expectInstall(ScannerModelError.INTEGRITY) { store.withActiveModel { fail("Damaged local model must not infer") } }
    }

    private suspend fun installBaseline(store: VersionedModelStore, repository: ModelCatalogRepository) {
        fixture.signed.offerModel(fixture.signed.baseline)
        val digest = store.prepare(repository.approval(fixture.signed.baseline.model.directoryName))
        store.installPending(digest) { _, _ -> }
    }

    private suspend fun expectInstall(reason: ScannerModelError, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: ModelInstallException) { assertEquals(reason, error.reason) }
    }
}
