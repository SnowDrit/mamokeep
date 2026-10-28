package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
private data class CatalogCache(
    val envelope: String,
    val checkedAtEpochMillis: Long,
    val knownPackages: Map<String, String>,
    val knownDirectories: Map<String, String>,
)

@Serializable
data class ModelApproval(
    val key: String,
    val envelope: String? = null,
    val apkDescriptorSha256: String? = null,
)

/** A signed catalog is the only network-controlled input allowed to authorize new model bytes. */
class ModelCatalogRepository(
    private val root: File,
    val verifier: CatalogVerifier,
    seed: ByteArray,
    bundled: ModelPackage,
    private val appVersion: Long,
    private val transport: ModelTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val apkModels: ApkModelRegistry = ApkModelRegistry.EMPTY,
) {
    val policy get() = verifier.policy
    private val mutex = Mutex()
    private val cacheFile = File(root, "catalog-cache.json")
    private val seedCatalog = verifier.verify(seed, requireFresh = false)
    val bundledRelease = seedCatalog.catalog.releases.single { it.model == bundled }
    @Volatile private var knownPackages = mutableMapOf<String, String>()
    @Volatile private var knownDirectories = mutableMapOf<String, String>()
    private var cacheProblem = false
    private var lastAttemptMillis: Long? = null
    @Volatile var current: VerifiedCatalog = seedCatalog
        private set
    @Volatile var checkedAtEpochMillis: Long? = null
        private set
    var loadError: CatalogError? = null
        private set

    init {
        require(seedCatalog.catalog.sequence >= policy.minimumSequence)
        remember(seedCatalog, knownPackages, knownDirectories)
        rememberApk(knownPackages, knownDirectories)
        if (cacheFile.exists()) {
            try {
                // History is bounded by the app's private storage, but never read an unbounded corrupt file.
                val cache = scannerJson.decodeFromString<CatalogCache>(cacheFile.inputStream().use {
                    it.readBounded(MAX_CACHE_BYTES).toString(Charsets.UTF_8)
                })
                val restored = verifier.verify(cache.envelope.toByteArray(), requireFresh = false)
                knownPackages.putAll(cache.knownPackages)
                knownDirectories.putAll(cache.knownDirectories)
                if (restored.catalog.sequence >= seedCatalog.catalog.sequence) acceptOrder(restored)
                remember(seedCatalog, knownPackages, knownDirectories)
                rememberApk(knownPackages, knownDirectories)
                remember(restored, knownPackages, knownDirectories)
                current = if (restored.catalog.sequence >= seedCatalog.catalog.sequence) restored else seedCatalog
                checkedAtEpochMillis = cache.checkedAtEpochMillis
            } catch (e: Exception) {
                // Do not overwrite a lost high-water mark with a lower network catalog.
                cacheProblem = true
                loadError = CatalogError.STORAGE
            }
        }
    }

    fun releases(): List<CatalogRelease> =
        (current.catalog.releases + bundledRelease + apkModels.releases()).distinctBy { it.model.directoryName }.sortedByDescending { it.versionCode }

    fun compatible(release: CatalogRelease) = release.compatible(policy, appVersion)
    fun expired() = verifier.expired(current.catalog)

    suspend fun refresh(force: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = nowMillis()
            val previousAttempt = lastAttemptMillis ?: checkedAtEpochMillis
            if (!force && previousAttempt != null && now >= previousAttempt && now - previousAttempt < policy.checkIntervalMillis) return@withLock false
            lastAttemptMillis = now
            if (cacheProblem) throw CatalogException(CatalogError.STORAGE)
            val bytes = try {
                transport.open(policy.url()).use {
                    if (it.contentLength > policy.maxCatalogBytes) throw CatalogException(CatalogError.INVALID)
                    it.body.readBounded(policy.maxCatalogBytes)
                }
            } catch (e: CatalogException) { throw e
            } catch (e: ModelInstallException) {
                throw CatalogException(if (e.reason == ScannerModelError.SOURCE) CatalogError.UNAVAILABLE else CatalogError.NETWORK, e)
            } catch (e: java.io.IOException) { throw CatalogException(CatalogError.NETWORK, e) }
            val verified = verifier.verify(bytes)
            acceptOrder(verified)
            val nextPackages = knownPackages.toMutableMap()
            val nextDirectories = knownDirectories.toMutableMap()
            remember(verified, nextPackages, nextDirectories)
            val next = CatalogCache(verified.envelope, now, nextPackages, nextDirectories)
            try {
                val encoded = scannerJson.encodeToString(next).toByteArray()
                if (encoded.size > MAX_CACHE_BYTES) throw java.io.IOException("Catalog history exceeds storage limit")
                atomicWrite(cacheFile, encoded)
            } catch (e: java.io.IOException) { throw CatalogException(CatalogError.STORAGE, e) }
            current = verified
            knownPackages = nextPackages
            knownDirectories = nextDirectories
            checkedAtEpochMillis = now
            loadError = null
            true
        }
    }

    fun isApkLocal(key: String): Boolean = apkModels.contains(key)

    /** Both proofs absent denotes only v1; APK packages require their immutable descriptor SHA. */
    fun approval(key: String): ModelApproval {
        if (apkModels.contains(key)) return apkModels.approval(key).also { resolve(it) }
        val snapshot = current
        val release = (snapshot.catalog.releases + bundledRelease).firstOrNull { it.model.directoryName == key }
            ?: throw CatalogException(CatalogError.INVALID)
        if (!compatible(release)) throw CatalogException(CatalogError.INVALID)
        return ModelApproval(key, if (release == bundledRelease) null else snapshot.envelope)
    }

    fun resolve(approval: ModelApproval, requireFresh: Boolean = false): CatalogRelease {
        val release = if (approval.apkDescriptorSha256 != null) {
            apkModels.resolve(approval).also { verifyKnownModel(it.model) }
        } else if (approval.envelope == null) {
            require(approval.key == bundledRelease.model.directoryName)
            bundledRelease
        } else {
            val verified = verifier.verify(approval.envelope.toByteArray(), requireFresh)
            val found = verified.catalog.releases.singleOrNull { it.model.directoryName == approval.key }
                ?: throw CatalogException(CatalogError.INVALID)
            verifyKnownModel(found.model)
            found
        }
        if (!compatible(release)) throw CatalogException(CatalogError.INVALID)
        return release
    }

    internal fun openApkAsset(approval: ModelApproval, file: ModelFile): java.io.InputStream {
        resolve(approval) // Recheck the exact approval and app/format compatibility before opening bytes.
        return apkModels.open(approval, file)
    }

    private fun verifyKnownModel(model: ModelPackage) {
        val known = knownPackages[identity(model)]
        if (known != null && known != model.digest()) throw CatalogException(CatalogError.ROLLBACK)
        val directoryOwner = knownDirectories[model.directoryName]
        if (directoryOwner != null && directoryOwner != identity(model)) throw CatalogException(CatalogError.ROLLBACK)
    }

    private fun acceptOrder(candidate: VerifiedCatalog) {
        if (candidate.catalog.sequence < current.catalog.sequence ||
            (candidate.catalog.sequence == current.catalog.sequence && candidate.payloadDigest != current.payloadDigest)
        ) throw CatalogException(CatalogError.ROLLBACK)
    }

    private fun remember(catalog: VerifiedCatalog, packages: MutableMap<String, String>, directories: MutableMap<String, String>) {
        catalog.catalog.releases.forEach { entry ->
            rememberModel(entry.model, packages, directories)
        }
    }

    private fun rememberApk(packages: MutableMap<String, String>, directories: MutableMap<String, String>) {
        apkModels.releases().forEach { rememberModel(it.model, packages, directories) }
    }

    private fun rememberModel(model: ModelPackage, packages: MutableMap<String, String>, directories: MutableMap<String, String>) {
        val id = identity(model)
        val digest = model.digest()
        if (packages[id]?.let { it != digest } == true ||
            directories[model.directoryName]?.let { it != id } == true
        ) throw CatalogException(CatalogError.ROLLBACK)
        packages[id] = digest
        directories[model.directoryName] = id
    }

    private fun identity(model: ModelPackage) = "${model.id}/${model.version}"
    companion object { private const val MAX_CACHE_BYTES = 4 * 1024 * 1024 }
}
