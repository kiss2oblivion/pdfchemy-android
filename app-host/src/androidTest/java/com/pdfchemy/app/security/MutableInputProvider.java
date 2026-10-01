package com.pdfchemy.app.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.IOException;

/** Test APK only. Pure Java because the provider runs outside instrumentation's combined class loader. */
public class MutableInputProvider extends ContentProvider {
    private byte[] first = new byte[0];
    private byte[] subsequent = new byte[0];
    private int opens;
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if ("configure".equals(method)) {
            if (extras == null) throw new IllegalArgumentException("Missing fixture");
            first = extras.getByteArray("first");
            subsequent = extras.getByteArray("subsequent");
            if (first == null || subsequent == null) throw new IllegalArgumentException("Missing fixture bytes");
            opens = 0;
        }
        Bundle state = new Bundle(); state.putInt("opens", opens); return state;
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read only fixture");
        final byte[] bytes = opens++ == 0 ? first : subsequent;
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(bytes);
                } catch (IOException ignored) { /* Reader may close a rejected input early. */ }
            }).start();
            return pipe[0];
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{"changing.pdf", null}); return cursor;
    }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
