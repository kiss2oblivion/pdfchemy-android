package com.pdfchemy.app.sandbox;
import android.os.ParcelFileDescriptor;
interface IPdfNativeRendererService {
    int getWorkerPid();
    int getWorkerUid();
    void debugBlock(in ParcelFileDescriptor pdfPfd, String sha256, long size);
    oneway void abortWorker();
    void renderPageToJpeg(in ParcelFileDescriptor pdfPfd, int pageIndex, in ParcelFileDescriptor outputJpegPfd, String sha256, long size);
    void renderPageToPixels(in ParcelFileDescriptor pdfPfd, int pageIndex, in ParcelFileDescriptor outputPfd, String sha256, long size, int maxWidth);
    int getPageCount(in ParcelFileDescriptor pdfPfd, String sha256, long size);
}
