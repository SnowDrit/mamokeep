package io.github.snowdrit.mamokeep.data.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.snowdrit.mamokeep.MamoKeepApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Uses only an isolated test directory and fixture bytes, never a user's garage or downloaded package. */
@RunWith(AndroidJUnit4::class)
class ModelStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun install_verify_tamper_remove_in_android_private_storage() = runBlocking {
        val root = File(context.noBackupFilesDir, "scanner-test-${UUID.randomUUID()}")
        val bytes = "instrumentation fixture, not OCR weights".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val packageSpec = ModelPackage(1, "test", "1", ModelSource("fixture/repo", "v1"), listOf(ModelFile("fixture.bin", bytes.size.toLong(), hash)))
        val policy = ModelDownloadPolicy(1000, 1000, 1, listOf("github.com"), 4, 1, 0)
        var requests = 0
        val transport = ModelTransport {
            requests++
            object : ModelResponse {
                override val body = ByteArrayInputStream(bytes)
                override val contentLength = bytes.size.toLong()
                override fun close() = body.close()
            }
        }
        try {
            ModelInstaller(root, packageSpec, policy, transport).install { _, _ -> }
            val reopened = ModelInstaller(root, packageSpec, policy, transport)
            assertTrue(reopened.isInstalled())
            assertEquals(1, requests)
            File(root, "test-1/fixture.bin").writeBytes(bytes.copyOf().apply { this[0] = 0 })
            assertFalse(reopened.isInstalled())
            reopened.remove()
            assertFalse(reopened.isInstalled())
            assertTrue(root.listFiles().isNullOrEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun unpublished_catalog_is_exposed_without_automatic_network_work() = runBlocking {
        val catalog = context.assets.open("scanner/model-package.json").bufferedReader().use {
            Json.decodeFromString<ModelPackage>(it.readText()).validate()
        }
        // This assertion follows the checked-in distribution configuration, not a fake download URL.
        org.junit.Assume.assumeFalse(catalog.source.isPublished)
        val manager = (context.applicationContext as MamoKeepApp).container.scannerModels
        withTimeout(10000) { manager.state.first { it.phase != ScannerModelPhase.CHECKING } }
        assertEquals(ScannerModelPhase.UNAVAILABLE, manager.state.value.phase)
        // Reading the catalog and checking local files does not enqueue a download.
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("scanner-model-download").get().isEmpty())
    }
}
