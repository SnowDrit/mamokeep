package io.github.snowdrit.mamokeep.ui.scanner

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inspects the installed manifest without opening an Activity or touching the garage. */
@RunWith(AndroidJUnit4::class)
class ExternalScannerManifestTest {
    @Test
    fun onlySupportedSingleLocalInputsAreAdvertised() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        fun handles(intent: Intent): Boolean = manager.queryIntentActivities(intent.setPackage(context.packageName), 0)
            .any { it.activityInfo.name == MainActivity::class.java.name }

        for (mime in listOf("image/jpeg", "application/pdf", "text/plain")) {
            assertTrue(handles(Intent(Intent.ACTION_SEND).setType(mime)))
            assertTrue(handles(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://receipts/one"), mime)))
        }
        assertFalse(handles(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")))
        assertFalse(handles(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/receipt.pdf"), "application/pdf")))
        assertFalse(handles(Intent(Intent.ACTION_SEND).setType("application/zip")))
        assertEquals(
            ActivityInfo.LAUNCH_SINGLE_TOP,
            manager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).launchMode,
        )
    }
}
