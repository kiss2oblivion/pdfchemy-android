package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import android.content.Context
import android.content.ContentResolver
import android.database.MatrixCursor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.MainActivity
import com.pdfchemy.app.logic.audio.AudioOutputPublisher
import com.pdfchemy.app.utils.DocumentIdentity
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import io.mockk.*
import org.junit.After
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
    @After fun cleanupMocks() { unmockkAll() }
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

    @Test fun distinctSameNameSameSizeDocumentsCollideInReaderIdentity_probe() {
        val app = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val first = Uri.parse("content://identityfixture/doc/first")
        val second = Uri.parse("content://identityfixture/doc/second")
        var secondSize = 4L
        every { app.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf("report.pdf", if (firstArg<Uri>() == second) secondSize else 4L))
            }
        }
        every { resolver.openInputStream(first) } returns ByteArrayInputStream("AAAA".toByteArray())
        every { resolver.openInputStream(second) } returns ByteArrayInputStream("BBBB".toByteArray())
        assertFalse(resolver.openInputStream(first)!!.use { it.readBytes() }.contentEquals(resolver.openInputStream(second)!!.use { it.readBytes() }))
        assertEquals(DocumentIdentity.computeStableId(app, first), DocumentIdentity.computeStableId(app, second))
        secondSize = 5L
        assertNotEquals(DocumentIdentity.computeStableId(app, first), DocumentIdentity.computeStableId(app, second))
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
