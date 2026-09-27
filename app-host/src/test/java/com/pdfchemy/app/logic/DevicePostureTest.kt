package com.pdfchemy.app.logic

import org.junit.Assert.*
import org.junit.Test

class DevicePostureTest {

    @Test
    fun testDefaultPosture_isNormal() {
        val posture = PostureInfo()
        assertEquals(DevicePosture.NORMAL, posture.posture)
        assertFalse(posture.isTabletop)
        assertFalse(posture.isBookSpread)
        assertEquals(0, posture.hingeTop)
        assertEquals(0, posture.hingeBottom)
    }

    @Test
    fun testTabletopFlipPosture() {
        val tabletop = PostureInfo(
            posture = DevicePosture.TABLETOP_FLIP,
            isTabletop = true,
            isBookSpread = false,
            hingeTop = 1200,
            hingeBottom = 1240
        )
        assertEquals(DevicePosture.TABLETOP_FLIP, tabletop.posture)
        assertTrue(tabletop.isTabletop)
        assertFalse(tabletop.isBookSpread)
        assertEquals(1200, tabletop.hingeTop)
        assertEquals(1240, tabletop.hingeBottom)
    }

    @Test
    fun testBookSpreadPosture() {
        val book = PostureInfo(
            posture = DevicePosture.BOOK_SPREAD,
            isTabletop = false,
            isBookSpread = true,
            hingeTop = 0,
            hingeBottom = 0
        )
        assertEquals(DevicePosture.BOOK_SPREAD, book.posture)
        assertFalse(book.isTabletop)
        assertTrue(book.isBookSpread)
    }
}
