package com.pdfchemy.app.sandbox;

import android.os.ParcelFileDescriptor;

interface IPdfNativeRendererService {
    int getWorkerPid();
    
    // Renders the requested page from the given PDF FileDescriptor to the output JPEG FileDescriptor
    void renderPageToJpeg(in ParcelFileDescriptor pdfPfd, int pageIndex, in ParcelFileDescriptor outputJpegPfd);
    
    // Returns the total number of pages in the PDF
    int getPageCount(in ParcelFileDescriptor pdfPfd);
}
