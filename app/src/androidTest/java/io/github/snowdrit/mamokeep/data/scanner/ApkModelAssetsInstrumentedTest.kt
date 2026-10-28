package io.github.snowdrit.mamokeep.data.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.R
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID

/** Installs real APK assets in an isolated directory; the selected user package stays untouched. */
@RunWith(AndroidJUnit4::class)
class ApkModelAssetsInstrumentedTest {
    @Test fun packaged_preview_installs_offline_survives_restart_and_rejects_tampering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun asset(path: String) = context.assets.open(path).use { it.readBytes() }
        val policy = scannerJson.decodeFromString<CatalogPolicy>(asset("scanner/catalog-policy.json").toString(Charsets.UTF_8)).validate()
        val downloadPolicy = scannerJson.decodeFromString<ModelDownloadPolicy>(asset("scanner/download-policy.json").toString(Charsets.UTF_8)).validate()
        val baseline = scannerJson.decodeFromString<ModelPackage>(asset("scanner/model-package.json").toString(Charsets.UTF_8)).validate()
        val seed = asset("scanner/catalog-seed.json")
        val index = JSONObject(asset("scanner/local-models/index.json").toString(Charsets.UTF_8))
        assertEquals(1, index.getInt("schemaVersion"))
        val paths = index.getJSONArray("descriptors")
        assertEquals(1, paths.length())
        val registry = ApkModelRegistry.fromApkAssets(listOf(asset(paths.getString(0))), policy) { context.assets.open(it) }
        val preview = registry.releases().single()
        assertEquals("preview", preview.channel)
        assertEquals("paddle-v5-eslav-onnx-2-apk-v5", preview.model.directoryName)
        assertEquals(ModelSource("", ""), preview.model.source)
        context.resources.getXml(R.xml.locales_config).use { locales ->
            while (locales.eventType != XmlPullParser.END_DOCUMENT) {
                if (locales.eventType == XmlPullParser.START_TAG && locales.name == "locale") {
                    val tag = locales.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    val language = Locale.forLanguageTag(tag).language
                    // ScannerVersionRow resolves notes by language, including zh for zh-Hans.
                    assertFalse(tag, preview.notes[language].isNullOrBlank())
                }
                locales.next()
            }
        }
        val root = File(context.noBackupFilesDir, "scanner-apk-assets-test-${UUID.randomUUID()}")
        var networkRequests = 0
        val transport = ModelTransport { networkRequests++; error("APK installation must not use the network") }
        fun repository() = ModelCatalogRepository(root, CatalogVerifier(policy), seed, baseline, 1, transport, apkModels = registry)
        try {
            val catalog = repository()
            val store = VersionedModelStore(root, catalog, downloadPolicy, transport)
            store.initialize()
            assertNull(store.snapshot(catalog.releases()).active)
            val approval = catalog.approval(preview.model.directoryName)
            assertNotNull(approval.apkDescriptorSha256)
            assertNull(approval.envelope)
            val digest = store.prepare(approval)
            val phases = mutableSetOf<ScannerModelPhase>()
            store.installPending(digest) { phase, _ -> phases += phase }
            assertTrue(phases.isNotEmpty())
            assertEquals(setOf(ScannerModelPhase.VERIFYING), phases)
            assertEquals(preview, store.snapshot(catalog.releases()).active)
            val reopenedCatalog = repository()
            val reopened = VersionedModelStore(root, reopenedCatalog, downloadPolicy, transport)
            reopened.initialize()
            assertEquals(preview, reopened.snapshot(reopenedCatalog.releases()).active)
            val directory = reopened.withActiveModel { it }
            for (spec in preview.model.files) {
                val installed = File(directory, spec.name)
                assertEquals(spec.name, spec.bytes, installed.length())
                assertEquals(spec.name, spec.sha256, sha256(installed.readBytes()))
            }
            RandomAccessFile(File(directory, "rec.onnx"), "rw").use { file ->
                val first = file.readByte()
                file.seek(0)
                file.writeByte(first.toInt() xor 1)
            }
            var inferenceEntered = false
            try {
                reopened.withActiveModel { inferenceEntered = true }
                fail("Modified model must be rejected before inference")
            } catch (expected: ModelInstallException) {
                assertEquals(ScannerModelError.INTEGRITY, expected.reason)
            }
            assertFalse(inferenceEntered)
            assertEquals(0, networkRequests)
        } finally {
            root.deleteRecursively()
        }
    }
}
