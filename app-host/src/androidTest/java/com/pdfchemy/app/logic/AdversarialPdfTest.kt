package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class AdversarialPdfTest {

    @Test
    fun malformedPdfIsRejectedWithoutCrashingHost() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Create a fake malicious PDF that is designed to trigger a failure in the parser or watchdog
        val maliciousPdf = File.createTempFile("malicious", ".pdf", context.cacheDir)
        FileOutputStream(maliciousPdf).use {
            // Write some garbage data to ensure it fails parsing
            it.write("Garbage PDF Data to trigger parse failure".toByteArray())
        }
        val uri = Uri.fromFile(maliciousPdf)

        var didThrowExpectedException = false
        try {
            // This should safely fail and return an exception to the host instead of crashing the host process.
            PdfGateway.analyzePdf(context, uri)
        } catch (e: Exception) {
            // Malformed-input rejection is separate from the real process-death security tests.
            didThrowExpectedException = true
        } finally {
            maliciousPdf.delete()
        }

        assertTrue("Host should survive and catch the IPC exception when jail fails", didThrowExpectedException)
    }
}
