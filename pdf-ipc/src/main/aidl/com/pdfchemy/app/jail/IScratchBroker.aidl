package com.pdfchemy.app.jail;
import android.os.ParcelFileDescriptor;
interface IScratchBroker {
    ParcelFileDescriptor allocate();
}
