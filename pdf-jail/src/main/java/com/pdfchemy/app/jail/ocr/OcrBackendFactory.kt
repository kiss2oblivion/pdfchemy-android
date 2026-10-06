package com.pdfchemy.app.jail.ocr

import android.content.Context

/**
 * Factory providing the active OcrBackend implementation for the isolated jail service.
 */
object OcrBackendFactory {
    fun create(context: Context): OcrBackend {
        return TesseractOcrBackend(context)
    }
}
