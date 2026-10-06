package com.pdfchemy.app.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only SAF-style destination backed by a pipe: its FD cannot be truncated. */
public class OutputCommitProvider extends ContentProvider {
    private static class State {
        byte[] bytes = "existing destination".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int opens;
        boolean block;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch written = new CountDownLatch(1);
    }
    private final Map<String, State> states = new HashMap<>();
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        final State state;
        synchronized (states) {
            if ("configure".equals(method)) {
                State fresh = new State(); fresh.block = extras != null && extras.getBoolean("block");
                states.put(arg, fresh);
            }
            state = states.get(arg);
        }
        if (state == null) throw new IllegalStateException("Missing fixture");
        if ("release".equals(method)) state.release.countDown();
        Bundle result = new Bundle();
        synchronized (state) { result.putInt("opens", state.opens); result.putByteArray("bytes", state.bytes); }
        result.putBoolean("written", state.written.getCount() == 0);
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) {
        final State state;
        synchronized (states) { state = states.get(uri.getLastPathSegment()); }
        if (state == null || !"wt".equals(mode)) throw new IllegalArgumentException("Write fixture only");
        synchronized (state) { state.opens++; }
        try {
            if (state.block && !state.release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Open fixture timed out");
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    synchronized (state) { state.bytes = bytes.toByteArray(); }
                } catch (Exception error) { throw new IllegalStateException(error); }
                finally { state.written.countDown(); }
            }).start();
            return pipe[1];
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
