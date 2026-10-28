package io.github.snowdrit.mamokeep.data.scanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class ApkModelIndex(val schemaVersion: Int, val descriptors: List<String>)

/** A process-wide owner. Catalog checks never enqueue weight downloads. */
class ScannerModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager = WorkManager.getInstance(appContext)
    private val actions = Mutex()
    private val ready = CompletableDeferred<Boolean>()
    private val _state = MutableStateFlow(ScannerModelState())
    val state: StateFlow<ScannerModelState> = _state.asStateFlow()
    private var catalog: ModelCatalogRepository? = null
    private var store: VersionedModelStore? = null
    private var stored = StoredModels(null, null, emptySet(), false)
    private var selectedKey: String? = null
    private var latestWork: WorkInfo? = null
    private var localPhase: ScannerModelPhase? = null
    private var localError: ScannerModelError? = null
    private var catalogPhase = CatalogPhase.IDLE
    private var catalogError: CatalogError? = null

    init {
        scope.launch {
            try {
                val model = scannerJson.decodeFromString<ModelPackage>(readAsset(PACKAGE_ASSET).toString(Charsets.UTF_8)).validate()
                val downloadPolicy = scannerJson.decodeFromString<ModelDownloadPolicy>(readAsset(POLICY_ASSET).toString(Charsets.UTF_8)).validate()
                val catalogPolicy = scannerJson.decodeFromString<CatalogPolicy>(readAsset(CATALOG_POLICY_ASSET).toString(Charsets.UTF_8)).validate()
                val root = File(appContext.noBackupFilesDir, STORAGE_DIRECTORY)
                val transport = HttpsModelTransport(downloadPolicy)
                @Suppress("DEPRECATION")
                val appVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toLong()
                val apkModels = readApkModels(catalogPolicy)
                catalog = ModelCatalogRepository(root, CatalogVerifier(catalogPolicy), readAsset(CATALOG_SEED_ASSET), model, appVersion, transport, apkModels = apkModels)
                store = VersionedModelStore(root, catalog!!, downloadPolicy, transport)
                store!!.initialize()
                actions.withLock {
                    localError = store!!.pointerError
                    refreshStoredFiles()
                    latestWork = workManager.getWorkInfosForUniqueWork(WORK_NAME).get().let { infos ->
                        infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
                    }
                    selectedKey = if (latestWork?.state?.isFinished == false) store!!.pendingKey() else null
                    val preferredApk = catalog!!.releases().firstOrNull {
                        catalog!!.isApkLocal(it.model.directoryName) && catalog!!.compatible(it)
                    }
                    selectedKey = selectedKey ?: stored.active?.model?.directoryName ?: preferredApk?.model?.directoryName ?: model.directoryName
                    catalogError = catalog!!.loadError
                    if (catalogError != null) catalogPhase = CatalogPhase.FAILED
                    else if (catalog!!.checkedAtEpochMillis != null) catalogPhase = CatalogPhase.READY
                    publish()
                }
                ready.complete(true)
                workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { infos ->
                    actions.withLock {
                        latestWork = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
                        if (latestWork?.state?.isFinished != false) refreshStoredFiles()
                        publish()
                    }
                }
            } catch (e: CancellationException) {
                ready.complete(false)
                throw e
            } catch (e: Exception) {
                ready.complete(false)
                _state.value = ScannerModelState(phase = ScannerModelPhase.FAILED, error = errorOf(e, ScannerModelError.SOURCE))
            }
        }
    }

    fun download(wifiOnly: Boolean) = act {
        val source = catalog ?: return@act
        val key = selectedKey ?: return@act
        latestWork = workManager.getWorkInfosForUniqueWork(WORK_NAME).get().firstOrNull { !it.state.isFinished }
        if (latestWork != null || stored.active?.model?.directoryName == key) return@act
        val approval = source.approval(key)
        val digest = store!!.prepare(approval)
        if (key in stored.installedKeys) {
            localPhase = ScannerModelPhase.VERIFYING
            publish()
            store!!.installPending(digest) { _, _ -> }
            refreshStoredFiles()
            latestWork = null
            localPhase = null
            localError = null
            publish()
            return@act
        }
        val request = OneTimeWorkRequestBuilder<ScannerModelWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(when {
                    approval.apkDescriptorSha256 != null -> NetworkType.NOT_REQUIRED
                    wifiOnly -> NetworkType.UNMETERED
                    else -> NetworkType.CONNECTED
                }).build())
            .setInputData(workDataOf(MANIFEST_KEY to digest))
            .addTag(PACKAGE_TAG_PREFIX + key)
            .build()
        localError = null
        localPhase = ScannerModelPhase.QUEUED
        publish()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
        latestWork = workManager.getWorkInfoById(request.id).get()
        localPhase = null
        publish()
    }

    fun selectVersion(key: String) = act {
        if (latestWork?.state?.isFinished == false) return@act
        catalog?.approval(key) ?: return@act
        selectedKey = key
        latestWork = null
        localError = null
        localPhase = null
        refreshStoredFiles()
        publish()
    }

    /** Checking the small catalog does not hold the action lock during HTTPS I/O. */
    fun checkCatalog(force: Boolean) {
        scope.launch {
            if (!ready.await()) return@launch
            val source = catalog ?: return@launch
            var oldPhase = CatalogPhase.IDLE
            val shouldCheck = actions.withLock {
                if (catalogPhase == CatalogPhase.CHECKING) false else {
                    oldPhase = catalogPhase
                    catalogPhase = CatalogPhase.CHECKING
                    publish()
                    true
                }
            }
            if (!shouldCheck) return@launch
            var changed = false
            var failure: CatalogError? = null
            try { changed = source.refresh(force)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { failure = (e as? CatalogException)?.reason ?: CatalogError.NETWORK }
            actions.withLock {
                when {
                    failure != null -> { catalogPhase = CatalogPhase.FAILED; catalogError = failure }
                    changed -> {
                        catalogPhase = CatalogPhase.READY
                        catalogError = null
                        if (latestWork?.state?.isFinished != false) refreshStoredFiles()
                    }
                    else -> catalogPhase = oldPhase
                }
                publish()
            }
        }
    }

    fun cancel() = act {
        localPhase = ScannerModelPhase.CANCELLING
        publish()
        workManager.cancelUniqueWork(WORK_NAME).result.get()
        // Store lock waits for the cancelled reader before allowing another installation.
        refreshStoredFiles()
        latestWork = null
        localPhase = null
        localError = null
        publish()
    }

    fun remove() = act {
        localPhase = ScannerModelPhase.REMOVING
        publish()
        workManager.cancelUniqueWork(WORK_NAME).result.get()
        store?.removeAll()
        latestWork = null
        localPhase = null
        localError = null
        refreshStoredFiles()
        publish()
    }

    fun rollback() = act {
        if (latestWork?.state?.isFinished == false) return@act
        localPhase = ScannerModelPhase.VERIFYING
        publish()
        store?.rollback()
        refreshStoredFiles()
        selectedKey = stored.active?.model?.directoryName ?: selectedKey
        latestWork = null
        localPhase = null
        localError = null
        publish()
    }

    /** Disk only. Neither the app start nor opening the card fetches models or the catalog. */
    fun refresh() = act {
        if (store == null || latestWork?.state?.isFinished == false) return@act
        refreshStoredFiles()
        publish()
    }

    internal suspend fun installPending(digest: String, progress: suspend (ScannerModelPhase, Long) -> Unit) {
        if (!ready.await()) throw ModelInstallException(ScannerModelError.SOURCE)
        store!!.installPending(digest, progress)
    }

    suspend fun recognize(image: android.graphics.Bitmap): OcrDocument {
        if (!ready.await()) throw ModelInstallException(ScannerModelError.SOURCE)
        return store!!.withActiveModel { directory -> OcrEngine().recognize(image, directory) }
    }

    private suspend fun refreshStoredFiles() {
        val source = catalog ?: return
        stored = store?.snapshot(source.releases()) ?: stored
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            if (!ready.await()) return@launch
            actions.withLock {
                try { block()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    localPhase = null
                    localError = errorOf(e)
                    publish()
                }
            }
        }
    }

    private fun publish() {
        val source = catalog ?: return
        val releases = (source.releases() + listOfNotNull(stored.active, stored.previous)).distinctBy { it.model.directoryName }
        val selected = releases.firstOrNull { it.model.directoryName == selectedKey } ?: source.bundledRelease
        val key = selected.model.directoryName
        val work = latestWork?.takeIf { PACKAGE_TAG_PREFIX + key in it.tags }
        val phase = when {
            localPhase != null -> localPhase!!
            localError != null -> ScannerModelPhase.FAILED
            work?.state == WorkInfo.State.RUNNING -> enumValueOrNull<ScannerModelPhase>(work.progress.getString(PHASE_KEY))
                ?: if (source.isApkLocal(key)) ScannerModelPhase.VERIFYING else ScannerModelPhase.DOWNLOADING
            work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED -> ScannerModelPhase.QUEUED
            stored.active?.model?.directoryName == key -> ScannerModelPhase.INSTALLED
            work?.state == WorkInfo.State.FAILED -> ScannerModelPhase.FAILED
            else -> ScannerModelPhase.NOT_INSTALLED
        }
        _state.value = ScannerModelState(
            phase = phase,
            bytesDownloaded = if (phase == ScannerModelPhase.INSTALLED) selected.model.totalBytes else work?.progress?.getLong(BYTES_KEY, 0)?.coerceIn(0, selected.model.totalBytes) ?: 0,
            totalBytes = selected.model.totalBytes,
            sourceRepository = selected.model.source.repository,
            isApkLocal = source.isApkLocal(key),
            version = selected.model.version,
            hasStoredFiles = stored.hasStoredFiles,
            error = localError ?: if (phase == ScannerModelPhase.FAILED) enumValueOrNull<ScannerModelError>(work?.outputData?.getString(ERROR_KEY)) ?: ScannerModelError.UNKNOWN else null,
            installedVersion = stored.active?.model?.version,
            previousVersion = stored.previous?.model?.version,
            versions = releases.sortedByDescending { it.versionCode }.map {
                ScannerVersionOption(it.model.directoryName, it.model.version, it.model.totalBytes, it.channel, it.notes,
                    source.compatible(it), it.model.directoryName in stored.installedKeys, it.model.directoryName == key,
                    it.model.directoryName == stored.active?.model?.directoryName)
            },
            catalogPhase = catalogPhase,
            catalogError = catalogError,
            catalogCheckedAtEpochMillis = source.checkedAtEpochMillis,
            updateAvailable = stored.active?.let { active -> releases.any {
                it.versionCode > active.versionCode &&
                    (it.channel == "stable" || source.isApkLocal(it.model.directoryName)) && source.compatible(it)
            } } == true,
            catalogExpired = source.expired(),
        )
    }

    private fun readApkModels(policy: CatalogPolicy): ApkModelRegistry {
        fun descriptorBytes(path: String) = appContext.assets.open(path).use { it.readBounded(policy.maxCatalogBytes) }
        val index = scannerJson.decodeFromString<ApkModelIndex>(descriptorBytes(APK_MODEL_INDEX_ASSET).toString(Charsets.UTF_8))
        require(index.schemaVersion == 1 && index.descriptors.size <= policy.maxVersions)
        require(index.descriptors.distinct().size == index.descriptors.size)
        require(index.descriptors.all { path ->
            path.startsWith(APK_MODEL_DESCRIPTOR_PREFIX) &&
                safeName(path.removePrefix(APK_MODEL_DESCRIPTOR_PREFIX)) && path.endsWith(".json")
        })
        return ApkModelRegistry.fromApkAssets(index.descriptors.map(::descriptorBytes), policy) { path ->
            appContext.assets.open(path)
        }
    }

    private fun readAsset(path: String) = appContext.assets.open(path).use { it.readBytes() }

    companion object {
        internal const val WORK_NAME = "scanner-model-download"
        internal const val MANIFEST_KEY = "manifest"
        internal const val PHASE_KEY = "phase"
        internal const val BYTES_KEY = "bytes"
        internal const val ERROR_KEY = "error"
        private const val PACKAGE_TAG_PREFIX = "scanner-package:"
        private const val PACKAGE_ASSET = "scanner/model-package.json"
        private const val POLICY_ASSET = "scanner/download-policy.json"
        private const val CATALOG_POLICY_ASSET = "scanner/catalog-policy.json"
        private const val CATALOG_SEED_ASSET = "scanner/catalog-seed.json"
        private const val APK_MODEL_INDEX_ASSET = "scanner/local-models/index.json"
        private const val APK_MODEL_DESCRIPTOR_PREFIX = "scanner/local-models/"
        private const val STORAGE_DIRECTORY = "scanner-models"

        internal fun errorOf(e: Exception, fallback: ScannerModelError = ScannerModelError.UNKNOWN): ScannerModelError = when (e) {
            is ModelInstallException -> e.reason
            is CatalogException -> when (e.reason) {
                CatalogError.NETWORK -> ScannerModelError.NETWORK
                CatalogError.STORAGE -> ScannerModelError.STORAGE
                CatalogError.SIGNATURE, CatalogError.ROLLBACK -> ScannerModelError.INTEGRITY
                else -> ScannerModelError.SOURCE
            }
            is java.io.IOException -> ScannerModelError.STORAGE
            else -> fallback
        }

        private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? = enumValues<T>().firstOrNull { it.name == value }
    }
}
