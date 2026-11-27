package com.pdfchemy.app.logic

import android.content.Context
import android.app.ActivityManager
import io.mockk.mockk
import io.mockk.every
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DeviceGuardTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        System.gc()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun subMebibyteSystemMemoryMustNotInventHeadroom() {
        val limitedContext = mockk<Context>()
        val manager = mockk<ActivityManager>()
        every { limitedContext.getSystemService(Context.ACTIVITY_SERVICE) } returns manager
        for (bytes in listOf(1L, 512L * 1024L)) {
            every { manager.getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().availMem = bytes
            }
            assertEquals("Known system headroom of $bytes bytes must round down", 0,
                DeviceGuard.getAvailableMemoryMb(limitedContext))
        }
    }

    @Test
    fun assessTask_smallDocument_returnsSafe() {
        val assessment = DeviceGuard.assessTask(
            context = context,
            pageCount = 5,
            fileSizeBytes = 500 * 1024L, // 500 KB
            isImageHeavy = false
        )
        assertEquals(DeviceGuard.CapacityStatus.SAFE, assessment.status)
        assertTrue(assessment.canProceedAnyway)
    }

    @Test
    fun assessTask_massiveDocument_triggersAlternative() {
        val assessment = DeviceGuard.assessTask(
            context = context,
            pageCount = 350,
            fileSizeBytes = 250 * 1024 * 1024L, // 250 MB
            isImageHeavy = true
        )
        // With 350 pages and 250MB, it should caution or recommend splitting
        assertTrue(
            "Should recommend an alternative for massive 350-page document",
            assessment.recommendedAlternative == DeviceGuard.AlternativeAction.SPLIT_FIRST ||
            assessment.recommendedAlternative == DeviceGuard.AlternativeAction.PAGE_RANGE
        )
    }

    @Test
    fun formatFileSize_formatsCorrectly() {
        assertEquals("0 B", DeviceGuard.formatFileSize(0L))
        assertEquals("500 B", DeviceGuard.formatFileSize(500L))
        assertTrue(DeviceGuard.formatFileSize(1024L * 1024L).contains("MB"))
        assertTrue(DeviceGuard.formatFileSize(50L * 1024L * 1024L).contains("50"))
    }
}
