package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

class ModelInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val policy = ModelDownloadPolicy(1000, 1000, 3, listOf("github.com", "release-assets.githubusercontent.com"), 4, 1, 0)
    private val first = "first-model".toByteArray()
    private val second = "second-model".toByteArray()
    private val files = listOf(spec("det.onnx", first), spec("rec.onnx", second))
    private fun model(source: ModelSource = ModelSource("owner/repo", "ocr-v1")) =
        ModelPackage(1, "ocr", "1", source, files)

    private class Transport(private val contents: MutableMap<String, ByteArray>) : ModelTransport {
        val calls = mutableListOf<String>()
        var failure: String? = null
        var length: Long? = null
        var closed = 0
        override fun open(uri: URI): ModelResponse {
            val name = uri.path.substringAfterLast('/')
            calls += name
            if (name == failure) throw IOException("disconnected")
            val bytes = contents.getValue(name)
            return object : ModelResponse {
                override val body = ByteArrayInputStream(bytes)
                override val contentLength = length ?: bytes.size.toLong()
                override fun close() { body.close(); closed++ }
            }
        }
    }

    private fun transport() = Transport(mutableMapOf("det.onnx" to first, "rec.onnx" to second))

    @Test fun `complete package is installed offline and removable`() = runBlocking {
        val root = temporary.newFolder()
        val network = transport()
        val installer = ModelInstaller(root, model(), policy, network)
        val phases = mutableListOf<ScannerModelPhase>()
        installer.install { phase, _ -> phases += phase }
        assertTrue(installer.isInstalled())
        assertEquals(2, network.closed)
        assertTrue(phases.contains(ScannerModelPhase.VERIFYING))
        installer.install { _, _ -> fail("Already installed must not download") }
        assertEquals(2, network.calls.size)
        installer.remove()
        assertFalse(installer.isInstalled())
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun `same size tampered response fails checksum and never becomes installed`() = runBlocking {
        val network = Transport(mutableMapOf("det.onnx" to first.copyOf().apply { this[0] = 0 }))
        val installer = ModelInstaller(temporary.newFolder(), model(), policy, network)
        expectFailure(ScannerModelError.INTEGRITY) { installer.install { _, _ -> } }
        assertFalse(installer.isInstalled())
        assertEquals(listOf("det.onnx"), network.calls)
    }

    @Test fun `truncated chunked response is rejected`() = runBlocking {
        val network = Transport(mutableMapOf("det.onnx" to first.copyOf(first.size - 1))).apply { length = -1 }
        val installer = ModelInstaller(temporary.newFolder(), model(), policy, network)
        expectFailure(ScannerModelError.INTEGRITY) { installer.install { _, _ -> } }
        assertFalse(installer.isInstalled())
    }

    @Test fun `oversized chunked response stops at expected bound`() = runBlocking {
        val network = Transport(mutableMapOf("det.onnx" to first + first)).apply { length = -1 }
        val root = temporary.newFolder()
        val installer = ModelInstaller(root, model(), policy, network)
        expectFailure(ScannerModelError.INTEGRITY) { installer.install { _, _ -> } }
        assertFalse(installer.isInstalled())
        assertFalse(root.walk().any { it.name.endsWith(".part") })
    }

    @Test fun `wrong content length fails without installing`() = runBlocking {
        val network = transport().apply { length = first.size + 1L }
        val installer = ModelInstaller(temporary.newFolder(), model(), policy, network)
        expectFailure(ScannerModelError.INTEGRITY) { installer.install { _, _ -> } }
        assertFalse(installer.isInstalled())
    }

    @Test fun `network retry reuses only completely verified files`() = runBlocking {
        val root = temporary.newFolder()
        val network = transport().apply { failure = "rec.onnx" }
        val installer = ModelInstaller(root, model(), policy, network)
        expectFailure(ScannerModelError.NETWORK) { installer.install { _, _ -> } }
        assertFalse(installer.isInstalled())
        network.failure = null
        assertTrue(installer.hasStoredFiles())
        // New instance represents a new process resuming the durable staging directory.
        val resumed = ModelInstaller(root, model(), policy, network)
        resumed.install { _, _ -> }
        assertTrue(resumed.isInstalled())
        assertEquals(listOf("det.onnx", "rec.onnx", "rec.onnx"), network.calls)
        resumed.remove()
        assertFalse(resumed.hasStoredFiles())
    }

    @Test fun `cancellation during streaming leaves no install or partial file`() = runBlocking {
        val root = temporary.newFolder()
        val installer = ModelInstaller(root, model(), policy, transport())
        try {
            installer.install { phase, bytes ->
                if (phase == ScannerModelPhase.DOWNLOADING && bytes > 0) throw CancellationException("cancel")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(installer.isInstalled())
        assertFalse(root.walk().any { it.name.endsWith(".part") })
    }

    @Test fun `download cannot start without published source`() = runBlocking {
        val network = transport()
        val installer = ModelInstaller(temporary.newFolder(), model(ModelSource("", "")), policy, network)
        expectFailure(ScannerModelError.SOURCE) { installer.install { _, _ -> } }
        assertTrue(network.calls.isEmpty())
    }

    @Test fun `stored package is rechecked and a modified file is not trusted`() = runBlocking {
        val root = temporary.newFolder()
        val network = transport()
        val installer = ModelInstaller(root, model(), policy, network)
        installer.install { _, _ -> }
        File(root, "ocr-1/det.onnx").writeBytes(first.copyOf().apply { this[0] = 0 })
        assertFalse(installer.isInstalled())
        installer.install { _, _ -> }
        assertTrue(installer.isInstalled())
    }

    @Test fun `extreme storage reserve does not overflow and make request`() = runBlocking {
        val network = transport()
        val installer = ModelInstaller(temporary.newFolder(), model(), policy.copy(storageReserveBytes = Long.MAX_VALUE), network)
        expectFailure(ScannerModelError.STORAGE) { installer.install { _, _ -> } }
        assertTrue(network.calls.isEmpty())
    }

    @Test fun `manifest rejects unsafe addresses paths checksums duplicates and overflow`() {
        val invalid = listOf(
            model().copy(schemaVersion = 2),
            model().copy(id = "../ocr"),
            model().copy(version = ".."),
            model(ModelSource("owner/repo", "")),
            model(ModelSource("https://github.com/owner/repo", "v1")),
            model(ModelSource("owner/repo", "latest")),
            model(ModelSource("owner/repo", "../v1")),
            model(ModelSource("owner/repo?token=x", "v1")),
            model().copy(files = listOf(files[0].copy(name = "../det.onnx"))),
            model().copy(files = listOf(files[0].copy(sha256 = "f"))),
            model().copy(files = listOf(files[0], files[0])),
            model().copy(files = listOf(files[0].copy(bytes = 0))),
            model().copy(files = files.map { it.copy(bytes = Long.MAX_VALUE) }),
        )
        invalid.forEach { candidate ->
            assertNotNull(runCatching { candidate.validate() }.exceptionOrNull())
        }
        assertEquals(
            "https://github.com/owner/repo/releases/download/ocr-v1/det.onnx",
            model().validate().url(files[0]).toString(),
        )
    }

    @Test fun `redirects reject http credentials ports suffix lookalikes and third parties`() {
        val invalid = listOf(
            "http://github.com/file", "https://github.com.evil.test/file", "https://evil.test/file",
            "https://user:password@github.com/file", "https://github.com:8443/file",
            "https://github.com/file#fragment", "file:///tmp/model.onnx",
        )
        invalid.forEach { address ->
            val error = runCatching { HttpsModelTransport.validateTarget(URI(address), policy) }.exceptionOrNull()
            assertTrue(address, error is ModelInstallException)
        }
        HttpsModelTransport.validateTarget(URI("https://release-assets.githubusercontent.com/file?sig=abc"), policy)
    }

    private suspend fun expectFailure(reason: ScannerModelError, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: ModelInstallException) { assertEquals(reason, e.reason) }
    }

    private fun spec(name: String, data: ByteArray) = ModelFile(
        name, data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )
}
