package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.serialization.Serializable
import java.io.InputStream

/** Loaded only from the installed APK, never from a selected file or private storage. */
@Serializable
data class ApkModelDescriptor(
    val schemaVersion: Int,
    val assetDirectory: String,
    val release: CatalogRelease,
)

/** Immutable APK authority, separate from signed network catalogs and the v1 baseline. */
class ApkModelRegistry private constructor(
    private val entries: Map<String, Entry>,
    private val openAsset: (String) -> InputStream,
) {
    private data class Entry(val descriptor: ApkModelDescriptor, val digest: String)

    fun releases(): List<CatalogRelease> = entries.values.map { it.descriptor.release }
    fun contains(key: String): Boolean = key in entries

    fun approval(key: String): ModelApproval {
        val entry = entries[key] ?: throw CatalogException(CatalogError.INVALID)
        return ModelApproval(key, apkDescriptorSha256 = entry.digest)
    }

    fun resolve(approval: ModelApproval): CatalogRelease = entry(approval).descriptor.release

    internal fun open(approval: ModelApproval, file: ModelFile): InputStream {
        val descriptor = entry(approval).descriptor
        if (file !in descriptor.release.model.files) throw CatalogException(CatalogError.INVALID)
        return openAsset("scanner/local-models/${descriptor.assetDirectory}/${file.name}")
    }

    private fun entry(approval: ModelApproval): Entry {
        if (approval.envelope != null || approval.apkDescriptorSha256 == null) {
            throw CatalogException(CatalogError.INVALID)
        }
        val entry = entries[approval.key] ?: throw CatalogException(CatalogError.INVALID)
        if (entry.digest != approval.apkDescriptorSha256) throw CatalogException(CatalogError.INVALID)
        return entry
    }

    companion object {
        val EMPTY = ApkModelRegistry(emptyMap()) { throw CatalogException(CatalogError.INVALID) }

        /** The caller supplies APK asset bytes and AssetManager.open; no external import. */
        fun fromApkAssets(
            descriptors: List<ByteArray>,
            policy: CatalogPolicy,
            openAsset: (String) -> InputStream,
        ): ApkModelRegistry {
            require(descriptors.size <= policy.maxVersions)
            val entries = descriptors.map { bytes ->
                require(bytes.size <= policy.maxCatalogBytes)
                val descriptor = scannerJson.decodeFromString<ApkModelDescriptor>(bytes.toString(Charsets.UTF_8))
                val release = descriptor.release
                val model = release.model.validate()
                require(descriptor.schemaVersion == 1)
                require(model.source == ModelSource("", ""))
                require(descriptor.assetDirectory == model.directoryName)
                require(model.files.size <= policy.maxFiles && model.totalBytes <= policy.maxPackageBytes)
                require(release.versionCode > 0 && release.channel == "preview")
                require(release.minAppVersionCode > 0)
                require(release.maxAppVersionCode == null || release.maxAppVersionCode >= release.minAppVersionCode)
                require(release.compatible(policy, release.minAppVersionCode))
                require(release.notes.isNotEmpty() && release.notes.size <= 32)
                require(release.notes.all { (language, note) -> safeName(language) && note.length <= 4000 })
                model.directoryName to Entry(descriptor, sha256(bytes))
            }
            require(entries.map { it.first }.distinct().size == entries.size)
            require(entries.map { it.second.descriptor.release.versionCode }.distinct().size == entries.size)
            return ApkModelRegistry(entries.toMap(), openAsset)
        }
    }
}
