package io.github.snowdrit.mamokeep.notifications

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupFileReplacementTest {
    private lateinit var directory: File
    private lateinit var previous: File
    private val name = "mamokeep-2026-10-20.json"

    @Before
    fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "backup-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        previous = File(directory, name).apply { writeText("previous complete backup") }
    }

    @After
    fun tearDown() { directory.deleteRecursively() }

    @Test
    fun failedWriteKeepsThePreviousCompleteCopy() = runBlocking {
        try {
            writeDailyBackup(DocumentFile.fromFile(directory), name) { uri ->
                File(requireNotNull(uri.path)).writeText("incomplete")
                throw IOException("provider failed during flush")
            }
            fail("Write failure must propagate")
        } catch (_: IOException) {
            assertEquals("previous complete backup", previous.readText())
            assertEquals(listOf(name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test
    fun cancelledWriteKeepsThePreviousCompleteCopy() = runBlocking {
        try {
            writeDailyBackup(DocumentFile.fromFile(directory), name) { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals("previous complete backup", previous.readText())
            assertEquals(listOf(name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test
    fun completedWriteReplacesTheDailyCopyOnlyAfterTheNewContentExists() = runBlocking {
        writeDailyBackup(DocumentFile.fromFile(directory), name) { uri ->
            assertEquals("previous complete backup", previous.readText())
            File(requireNotNull(uri.path)).writeText("new complete backup")
        }
        assertEquals("new complete backup", previous.readText())
        assertEquals(listOf(name), directory.listFiles()!!.map { it.name })
    }

    @Test
    fun missingFolderFailsInsteadOfReportingSuccess() = runBlocking {
        try {
            writeDailyBackup(DocumentFile.fromFile(File(directory, "missing")), name) {
                fail("An unavailable folder must not reach the writer")
            }
            fail("Unavailable folder must fail")
        } catch (_: IOException) {
            assertEquals("previous complete backup", previous.readText())
        }
    }
}
