package com.pdfchemy.app.jail;

/**
 * Callback for string-based results from the jail service.
 */
oneway interface IPdfJailStringCallback {
    void onSuccess(String resultJson);
    void onFailure(int errorCode, String errorMessage);
}
