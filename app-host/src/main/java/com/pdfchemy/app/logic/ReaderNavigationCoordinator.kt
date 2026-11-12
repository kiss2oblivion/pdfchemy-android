package com.pdfchemy.app.logic

import android.content.Intent
import android.net.Uri
import com.pdfchemy.app.Screen
import com.pdfchemy.app.security.ExternalUriAdmission
import java.util.Locale

/**
 * Coordinates Reader-first document entry across Home, Recent Files, SAF pickers, and external intents,
 * and preserves the active document URI when navigating from the Reader to tools and returning.
 */
class ReaderNavigationCoordinator {

    var activeReaderUri: Uri? = null
        private set

    var readerReturnUri: Uri? = null
        private set

    fun onEnterReader(uri: Uri?) {
        if (uri != null && uri != Uri.EMPTY) {
            activeReaderUri = uri
        }
    }

    fun onExitReader() {
        activeReaderUri = null
        readerReturnUri = null
    }

    /**
     * Records the active Reader document and upgrades parameterized tool screens with the document URI
     * so switching to a tool and pressing Back restores the exact Reader session.
     */
    fun prepareToolNavigationFromReader(sourceUri: Uri?, targetScreen: Screen): Screen {
        val validUri = sourceUri?.takeIf { it != Uri.EMPTY } ?: activeReaderUri?.takeIf { it != Uri.EMPTY }
        activeReaderUri = validUri
        readerReturnUri = validUri
        if (validUri == null) return targetScreen
        return when (targetScreen) {
            is Screen.SplitPdf -> if (targetScreen.initialUri == null) Screen.SplitPdf(validUri) else targetScreen
            is Screen.PdfEditor -> if (targetScreen.initialPdfUri == null) Screen.PdfEditor(validUri) else targetScreen
            is Screen.ReflowReader -> if (targetScreen.initialUri == null) Screen.ReflowReader(validUri) else targetScreen
            is Screen.UnlockPdf -> if (targetScreen.initialUri == null) Screen.UnlockPdf(validUri) else targetScreen
            is Screen.PdfReader -> if (targetScreen.initialPdfUri == null) Screen.PdfReader(validUri) else targetScreen
            else -> targetScreen
        }
    }

    /**
     * Restores [Screen.PdfReader] with the preserved document URI when returning from a tool launched
     * via the Reader; otherwise clears continuity state and navigates to [fallback].
     */
    fun resolveReturnFromTool(fallback: Screen, onClearContinuity: () -> Unit): Screen {
        val returnUri = readerReturnUri?.takeIf { it != Uri.EMPTY }
        readerReturnUri = null
        return if (returnUri != null) {
            activeReaderUri = returnUri
            Screen.PdfReader(initialPdfUri = returnUri)
        } else {
            onClearContinuity()
            fallback
        }
    }

    companion object {
        /**
         * Routes a staged or incoming document URI consistently to [Screen.PdfReader] for PDFs,
         * while preserving specialized handling for `.epub` and `.cbz`/`.cbr` archives.
         */
        fun resolveDocumentEntryScreen(uri: Uri, fileName: String? = null): Screen {
            val rawName = (fileName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "").lowercase(Locale.ROOT)
            val ext = rawName.substringAfterLast('.', "")
            return when (ext) {
                "epub" -> Screen.ReflowReader(initialUri = uri)
                "cbz", "cbr" -> Screen.EbookConverter
                else -> Screen.PdfReader(initialPdfUri = uri)
            }
        }

        /**
         * Extracts a document URI from external `ACTION_VIEW`, `ACTION_SEND`, or `ACTION_SEND_MULTIPLE` intents.
         */
        fun extractIncomingDocumentUri(intent: Intent?): Uri? {
            if (intent == null) return null
            return ExternalUriAdmission.extractCandidateUri(intent)
        }
    }
}
