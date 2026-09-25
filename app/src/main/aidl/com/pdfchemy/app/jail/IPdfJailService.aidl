package com.pdfchemy.app.jail;

import android.os.ParcelFileDescriptor;
import com.pdfchemy.app.jail.IPdfJailCallback;

/**
 * IPC Interface for the isolated PDF Jail Worker.
 */
oneway interface IPdfJailService {
    /**
     * Executes the COMPRESS operation in the isolated service.
     * 
     * @param sourceFd The ParcelFileDescriptor containing the untrusted source PDF.
     * @param targetFd The ParcelFileDescriptor where the processed output should be written.
     * @param targetDpi Target DPI for image downsampling.
     * @param quality JPEG quality factor (0.0 to 1.0).
     * @param rasterizePages True to flatten pages entirely.
     * @param callback Callback to receive the result asynchronously.
     */
    void compressPdf(
        in ParcelFileDescriptor sourceFd, 
        in ParcelFileDescriptor targetFd, 
        float targetDpi, 
        float quality, 
        boolean rasterizePages,
        IPdfJailCallback callback
    );

    /**
     * Analyzes the PDF and returns a JSON string containing the analysis results.
     */
    void analyzePdf(
        in ParcelFileDescriptor sourceFd,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    /**
     * Exports a modified PDF given the source, destination, and a JSON string representing modifications.
     */
    void exportModifiedPdf(
        in ParcelFileDescriptor sourceFd,
        in ParcelFileDescriptor targetFd,
        String modificationsJson,
        IPdfJailCallback callback
    );
    /**
     * Executes a generic engine operation.
     */
    void executeEngine(
        String engineName,
        in ParcelFileDescriptor sourceFd,
        in ParcelFileDescriptor targetFd,
        String paramsJson,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    /**
     * Executes a generic engine operation with an extra file descriptor.
     */
    void executeEngineExtra(
        String engineName,
        in ParcelFileDescriptor sourceFd,
        in ParcelFileDescriptor targetFd,
        in ParcelFileDescriptor extraFd,
        String paramsJson,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    void executeEngineBatch(
        String engineName,
        in ParcelFileDescriptor[] sourceFds,
        in ParcelFileDescriptor[] targetFds,
        String paramsJson,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );
}

