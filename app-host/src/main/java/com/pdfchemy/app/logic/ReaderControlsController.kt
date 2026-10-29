package com.pdfchemy.app.logic

data class ReaderViewportPaddingDp(
    val topDp: Int,
    val bottomDp: Int,
    val horizontalDp: Int
)

enum class ReaderBackAction {
    CLOSE_COMMAND_CENTRE,
    CLOSE_TOC,
    CLOSE_SEARCH,
    RESET_ZOOM,
    EXIT_READER
}

data class ReaderControlsUiState(
    val showControls: Boolean = true,
    val isSearchMode: Boolean = false,
    val showTocSheet: Boolean = false,
    val showCommandCentreSheet: Boolean = false
) {
    fun onCanvasTap(): ReaderControlsUiState {
        val nextShow = !showControls
        return copy(
            showControls = nextShow,
            isSearchMode = if (!nextShow) false else isSearchMode,
            showTocSheet = false,
            showCommandCentreSheet = false
        )
    }

    fun onToggleSearch(): ReaderControlsUiState {
        val nextSearch = !isSearchMode
        return copy(
            showControls = true,
            isSearchMode = nextSearch,
            showTocSheet = false,
            showCommandCentreSheet = false
        )
    }

    fun onOpenToc(): ReaderControlsUiState {
        return copy(
            showTocSheet = true,
            showCommandCentreSheet = false
        )
    }

    fun onDismissToc(): ReaderControlsUiState {
        return copy(showTocSheet = false)
    }

    fun onOpenCommandCentre(): ReaderControlsUiState {
        return copy(
            showCommandCentreSheet = true,
            showTocSheet = false
        )
    }

    fun onDismissCommandCentre(): ReaderControlsUiState {
        return copy(showCommandCentreSheet = false)
    }

    fun resolveBackAction(isZoomed: Boolean): Pair<ReaderBackAction, ReaderControlsUiState> {
        return when {
            showCommandCentreSheet -> ReaderBackAction.CLOSE_COMMAND_CENTRE to copy(showCommandCentreSheet = false)
            showTocSheet -> ReaderBackAction.CLOSE_TOC to copy(showTocSheet = false)
            isSearchMode -> ReaderBackAction.CLOSE_SEARCH to copy(isSearchMode = false)
            isZoomed -> ReaderBackAction.RESET_ZOOM to this
            else -> ReaderBackAction.EXIT_READER to this
        }
    }

    companion object {
        /**
         * Returns viewport padding that depends ONLY on orientation and never on
         * [showControls], guaranteeing zero scroll/layout jump when toggling overlays.
         */
        fun computeStableViewportPadding(isLandscape: Boolean): ReaderViewportPaddingDp {
            return if (isLandscape) {
                ReaderViewportPaddingDp(topDp = 64, bottomDp = 96, horizontalDp = 24)
            } else {
                ReaderViewportPaddingDp(topDp = 80, bottomDp = 120, horizontalDp = 16)
            }
        }
    }
}

