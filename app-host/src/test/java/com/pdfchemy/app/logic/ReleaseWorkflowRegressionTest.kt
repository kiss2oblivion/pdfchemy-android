package com.pdfchemy.app.logic

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.MainActivity
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReleaseWorkflowRegressionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @After fun resetMocks() { unmockkAll() }

    @Test fun startupCleanupPreservesSavedScansAndRemovesOnlyStaleCache() {
        val scan = File(context.filesDir, "scans/retained.pdf").apply { parentFile!!.mkdirs(); writeText("saved scan") }
        val cached = File(context.cacheDir, "stale.pdf").apply { writeText("temporary") }
        val recent = File(context.cacheDir, "recent.pdf").apply { writeText("active") }
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        scan.setLastModified(old); cached.setLastModified(old)
        try {
            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            MainActivity::class.java.getDeclaredMethod("cleanupOrphanedCacheFiles", Context::class.java).apply { isAccessible = true }.invoke(activity, context)
            assertTrue("Persistently saved scan was deleted", scan.exists())
            assertEquals("saved scan", scan.readText())
            assertFalse(cached.exists())
            assertTrue(recent.exists())
        } finally { scan.delete(); cached.delete(); recent.delete() }
    }

    @Test fun sharingOfficeAndImageOutputsUsesTheirActualMimeAndGrantsEveryUri() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((extension, expected) in listOf("docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "png" to "image/png", "txt" to "text/plain")) {
            val uris = listOf(Uri.parse("content://fixture/first.$extension"), Uri.parse("content://fixture/second.$extension"))
            ShareUtil.shareFiles(activity, uris)
            val chooser = shadowOf(activity).nextStartedActivity
            @Suppress("DEPRECATION") val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(expected, share.type)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)
            assertEquals(2, share.clipData!!.itemCount)
            assertEquals(uris.last(), share.clipData!!.getItemAt(1).uri)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
        activity.finish()
    }

    @Test fun mixedOutputsShareWithWildcardInsteadOfPretendingToBePdf() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ShareUtil.shareFiles(activity, listOf(Uri.parse("content://fixture/document.pdf"), Uri.parse("content://fixture/image.png")))
        @Suppress("DEPRECATION") val share = shadowOf(activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("*/*", share.type)
        activity.finish()
    }

    @Test fun oversizedPageRangesAreBoundedBeforeExpansion() {
        val parser = PdfManipulator::class.java.getDeclaredMethod("parsePageRange", String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        assertEquals(setOf(1, 2, 3), parser.invoke(PdfManipulator, "0-2147483647", 3))
        assertEquals(emptySet<Int>(), parser.invoke(PdfManipulator, "2147483646-2147483647", 3))
    }

    @Test fun originalImageOutputUsesDecodedSourceFormatForMimeAndExtension() {
        for ((mime, expected) in listOf("image/png" to ImageOutputFormat.PNG, "image/webp" to ImageOutputFormat.WEBP, "image/jpeg" to ImageOutputFormat.JPEG, "image/heic" to ImageOutputFormat.JPEG)) {
            assertEquals(expected, ImageCompressor.resolveOutputFormat(ImageOutputFormat.ORIGINAL, mime))
        }
        assertEquals(ImageOutputFormat.JPEG, ImageCompressor.resolveOutputFormat(ImageOutputFormat.JPEG, "image/png"))
    }

    @Test fun splitAndImageExportDeleteEarlierOutputsIfProviderCreationFails() = runBlocking<Unit> {
        mockkObject(PdfGateway)
        val source = Uri.parse("content://fixture/input.pdf")
        val directory = mockk<DocumentFile>()
        val first = mockk<DocumentFile>(relaxed = true)
        every { first.uri } returns Uri.parse("content://fixture/first.pdf")
        every { directory.createFile(any(), any()) } returnsMany listOf(first, null)
        coEvery { PdfGateway.executeEngine(any(), "PLAN_SPLIT_BOOKMARKS", any(), any(), any()) } returns PlanSplitBookmarksContract(true, listOf(listOf("1"), listOf("2")))
        assertTrue(runCatching { PdfManipulator.splitByBookmarks(context, source, directory, "source") }.isFailure)
        verify { first.delete() }
        clearMocks(directory, first, answers = false)
        every { directory.createFile(any(), any()) } returnsMany listOf(first, null)
        coEvery { PdfGateway.executeEngine(any(), "PLAN_SPLIT_BLANK", any(), any(), any()) } returns PlanSplitBlankContract(true, listOf(listOf("1"), listOf("2")))
        assertTrue(runCatching { PdfManipulator.splitByBlankPages(context, source, directory, "source") }.isFailure)
        verify { first.delete() }
        clearMocks(directory, first, answers = false)
        every { directory.createFile(any(), any()) } returnsMany listOf(first, null)
        coEvery { PdfGateway.executeEngine(any(), "GET_PAGE_COUNT", any(), any(), any()) } returns PageCountContract(2)
        assertTrue(runCatching { PdfManipulator.convertPdfToImages(context, source, directory, "source") }.isFailure)
        verify { first.delete() }
        coVerify(exactly = 0) { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) }
    }

    @Test fun unreadablePdfCannotReportSuccessfulEmptySplit() = runBlocking<Unit> {
        mockkObject(PdfGateway)
        coEvery { PdfGateway.executeEngine(any(), "GET_PAGE_COUNT", any(), any(), any()) } throws java.io.IOException("provider denied")
        val directory = mockk<DocumentFile>()
        val result = runCatching { PdfManipulator.splitPdf(context, Uri.parse("content://fixture/input.pdf"), directory, "source") }
        assertTrue(result.isFailure)
        verify(exactly = 0) { directory.createFile(any(), any()) }
    }
}
