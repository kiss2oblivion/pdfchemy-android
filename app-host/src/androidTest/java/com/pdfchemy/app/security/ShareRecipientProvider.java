package com.pdfchemy.app.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Standalone test-APK receiver: reads as a foreign UID, never as the caller. */
public class ShareRecipientProvider extends ContentProvider {
    private final Map<String, InputStream> consumers = new HashMap<>();
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method, String argument, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        try {
            if ("close".equals(method)) {
                for (InputStream stream : consumers.values()) stream.close();
                consumers.clear();
            } else if ("open".equals(method)) {
                if (consumers.size() >= 2 || consumers.containsKey(argument)) throw new IllegalStateException("Receiver slots full");
                consumers.put(argument, getContext().getContentResolver().openInputStream(Uri.parse(extras.getString("uri"))));
            } else {
                InputStream stream = "readOpen".equals(method) ? consumers.get(argument)
                    : getContext().getContentResolver().openInputStream(Uri.parse(argument));
                try {
                    if (stream == null) throw new IllegalStateException("Missing stream");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > 65536) throw new IllegalStateException("Fixture read bound");
                        bytes.write(buffer, 0, count);
                    }
                    result.putByteArray("bytes", bytes.toByteArray());
                } finally { if (!"readOpen".equals(method) && stream != null) stream.close(); }
            }
        } catch (Exception error) { result.putString("error", error.getClass().getSimpleName()); }
        finally { Binder.restoreCallingIdentity(identity); }
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
