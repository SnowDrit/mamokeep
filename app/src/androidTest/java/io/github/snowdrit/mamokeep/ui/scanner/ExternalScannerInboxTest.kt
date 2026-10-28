package io.github.snowdrit.mamokeep.ui.scanner

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalScannerInboxTest {
    @Test
    fun retainedPendingInputIsDispatchedAndAcknowledgedExactlyOnce() = onMain {
        val saved = SavedStateHandle()
        val inbox = ExternalScannerInbox(saved)
        inbox.receive(share("first receipt"))
        val input = inbox.input!!
        inbox.dispatch(input.requestId)
        // The same ViewModel survives Activity rotation and keeps the original ID.
        assertSame(input, inbox.input)
        assertTrue(inbox.dispatched)
        assertEquals(true, saved.get<Boolean>("pending"))
        inbox.consume(input.requestId)
        inbox.consume(input.requestId)
        assertNull(inbox.input)
        assertFalse(inbox.dispatched)
        assertEquals(false, saved.get<Boolean>("pending"))
    }

    @Test
    fun aSecondDeliveryCannotReplaceAnUnconsumedFirstDelivery() = onMain {
        val inbox = ExternalScannerInbox(SavedStateHandle())
        inbox.receive(share("first receipt"))
        val first = inbox.input
        inbox.receive(share("second receipt"))
        assertSame(first, inbox.input)
        assertEquals(ScannerImportError.BUSY, inbox.error)
        inbox.dismissError()
        assertSame(first, inbox.input)
    }

    @Test
    fun aStaleAcknowledgementCannotConsumeANewExplicitShare() = onMain {
        val inbox = ExternalScannerInbox(SavedStateHandle())
        inbox.receive(share("receipt"))
        val firstId = inbox.input!!.requestId
        inbox.consume(firstId)
        inbox.receive(share("receipt"))
        val second = inbox.input
        assertNotEquals(firstId, second!!.requestId)
        inbox.consume(firstId)
        assertSame(second, inbox.input)
    }

    @Test
    fun processRestorationReportsInterruptionWithoutReplayingThePayload() = onMain {
        val saved = SavedStateHandle()
        val original = ExternalScannerInbox(saved)
        original.receive(share("private receipt text"))
        original.dispatch(original.input!!.requestId)
        val restored = ExternalScannerInbox(SavedStateHandle(mapOf("pending" to saved.get<Boolean>("pending"))))
        assertNull(restored.input)
        assertFalse(restored.dispatched)
        assertEquals(ScannerImportError.INTERRUPTED, restored.error)
        assertEquals(setOf("pending", "error"), saved.keys())
    }

    @Test
    fun rejectingForAnOpenEditorDropsTheNewInputAndKeepsAVisibleReason() = onMain {
        val saved = SavedStateHandle()
        val inbox = ExternalScannerInbox(saved)
        inbox.receive(share("receipt"))
        inbox.reject(inbox.input!!.requestId)
        assertNull(inbox.input)
        assertEquals(false, saved.get<Boolean>("pending"))
        val restored = ExternalScannerInbox(SavedStateHandle(mapOf("error" to saved.get<String>("error"))))
        assertNull(restored.input)
        assertEquals(ScannerImportError.BUSY, restored.error)
    }

    private fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
}
