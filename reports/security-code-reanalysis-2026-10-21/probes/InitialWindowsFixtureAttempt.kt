package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.MainActivity
import com.pdfchemy.app.logic.DocumentActions
import com.pdfchemy.app.logic.audio.AudioOutputPublisher
import com.pdfchemy.app.utils.DocumentIdentity
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Vulnerability-confirming audit probes, not fixed-behavior regression tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FreshReanalysisProbeTest {
    @Test fun startupSweepDeletesAnActivelyLeasedSnapshot_probe() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val leased = ByteArrayInputStream("synthetic leased input".toByteArray()).use {
            DocumentStager.stageStreamWithLease(app, it, "pdf")
        }
        val file = File(leased.document.uri.path!!)
        try {
            assertTrue(file.setLastModified(System.currentTimeMillis() - 3_700_000))
            assertEquals(1, DocumentStager.activeLeases(leased.document))
            assertTrue(file.exists())
            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            MainActivity::class.java.getDeclaredMethod("cleanupOrphanedCacheFiles", android.content.Context::class.java)
                .apply { isAccessible = true }.invoke(activity, app)
            assertEquals("Lease remains registered even after the external sweeper deletes its file", 1, DocumentStager.activeLeases(leased.document))
            assertFalse("Observed defect: startup sweep ignores an active staging lease", file.exists())
        } finally { leased.close(); DocumentStager.release(leased.document) }
    }

    @Test fun readerSnapshotCannotBeSharedThroughConfiguredFileProvider_probe() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val leased = ByteArrayInputStream("synthetic reader snapshot".toByteArray()).use {
            DocumentStager.stageStreamWithLease(app, it, "pdf")
        }
        val exports = File(app.cacheDir, "exports").apply { mkdirs() }
        val control = File.createTempFile("audit_share_", ".pdf", exports)
        try {
            val accepted = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", control)
            assertEquals("content", accepted.scheme)
            val error = runCatching { DocumentActions.share(app, leased.document.uri) }.exceptionOrNull()
            assertTrue("The real share path must fail specifically at root resolution: $error", error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().contains("configured root", ignoreCase = true))
        } finally { control.delete(); leased.close(); DocumentStager.release(leased.document) }
    }

    @Test fun distinctSameNameSameSizeDocumentsCollideInReaderIdentity_probe() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File.createTempFile("audit_identity_", ".dir", app.cacheDir).apply { delete(); mkdirs() }
        val left = File(root, "left").apply { mkdir() }
        val right = File(root, "right").apply { mkdir() }
        val first = File(left, "report.pdf").apply { writeText("AAAA") }
        val second = File(right, "report.pdf").apply { writeText("BBBB") }
        try {
            assertFalse(first.readBytes().contentEquals(second.readBytes()))
            assertEquals(DocumentIdentity.computeStableId(app, Uri.fromFile(first)), DocumentIdentity.computeStableId(app, Uri.fromFile(second)))
            second.appendText("C")
            assertNotEquals(DocumentIdentity.computeStableId(app, Uri.fromFile(first)), DocumentIdentity.computeStableId(app, Uri.fromFile(second)))
        } finally { first.delete(); second.delete(); left.delete(); right.delete(); root.delete() }
    }

    @Test fun cancellationCannotFinishWhilePublicationWriteIsBlocked_probe() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val input = File.createTempFile("audit_audio_", ".wav", app.cacheDir).apply { writeBytes(ByteArray(128)) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanup = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = scope.launch {
            AudioOutputPublisher.copy(input, { object : OutputStream() {
                override fun write(value: Int) = error("Array write expected")
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Probe must release blocked write" }
                }
            } }, { cleanup.incrementAndGet() })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.cancel()
            assertNull("Observed defect: cancellation cannot join or clean up a blocked provider-style write", withTimeoutOrNull(250) { writer.join(); true })
            assertEquals(0, cleanup.get())
        } finally {
            release.countDown()
            withTimeout(5000) { writer.join() }
            scope.cancel(); input.delete()
        }
        assertEquals(1, cleanup.get())
    }
}
