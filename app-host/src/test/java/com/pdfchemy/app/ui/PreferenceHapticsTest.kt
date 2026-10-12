package com.pdfchemy.app.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceHapticsTest {
    @Test fun disablingFeedbackSuppressesAllCallsAndEnablingForwardsThem() {
        val calls = mutableListOf<HapticFeedbackType>()
        val delegate = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { calls += hapticFeedbackType }
        }
        val enabled = PreferenceHaptics(delegate, true)
        val disabled = PreferenceHaptics(delegate, false)
        enabled.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        disabled.performHapticFeedback(HapticFeedbackType.LongPress)
        enabled.performHapticFeedback(HapticFeedbackType.LongPress)
        assertEquals(listOf(HapticFeedbackType.TextHandleMove, HapticFeedbackType.LongPress), calls)
    }
}
