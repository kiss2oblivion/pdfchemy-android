package com.pdfchemy.app.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Applies the existing app preference to every Compose feedback caller. */
class PreferenceHaptics(private val delegate: HapticFeedback, private val enabled: Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled) delegate.performHapticFeedback(hapticFeedbackType)
    }
}
