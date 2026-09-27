package com.pdfchemy.app.logic

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.distinctUntilChanged

enum class DevicePosture {
    NORMAL,        // Standard flat phone or unfolded tablet
    BOOK_SPREAD,   // Fold model unfolded or partially folded vertically like a book
    TABLETOP_FLIP  // Flip model resting on desk at ~90 degrees (horizontal hinge, half-opened)
}

data class PostureInfo(
    val posture: DevicePosture = DevicePosture.NORMAL,
    val isTabletop: Boolean = false,
    val isBookSpread: Boolean = false,
    val hingeTop: Int = 0,
    val hingeBottom: Int = 0
)

/**
 * Collects real-time device folding posture using Android Jetpack WindowManager.
 * Detects Flip clamshell models in Tabletop / Flex mode (horizontal hinge half-opened)
 * and Fold models in Book mode (vertical hinge).
 */
@Composable
fun rememberDevicePosture(): PostureInfo {
    val context = LocalContext.current
    val activity = context as? Activity ?: return PostureInfo()

    var postureInfo by remember { mutableStateOf(PostureInfo()) }

    LaunchedEffect(activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val foldingFeature = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                if (foldingFeature != null && foldingFeature.state == FoldingFeature.State.HALF_OPENED) {
                    if (foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL) {
                        // Flip model in Tabletop / Flex mode (resting on table, screen bent ~90°)
                        postureInfo = PostureInfo(
                            posture = DevicePosture.TABLETOP_FLIP,
                            isTabletop = true,
                            isBookSpread = false,
                            hingeTop = foldingFeature.bounds.top,
                            hingeBottom = foldingFeature.bounds.bottom
                        )
                    } else {
                        // Fold model partially folded vertically like a book
                        postureInfo = PostureInfo(
                            posture = DevicePosture.BOOK_SPREAD,
                            isTabletop = false,
                            isBookSpread = true,
                            hingeTop = foldingFeature.bounds.top,
                            hingeBottom = foldingFeature.bounds.bottom
                        )
                    }
                } else {
                    postureInfo = PostureInfo(
                        posture = DevicePosture.NORMAL,
                        isTabletop = false,
                        isBookSpread = false
                    )
                }
            }
    }

    return postureInfo
}
