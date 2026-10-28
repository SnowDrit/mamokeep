package io.github.snowdrit.mamokeep.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The provider and file are isolated fixtures; no garage or selected backup folder is used. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29) // ContentResolver.wrap is only needed by this fixture.
class DocumentTextIOTest {
    private val uri = Uri.parse("content://document-text-test/backup.json")
    private lateinit var file: File
    private lateinit var provider: DocumentProvider
    private lateinit var resolver: ContentResolver

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File.createTempFile("document-text-test-", ".json", context.cacheDir)
        provider = DocumentProvider(file)
        resolver = ContentResolver.wrap(provider)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun utf8RoundTripReplacesOldContentOffMainThread() = runBlocking {
        file.writeText("An older backup with trailing content that must be removed.")
        val text = "{\"машина\":\"🚙\"}\n"

        writeFromMain(text)
        assertEquals(text, readFromMain())
        assertEquals(text, file.readText())
        assertEquals(listOf("wt", "r"), provider.openedModes)
        assertFalse(provider.openedOnMain)
    }

    @Test
    fun missingOrFailingInputNeverReturnsContent() = runBlocking {
        for (fault in listOf(Fault.NULL_DESCRIPTOR, Fault.OPEN_FAILURE, Fault.STREAM_FAILURE)) {
            provider.fault = fault
            expectIOException { readFromMain() }
        }
        assertEquals(listOf("r", "r", "r"), provider.openedModes)
        assertFalse(provider.openedOnMain)
    }

    @Test
    fun missingOrFailingOutputNeverCompletesSuccessfully() = runBlocking {
        for (fault in listOf(Fault.NULL_DESCRIPTOR, Fault.OPEN_FAILURE, Fault.STREAM_FAILURE)) {
            provider.fault = fault
            expectIOException { writeFromMain("backup") }
        }
        assertEquals(listOf("wt", "wt", "wt"), provider.openedModes)
        assertFalse(provider.openedOnMain)
    }

    @Test
    fun providerCancellationPropagatesForReadAndWrite() = runBlocking {
        provider.fault = Fault.CANCELLED
        try {
            readFromMain()
            fail("Cancelled read returned content")
        } catch (expected: CancellationException) {
            assertEquals("fixture cancellation", expected.message)
        }
        try {
            writeFromMain("backup")
            fail("Cancelled write reported success")
        } catch (expected: CancellationException) {
            assertEquals("fixture cancellation", expected.message)
        }
        assertEquals(listOf("r", "wt"), provider.openedModes)
        assertFalse(provider.openedOnMain)
    }

    private suspend fun readFromMain(): String = withContext(Dispatchers.Main) {
        resolver.readDocumentText(uri)
    }

    private suspend fun writeFromMain(text: String) = withContext(Dispatchers.Main) {
        resolver.writeDocumentText(uri, text)
    }

    private suspend fun expectIOException(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected a document I/O failure")
        } catch (_: IOException) {
            // The caller must see a failure instead of content or successful completion.
        }
    }

    private enum class Fault { NONE, NULL_DESCRIPTOR, OPEN_FAILURE, STREAM_FAILURE, CANCELLED }

    private class DocumentProvider(private val file: File) : ContentProvider() {
        var fault = Fault.NONE
        val openedModes = mutableListOf<String>()
        var openedOnMain = false

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            openedModes += mode
            openedOnMain = openedOnMain || Looper.myLooper() == Looper.getMainLooper()
            when (fault) {
                Fault.NULL_DESCRIPTOR -> return null
                Fault.OPEN_FAILURE -> throw FileNotFoundException("fixture provider failure")
                Fault.CANCELLED -> throw CancellationException("fixture cancellation")
                else -> Unit
            }
            // A real descriptor with incompatible access fails during reading or flushing.
            val accessMode = if (fault == Fault.STREAM_FAILURE) {
                if (mode == "r") "w" else "r"
            } else mode
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(accessMode))
            return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            error("Fixture only supports opening documents")
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Fixture only supports opening documents")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture only supports opening documents")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Fixture only supports opening documents")
    }
}
