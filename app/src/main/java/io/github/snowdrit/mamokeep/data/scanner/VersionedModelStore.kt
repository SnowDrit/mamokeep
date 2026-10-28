package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
private data class ActiveModels(val active: ModelApproval? = null, val previous: ModelApproval? = null)

data class StoredModels(
    val active: CatalogRelease?,
    val previous: CatalogRelease?,
    val installedKeys: Set<String>,
    val hasStoredFiles: Boolean,
)

/** One lock covers all installer instances and the active pointer, including cancellation/removal. */
class VersionedModelStore(
    private val root: File,
    private val catalog: ModelCatalogRepository,
    private val policy: ModelDownloadPolicy,
    private val transport: ModelTransport,
) {
    private val mutex = Mutex()
    private val pointerFile = File(root, "active-models.json")
    private val pendingFile = File(root, "pending-model.json")
    private val packagesRoot = File(root, "packages")
    private var pointer = ActiveModels()
    var pointerError: ScannerModelError? = null
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val legacy = ModelInstaller(root, catalog.bundledRelease.model, policy, transport)
            if (!installer(catalog.bundledRelease).isInstalled() && legacy.isInstalled()) {
                if (!packagesRoot.isDirectory && !packagesRoot.mkdirs()) throw ModelInstallException(ScannerModelError.STORAGE)
                val name = catalog.bundledRelease.model.directoryName
                if (!File(root, name).renameTo(File(packagesRoot, name))) throw ModelInstallException(ScannerModelError.STORAGE)
            }
            if (pointerFile.exists()) {
                pointer = try {
                    scannerJson.decodeFromString<ActiveModels>(pointerFile.inputStream().use {
                        it.readBounded(metadataLimit()).toString(Charsets.UTF_8)
                    })
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    // An existing pointer is authoritative. Preserve damaged bytes and
                    // require explicit package removal; never silently activate the baseline.
                    pointerError = ScannerModelError.INTEGRITY
                    return@withLock
                }
            }
            // Migration from the original single-version downloader; no network or user data changes.
            if (!pointerFile.exists() && installer(catalog.bundledRelease).isInstalled()) {
                savePointer(ActiveModels(ModelApproval(catalog.bundledRelease.model.directoryName)))
            }
        }
    }

    suspend fun snapshot(releases: List<CatalogRelease>): StoredModels = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = verifiedRelease(pointer.active)
            val previous = verifiedRelease(pointer.previous)
            val all = (releases + listOfNotNull(active, previous)).distinctBy { it.model.directoryName }
            val installed = all.filter { installer(it).isInstalled() }.map { it.model.directoryName }.toSet()
            StoredModels(active, previous, installed, hasModelFiles())
        }
    }

    suspend fun prepare(approval: ModelApproval): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkPointer()
            val release = catalog.resolve(approval)
            if (!installer(release).isInstalled()) catalog.resolve(approval, requireFresh = true)
            val bytes = scannerJson.encodeToString(approval).toByteArray()
            atomicWrite(pendingFile, bytes)
            // Discard abandoned partial versions; keep verified active/previous and this requested target.
            cleanup(setOfNotNull(pointer.active?.key, pointer.previous?.key, approval.key))
            sha256(bytes)
        }
    }

    suspend fun installPending(expectedDigest: String, progress: suspend (ScannerModelPhase, Long) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkPointer()
            val bytes = pendingFile.inputStream().use { it.readBounded(metadataLimit()) }
            if (sha256(bytes) != expectedDigest) throw ModelInstallException(ScannerModelError.SOURCE)
            val approval = scannerJson.decodeFromString<ModelApproval>(bytes.toString(Charsets.UTF_8))
            val release = catalog.resolve(approval)
            val target = installer(release)
            if (!target.isInstalled()) catalog.resolve(approval, requireFresh = true)
            if (approval.apkDescriptorSha256 != null) {
                target.installFromApk({ file -> catalog.openApkAsset(approval, file) }, progress)
            } else {
                target.install(progress)
            }
            currentCoroutineContext().ensureActive()
            // All target bytes are verified before the atomic pointer changes.
            if (pointer.active != approval) {
                val previous = pointer.active?.takeIf { verifiedRelease(it) != null && it.key != approval.key }
                savePointer(ActiveModels(approval, previous))
            }
            cleanup(setOfNotNull(pointer.active?.key, pointer.previous?.key))
        }
    }

    suspend fun pendingKey(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!pendingFile.exists()) return@withLock null
            try {
                val approval = scannerJson.decodeFromString<ModelApproval>(pendingFile.inputStream().use {
                    it.readBounded(metadataLimit()).toString(Charsets.UTF_8)
                })
                catalog.resolve(approval).model.directoryName
            } catch (_: Exception) { null }
        }
    }

    suspend fun rollback() = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkPointer()
            val target = pointer.previous ?: throw ModelInstallException(ScannerModelError.SOURCE)
            if (verifiedRelease(target) == null) throw ModelInstallException(ScannerModelError.INTEGRITY)
            currentCoroutineContext().ensureActive()
            savePointer(ActiveModels(target, pointer.active?.takeIf { verifiedRelease(it) != null }))
        }
    }

    suspend fun removeAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Catalog and its rollback-protection history survive model removal.
            savePointer(ActiveModels())
            pointerError = null
            for (directory in packagesRoot.listFiles().orEmpty().filter { it.isDirectory }) {
                if (!directory.deleteRecursively()) throw ModelInstallException(ScannerModelError.STORAGE)
            }
            if (pendingFile.exists() && !pendingFile.delete()) throw ModelInstallException(ScannerModelError.STORAGE)
        }
    }

    /** Holds the same lease as removal and activation for the entire local inference. */
    suspend fun <T> withActiveModel(block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkPointer()
            val approval = pointer.active ?: throw ModelInstallException(ScannerModelError.SOURCE)
            val release = catalog.resolve(approval)
            if (!installer(release).isInstalled()) throw ModelInstallException(ScannerModelError.INTEGRITY)
            block(File(packagesRoot, release.model.directoryName))
        }
    }

    private fun checkPointer() {
        pointerError?.let { throw ModelInstallException(it) }
    }

    private suspend fun verifiedRelease(approval: ModelApproval?): CatalogRelease? {
        if (approval == null) return null
        return try {
            catalog.resolve(approval).takeIf { installer(it).isInstalled() }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null }
    }

    private fun installer(release: CatalogRelease) = ModelInstaller(packagesRoot, release.model, policy, transport)
    private fun hasModelFiles() = packagesRoot.listFiles().orEmpty().any { dir -> dir.isDirectory && dir.listFiles()?.any { it.isFile } == true }
    private fun savePointer(next: ActiveModels) {
        atomicWrite(pointerFile, scannerJson.encodeToString(next).toByteArray())
        pointer = next
    }

    private fun cleanup(keep: Set<String>) {
        // Best effort after activation: a failed cleanup must not turn a successful update into a failure.
        packagesRoot.listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
            if (directory.name !in keep && keep.none { directory.name == ".install-$it" }) directory.deleteRecursively()
        }
    }

    private fun metadataLimit() = Math.multiplyExact(catalog.policy.maxCatalogBytes, METADATA_MULTIPLIER)
    companion object { private const val METADATA_MULTIPLIER = 4 }
}
