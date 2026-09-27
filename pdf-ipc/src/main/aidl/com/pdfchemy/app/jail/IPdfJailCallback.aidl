package com.pdfchemy.app.jail;

/**
 * Callback interface for the PDF Jail Service.
 * Allows the isolated worker to report success or failure back to the host process asynchronously.
 */
oneway interface IPdfJailCallback {
    /**
     * Called when the operation completes successfully.
     */
    void onSuccess(long outputSizeBytes);

    /**
     * Called when the operation fails.
     * @param errorCode An arbitrary error code.
     * @param errorMessage A descriptive error message.
     */
    void onFailure(int errorCode, String errorMessage);
}
