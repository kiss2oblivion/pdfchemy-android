package com.pdfchemy.app.jail;

import android.os.ParcelFileDescriptor;
import android.os.IBinder;
import com.pdfchemy.app.jail.IPdfJailCallback;

/**
 * IPC Interface for the isolated PDF Jail Worker.
 * Synchronous admission handshake with tokenized ownership.
 * Heavy execution methods are individually oneway.
 */
interface IPdfJailService {
    long beginOperation(IBinder ownerBinder);
    boolean completeOperation(long operationId);
    oneway void abortOperation(long operationId);
    oneway void abortWorker();

    /**
     * Executes the COMPRESS operation in the isolated service.
     */
    oneway void compressPdf(
        long operationId,
        in ParcelFileDescriptor sourceFd, 
        in ParcelFileDescriptor targetFd, 
        float targetDpi, 
        float quality, 
        boolean rasterizePages,
        String expectedSha256,
        long expectedSize,
        IBinder scratchBinder,
        IPdfJailCallback callback
    );

    /**
     * Analyzes the PDF and returns a JSON string containing the analysis results.
     */
    oneway void analyzePdf(
        long operationId,
        in ParcelFileDescriptor sourceFd,
        String expectedSha256,
        long expectedSize,
        IBinder scratchBinder,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    /**
     * Exports a modified PDF given the source, destination, and a JSON string representing modifications.
     */
    oneway void exportModifiedPdf(
        long operationId,
        in ParcelFileDescriptor sourceFd,
        in ParcelFileDescriptor targetFd,
        String modificationsJson,
        String expectedSha256,
        long expectedSize,
        IBinder scratchBinder,
        IPdfJailCallback callback
    );

    /**
     * Executes a generic engine operation.
     */
    oneway void executeEngine(
        long operationId,
        String engineName,
        in ParcelFileDescriptor sourceFd,
        in ParcelFileDescriptor targetFd,
        String paramsJson,
        IBinder rendererBinder,
        String expectedSha256,
        long expectedSize,
        IBinder scratchBinder,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    /**
     * Executes a generic engine operation with an extra file descriptor.
     */
    oneway void executeEngineExtra(
        long operationId,
        String engineName,
        in ParcelFileDescriptor sourceFd,
        in ParcelFileDescriptor targetFd,
        in ParcelFileDescriptor extraFd,
        String paramsJson,
        IBinder rendererBinder,
        String expectedSha256,
        long expectedSize,
        String extraExpectedSha256,
        long extraExpectedSize,
        IBinder scratchBinder,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    oneway void executeEngineBatch(
        long operationId,
        String engineName,
        in ParcelFileDescriptor[] sourceFds,
        in ParcelFileDescriptor[] targetFds,
        String paramsJson,
        IBinder rendererBinder,
        in String[] expectedSha256s,
        in long[] expectedSizes,
        IBinder scratchBinder,
        com.pdfchemy.app.jail.IPdfJailStringCallback callback
    );

    // Debug builds only: observe/overwrite the retained temporary output fixture.
    oneway void debugOutputProbe(boolean rewrite, com.pdfchemy.app.jail.IPdfJailCallback callback);
}
