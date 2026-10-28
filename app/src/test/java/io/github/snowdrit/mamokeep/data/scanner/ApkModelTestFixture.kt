package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Tiny data fixtures only. No real model weights or signing secrets. */
internal class ApkModelTestFixture {
    val signed = CatalogTestFixture()
    val release = signed.release(2).let { release ->
        release.copy(
            model = release.model.copy(id = "ocr-apk", version = "2-apk-v5", source = ModelSource("", "")),
            channel = "preview",
        )
    }
    val assets = release.model.files.associate { file ->
        assetPath(file.name) to signed.bytes(2, file.name)
    }.toMutableMap()
    val opened = mutableListOf<String>()
    var failingAsset: String? = null

    fun assetPath(name: String) = "scanner/local-models/${release.model.directoryName}/$name"
    fun descriptor(release: CatalogRelease = this.release) = scannerJson.encodeToString(
        ApkModelDescriptor(1, release.model.directoryName, release),
    ).toByteArray()

    fun registry(descriptors: List<ByteArray> = listOf(descriptor())) = ApkModelRegistry.fromApkAssets(
        descriptors, signed.policy,
    ) { path ->
        opened += path
        if (path == failingAsset) throw IOException("Interrupted APK asset fixture")
        ByteArrayInputStream(assets[path] ?: throw IOException("Missing APK asset fixture: $path"))
    }

    fun repository(root: File, registry: ApkModelRegistry = registry(), appVersion: Long = 1) = ModelCatalogRepository(
        root, signed.verifier, signed.seed, signed.baseline.model, appVersion, signed.network,
        { signed.nowSeconds * 1000 }, registry,
    )

    fun store(root: File, repository: ModelCatalogRepository) = signed.store(root, repository)
}
