package com.pdfchemy.app.jail;

/** Bounded, synchronous telemetry for one admitted OCR operation. No output acceptance. */
interface IPdfOcrProgressCallback {
    void onProgress(long operationId, int completedPages, int totalPages, boolean saving);
}
