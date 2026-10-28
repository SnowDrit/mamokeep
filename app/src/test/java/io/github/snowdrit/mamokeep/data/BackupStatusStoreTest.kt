package io.github.snowdrit.mamokeep.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupStatusStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: BackupStatusStore
    private var now = 1_000L
    private val folder = "content://documents/tree/backups"

    @Before
    fun setUp() {
        file = File(temporary.root, "backup_status.preferences_pb")
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = BackupStatusStore(preferences) { now }
    }

    private suspend fun closeStore() {
        scope.cancel()
        scope.coroutineContext[Job]!!.join()
    }

    @After
    fun tearDown() = runBlocking { closeStore() }

    @Test
    fun folderWithNoCompletedRunHasNoInventedSuccess() = runBlocking {
        assertEquals(BackupStatus(), store.observe(folder).first())
    }

    @Test
    fun successIsRecordedOnlyAfterTheWriteCompletes() = runBlocking {
        store.trackAttempt(folder) {
            assertNull(store.observe(folder).first().lastSuccessAt)
            now = 2_000L
        }
        assertEquals(BackupStatus(lastSuccessAt = 2_000L), store.observe(folder).first())
    }

    @Test
    fun writerFailureIsVisibleAndKeepsThePreviousSuccess() = runBlocking {
        store.trackAttempt(folder) {}
        now = 2_000L
        val failure = IOException("provider failed during flush")
        try {
            store.trackAttempt(folder) { throw failure }
            fail("The caller must receive the write failure")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertEquals(BackupStatus(1_000L, lastAttemptFailed = true), store.observe(folder).first())
    }

    @Test
    fun revokedFolderAccessIsVisibleWithoutClaimingAFirstCopy() = runBlocking {
        val failure = SecurityException("persisted grant was revoked")
        try {
            store.trackAttempt(folder) { throw failure }
            fail("The caller must receive the permission failure")
        } catch (error: SecurityException) {
            assertSame(failure, error)
        }
        assertEquals(BackupStatus(lastAttemptFailed = true), store.observe(folder).first())
    }

    @Test
    fun aDifferentFolderNeverInheritsAnOldSuccess() = runBlocking {
        store.trackAttempt(folder) {}
        val other = "content://documents/tree/other"
        assertEquals(BackupStatus(), store.observe(other).first())
        try {
            store.trackAttempt(other) { throw IOException("unavailable folder") }
            fail("The new folder failed")
        } catch (_: IOException) {
            assertEquals(BackupStatus(lastAttemptFailed = true), store.observe(other).first())
        }
        assertEquals(BackupStatus(), store.observe(folder).first())
    }

    @Test
    fun cancellationIsNotMisreportedAsAWriteFailureOrSuccess() = runBlocking {
        store.trackAttempt(folder) {}
        now = 2_000L
        val cancelled = CancellationException("weekly copies disabled")
        try {
            store.trackAttempt(folder) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertEquals(BackupStatus(1_000L), store.observe(folder).first())
    }

    @Test
    fun aSuccessfulRetryClearsTheErrorAndUpdatesTheTimestamp() = runBlocking {
        try {
            store.trackAttempt(folder) { throw IOException("offline provider") }
            fail("The first attempt failed")
        } catch (_: IOException) {
            assertTrue(store.observe(folder).first().lastAttemptFailed)
        }
        now = 2_000L
        store.trackAttempt(folder) {}
        val result = store.observe(folder).first()
        assertFalse(result.lastAttemptFailed)
        assertEquals(2_000L, result.lastSuccessAt)
    }

    @Test
    fun failureAndLastSuccessSurviveReopeningTheStore() = runBlocking {
        store.trackAttempt(folder) {}
        try {
            store.trackAttempt(folder) { throw IOException("removed folder") }
            fail("The second attempt failed")
        } catch (_: IOException) {
            // Reopen the real file to test persistence rather than the in-memory flow.
        }
        closeStore()
        openStore()
        assertEquals(BackupStatus(1_000L, lastAttemptFailed = true), store.observe(folder).first())
    }
}
