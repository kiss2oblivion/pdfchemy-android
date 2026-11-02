package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Exercise the actual dual-page wrapper with real bitmaps and controlled renderer outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorBitmapOwnershipRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://editor/ownership.pdf")
    private val fixtures = mutableListOf<Bitmap>()

    @Before fun setUp() { mockkObject(NativeRendererCoordinator) }
    @After fun tearDown() {
        unmockkObject(NativeRendererCoordinator)
        fixtures.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun bitmap() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also(fixtures::add)

    @Test fun secondRenderFailureRecyclesUnclaimedLeftBitmap() = runBlocking<Unit> {
        val left = bitmap()
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns left
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) } throws IOException("Right page failed")

        val failure = runCatching { PdfEditor.renderDualPageBitmaps(context, uri, 0, 1) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Right page failed", failure?.message)
        assertTrue("No consumer received the allocated left page", left.isRecycled)
    }

    @Test fun cancellationDuringSecondRenderRecyclesUnclaimedLeftBitmap() = runBlocking<Unit> {
        val left = bitmap()
        val secondEntered = CompletableDeferred<Unit>()
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns left
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) } coAnswers {
            secondEntered.complete(Unit)
            awaitCancellation()
        }

        val request = async { PdfEditor.renderDualPageBitmaps(context, uri, 0, 1) }
        try { withTimeout(10_000) { secondEntered.await() } }
        finally { request.cancelAndJoin() }
        assertTrue(request.isCancelled)
        assertTrue("Cancellation abandons the completed left render", left.isRecycled)
    }

    @Test fun cancellationBeforeCallerResumptionRecyclesBothBitmaps() = runBlocking<Unit> {
        val left = bitmap()
        val right = bitmap()
        val secondEntered = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns left
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) } coAnswers {
            secondEntered.complete(Unit)
            allowCompletion.await()
            right
        }
        val caller = QueuedDispatcher()
        var delivered = false
        val request = async(caller, start = CoroutineStart.UNDISPATCHED) {
            PdfEditor.renderDualPageBitmaps(context, uri, 0, 1).also { delivered = true }
        }
        try {
            withTimeout(10_000) { secondEntered.await() }
            // async has returned while the producer is blocked: withContext
            // has necessarily suspended. Only now let the producer finish.
            allowCompletion.complete(Unit)
            // The producer has finished, but its result is queued on the caller dispatcher.
            val resumption = requireNotNull(caller.queue.poll(10, TimeUnit.SECONDS)) {
                "No caller resumption queued; completed=${request.isCompleted}, delivered=$delivered, leftRecycled=${left.isRecycled}, rightRecycled=${right.isRecycled}"
            }
            request.cancel()
            resumption.run()
            withTimeout(10_000) { request.join() }
            assertFalse("Prompt cancellation must prevent delivery", delivered)
            assertTrue("Queued left page has no consumer", left.isRecycled)
            assertTrue("Queued right page has no consumer", right.isRecycled)
        } finally {
            allowCompletion.complete(Unit)
            request.cancel()
            while (!request.isCompleted) {
                requireNotNull(caller.queue.poll(10, TimeUnit.SECONDS)) { "Cancelled caller did not resume" }.run()
            }
        }
    }

    @Test fun queuedCancellationRemainsCorrectAcross200Requests() {
        repeat(200) { iteration ->
            try { cancellationBeforeCallerResumptionRecyclesBothBitmaps() }
            catch (error: Throwable) { throw AssertionError("Repeated request $iteration", error) }
        }
    }

    @Test fun successfulRenderTransfersBothLiveBitmapsToCaller() = runBlocking<Unit> {
        val left = bitmap()
        val right = bitmap()
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns left
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) } returns right

        val result = PdfEditor.renderDualPageBitmaps(context, uri, 0, 1)
        assertSame(left, result.leftPage)
        assertSame(right, result.rightPage)
        assertFalse(left.isRecycled)
        assertFalse(right.isRecycled)
    }

    @Test fun negativeRightIndexSkipsSecondRenderAndTransfersLeftBitmap() = runBlocking<Unit> {
        val left = bitmap()
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns left

        val result = PdfEditor.renderDualPageBitmaps(context, uri, 0, -1)
        assertSame(left, result.leftPage)
        assertNull(result.rightPage)
        assertFalse(left.isRecycled)
        coVerify(exactly = 0) { NativeRendererCoordinator.renderUriToBitmap(context, uri, -1, 720) }
    }

    @Test fun firstRenderFailurePropagatesWithoutRenderingRightPage() = runBlocking<Unit> {
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } throws IOException("Left page failed")

        val failure = runCatching { PdfEditor.renderDualPageBitmaps(context, uri, 0, 1) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Left page failed", failure?.message)
        coVerify(exactly = 0) { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) }
    }

    @Test fun nullLeftRenderStillTransfersRightBitmap() = runBlocking<Unit> {
        val right = bitmap()
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns null
        coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) } returns right

        val result = PdfEditor.renderDualPageBitmaps(context, uri, 0, 1)
        assertNull(result.leftPage)
        assertSame(right, result.rightPage)
        assertFalse(right.isRecycled)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    }
}
