package com.pdfchemy.app.logic

import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Cover successful ownership transfer when the producer wins the suspension race. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorBitmapImmediateDeliveryTest {
    @Test fun producerCompletingBeforeCallerSuspendsTransfersBothLiveBitmaps() {
        val actualIo = Dispatchers.IO
        val completedProducer = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val finished = CountDownLatch(1)
                actualIo.dispatch(context) { try { block.run() } finally { finished.countDown() } }
                check(finished.await(10, TimeUnit.SECONDS)) { "Producer did not finish" }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://editor/immediate.pdf")
        val left = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val right = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        mockkObject(NativeRendererCoordinator)
        mockkStatic(Dispatchers::class)
        try {
            every { Dispatchers.IO } returns completedProducer
            coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 0, 720) } returns left
            coEvery { NativeRendererCoordinator.renderUriToBitmap(context, uri, 1, 720) } returns right
            val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
            val caller = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
            }
            runBlocking {
                val request = async(caller, start = CoroutineStart.UNDISPATCHED) {
                    PdfEditor.renderDualPageBitmaps(context, uri, 0, 1)
                }
                assertTrue("Immediate delivery requires no queued continuation", request.isCompleted)
                assertTrue(queue.isEmpty())
                val result = request.await()
                assertSame(left, result.leftPage)
                assertSame(right, result.rightPage)
                assertFalse(left.isRecycled)
                assertFalse(right.isRecycled)
            }
        } finally {
            unmockkStatic(Dispatchers::class)
            unmockkObject(NativeRendererCoordinator)
            left.recycle(); right.recycle()
        }
    }
}
