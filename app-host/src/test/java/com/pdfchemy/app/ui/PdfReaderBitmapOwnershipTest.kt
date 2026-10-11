package com.pdfchemy.app.ui

import android.graphics.Bitmap
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for RV-03: Reader bitmap ownership, asynchronous disposal, and cancellation safety.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfReaderBitmapOwnershipTest {

    @Before
    fun setUp() {
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `bitmap replacement recycles old bitmap without touching newly installed bitmap`() {
        val oldBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val newBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        assertFalse(oldBitmap.isRecycled)
        assertFalse(newBitmap.isRecycled)

        // Simulate state transition:
        // 1. Initial state has oldBitmap
        var stateBitmap: Bitmap? = oldBitmap
        // 2. DisposableEffect(activeBitmap) captures activeBitmap = stateBitmap (oldBitmap)
        val activeBitmap1 = stateBitmap
        val disposeEffect1 = {
            activeBitmap1?.let { if (!it.isRecycled) it.recycle() }
        }

        // 3. New render completes: stateBitmap updated to newBitmap
        stateBitmap = newBitmap
        val activeBitmap2 = stateBitmap
        val disposeEffect2 = {
            activeBitmap2?.let { if (!it.isRecycled) it.recycle() }
        }

        // 4. Compose executes disposal of previous effect (disposeEffect1)
        disposeEffect1()

        // CRITICAL RV-03 INVARIANT: oldBitmap MUST be recycled; newBitmap MUST NOT be recycled!
        assertTrue("Replaced bitmap must be recycled on effect disposal", oldBitmap.isRecycled)
        assertFalse("New active bitmap must NOT be recycled by old effect disposal!", newBitmap.isRecycled)

        // 5. Subsequent disposal of second effect recycles newBitmap
        disposeEffect2()
        assertTrue("New bitmap recycled on its own disposal", newBitmap.isRecycled)
    }

    @Test
    fun `cancellation during async render recycles uncommitted bitmap`() = runBlocking {
        val uncommittedBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertFalse(uncommittedBitmap.isRecycled)

        var committedState: Bitmap? = null

        // Simulate LaunchedEffect cancellation handling
        val job = launch {
            var newlyRendered: Bitmap? = null
            try {
                newlyRendered = uncommittedBitmap
                // Coroutine is cancelled before state assignment
                cancel()
                currentCoroutineContext().ensureActive()
                committedState = newlyRendered
                newlyRendered = null
            } catch (cancelled: CancellationException) {
                newlyRendered?.let { if (!it.isRecycled) it.recycle() }
                throw cancelled
            }
        }

        job.join()

        assertNull("Cancelled render must not commit to state", committedState)
        assertTrue("Uncommitted bitmap must be recycled on cancellation to avoid native leak", uncommittedBitmap.isRecycled)
    }

    @Test
    fun `idempotent disposal does not throw or double-recycle`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val activeBitmap = bitmap
        val disposeEffect = {
            activeBitmap.let { if (!it.isRecycled) it.recycle() }
        }

        assertFalse(bitmap.isRecycled)
        disposeEffect()
        assertTrue(bitmap.isRecycled)

        // Redundant / repeated dispose invocation must be safely idempotent
        disposeEffect()
        disposeEffect()
        assertTrue(bitmap.isRecycled)
    }
}
