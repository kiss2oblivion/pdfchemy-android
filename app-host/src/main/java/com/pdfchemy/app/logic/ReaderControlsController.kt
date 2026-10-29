package com.pdfchemy.app.logic

import android.net.Uri
import com.pdfchemy.app.Screen

data class ReaderViewportPaddingDp(
    val topDp: Int,
    val bottomDp: Int,
    val horizontalDp: Int
)

data class ReaderBarLayoutSpec(
    val minTouchTargetDp: Int = 48,
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val compactLandscape: Boolean,
    val useHorizontalScrollForBottomActions: Boolean
)

object ReaderAccessibilityLayout {
    const val MIN_TOUCH_TARGET_DP = 48

    fun computeBarLayout(
        screenWidthDp: Int,
        isLandscape: Boolean,
        fontScale: Float
    ): ReaderBarLayoutSpec {
        val isSmallScreen = screenWidthDp <= 360
        val isLargeFont = fontScale >= 1.3f
        return ReaderBarLayoutSpec(
            minTouchTargetDp = MIN_TOUCH_TARGET_DP,
            horizontalPaddingDp = if (isSmallScreen) 8 else 16,
            verticalPaddingDp = if (isLandscape) 6 else 10,
            compactLandscape = isLandscape,
            useHorizontalScrollForBottomActions = isSmallScreen || isLargeFont || isLandscape
        )
    }
}

enum class ReaderBackAction {
    CLOSE_COMMAND_CENTRE,
    CLOSE_TOC,
    CLOSE_SEARCH,
    RESET_ZOOM,
    EXIT_READER
}

enum class ReaderCommandAction(val maxTapsFromReader: Int) {
    EDIT_ANNOTATE(1),
    REFLOW_READER(1),
    COMPRESS(1),
    OCR_SEARCHABLE(1),
    SIGN_PDF(1),
    PROTECT_ENCRYPT(1),
    SPLIT(2),
    PAGE_ORGANIZER(2),
    ROTATE_PAGES(2),
    DELETE_PAGES(2),
    FILL_FORM(2),
    QUICK_FILL_SIGN(2),
    EXTRACT_TABLES(2),
    EXPORT_OFFICE(2),
    REDACT(2),
    WATERMARK(2),
    PAGE_NUMBERS(2)
}

object ReaderCommandCentreCatalog {

    val primaryBarActions: List<ReaderCommandAction> = listOf(
        ReaderCommandAction.COMPRESS,
        ReaderCommandAction.OCR_SEARCHABLE,
        ReaderCommandAction.SIGN_PDF,
        ReaderCommandAction.PROTECT_ENCRYPT
    )

    val allSheetActions: List<ReaderCommandAction> = ReaderCommandAction.entries

    fun resolveScreen(action: ReaderCommandAction, activeDocumentUri: Uri?): Screen {
        return when (action) {
            ReaderCommandAction.EDIT_ANNOTATE -> Screen.PdfEditor(initialPdfUri = activeDocumentUri)
            ReaderCommandAction.REFLOW_READER -> Screen.ReflowReader(initialUri = activeDocumentUri)
            ReaderCommandAction.COMPRESS -> Screen.CompressPdf
            ReaderCommandAction.OCR_SEARCHABLE -> Screen.OcrPdf
            ReaderCommandAction.SIGN_PDF -> Screen.SignPdf
            ReaderCommandAction.PROTECT_ENCRYPT -> Screen.ProtectPdf
            ReaderCommandAction.SPLIT -> Screen.SplitPdf
            ReaderCommandAction.PAGE_ORGANIZER -> Screen.PageOrganizer
            ReaderCommandAction.ROTATE_PAGES -> Screen.RotatePdf
            ReaderCommandAction.DELETE_PAGES -> Screen.DeletePages
            ReaderCommandAction.FILL_FORM -> Screen.FillForm
            ReaderCommandAction.QUICK_FILL_SIGN -> Screen.QuickFillSign
            ReaderCommandAction.EXTRACT_TABLES -> Screen.TableExtractor
            ReaderCommandAction.EXPORT_OFFICE -> Screen.OfficeExport(OfficeFormat.WORD)
            ReaderCommandAction.REDACT -> Screen.Redaction
            ReaderCommandAction.WATERMARK -> Screen.Watermark
            ReaderCommandAction.PAGE_NUMBERS -> Screen.PageNumber
        }
    }
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
