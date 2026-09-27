package com.pdfchemy.app.sandbox;

import com.pdfchemy.app.sandbox.IPdfSandboxCallback;
import android.os.ParcelFileDescriptor;

interface IPdfSandboxService {
    int getWorkerPid();
    
    void convertPdfToEpub(in ParcelFileDescriptor inputPfd, in ParcelFileDescriptor outputPfd, String title, String author, IPdfSandboxCallback callback);
    void convertEpubToPdf(in ParcelFileDescriptor inputPfd, in ParcelFileDescriptor outputPfd, IPdfSandboxCallback callback);
}
