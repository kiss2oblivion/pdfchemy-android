package com.pdfchemy.app.security;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.StrictMode;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Test APK components use only SDK/Java classes so the foreign process is standalone. */
public final class CrossUidSenderFixture {
    private static final Map<String, Record> RECORDS = new HashMap<>();
    private static final class Record {
        final Bundle receipt = new Bundle();
        Uri uri;
        File ownedFile;
        String action;
        boolean grant;
        boolean privateAlias;
    }

    private static String token(String value) {
        if (value == null || !value.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid fixture token");
        return value;
    }

    public static class ReceiptProvider extends EmptyProvider {
        @Override public Bundle call(String method, String argument, Bundle extras) {
            String key = token(argument);
            synchronized (RECORDS) {
                if ("prepare".equals(method)) {
                    if (RECORDS.containsKey(key) || extras == null) throw new IllegalArgumentException("Duplicate or missing fixture");
                    Record record = new Record();
                    record.action = extras.getString("action", Intent.ACTION_VIEW);
                    if (!Intent.ACTION_VIEW.equals(record.action) && !Intent.ACTION_SEND.equals(record.action)
                        && !Intent.ACTION_SEND_MULTIPLE.equals(record.action)) throw new IllegalArgumentException("Invalid action");
                    record.grant = extras.getBoolean("grant");
                    record.privateAlias = extras.getBoolean("private_alias");
                    String supplied = extras.getString("uri");
                    if (supplied != null) {
                        if (supplied.length() > 4096 || record.grant) throw new IllegalArgumentException("Only owned fixture inputs can be granted");
                        record.uri = Uri.parse(supplied);
                    } else {
                        byte[] bytes = extras.getByteArray("bytes");
                        if (bytes == null || bytes.length > 65536) throw new IllegalArgumentException("Invalid synthetic input");
                        File directory = new File(getContext().getFilesDir(), "cross-uid-fixtures");
                        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create fixture directory");
                        record.ownedFile = new File(directory, key + ".pdf");
                        try (FileOutputStream output = new FileOutputStream(record.ownedFile)) {
                            output.write(bytes);
                        } catch (Exception error) { throw new IllegalStateException("Cannot write synthetic fixture", error); }
                        record.uri = Uri.parse("content://com.pdfchemy.app.test.crossuid.input/" + key);
                    }
                    record.receipt.putString("uri", record.uri.toString());
                    record.receipt.putString("action", record.action);
                    record.receipt.putBoolean("grant", record.grant);
                    record.receipt.putInt("sender_uid", Process.myUid());
                    record.receipt.putInt("prepare_caller_uid", Binder.getCallingUid());
                    RECORDS.put(key, record);
                }
                Record record = RECORDS.get(key);
                if (record == null) throw new IllegalArgumentException("Unknown fixture");
                Bundle result = new Bundle(record.receipt);
                if ("clear".equals(method)) {
                    if (record.ownedFile != null) {
                        getContext().revokeUriPermission(record.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (!record.ownedFile.delete() && record.ownedFile.exists()) throw new IllegalStateException("Cannot remove fixture");
                    }
                    RECORDS.remove(key);
                } else if (!"prepare".equals(method) && !"get".equals(method)) {
                    throw new IllegalArgumentException("Unknown fixture operation");
                }
                return result;
            }
        }
    }

    public static class ReadProvider extends EmptyProvider {
        @Override public String getType(Uri uri) { return "application/pdf"; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
            synchronized (RECORDS) {
                Record record = RECORDS.get(token(uri.getLastPathSegment()));
                if (record == null || record.ownedFile == null) throw new FileNotFoundException("Unknown fixture input");
                return ParcelFileDescriptor.open(record.ownedFile, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
    }

    public static class SenderActivity extends Activity {
        private boolean sent;
        @Override protected void onResume() {
            super.onResume();
            if (sent) return;
            sent = true;
            String key = token(getIntent().getStringExtra("token"));
            synchronized (RECORDS) {
                Record record = RECORDS.get(key);
                if (record == null) throw new IllegalArgumentException("Unknown sender fixture");
                try (InputStream input = getContentResolver().openInputStream(record.uri)) {
                    record.receipt.putBoolean("sender_can_read", input != null && input.read() >= 0);
                } catch (Exception expected) {
                    record.receipt.putBoolean("sender_can_read", false);
                    record.receipt.putString("sender_read_error", expected.getClass().getSimpleName());
                }
                StrictMode.VmPolicy previous = StrictMode.getVmPolicy();
                try {
                    // An untrusted caller can disable its own FileUriExposed check.
                    StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
                    Intent outgoing = new Intent(record.action)
                        .setClassName("com.pdfchemy.app", record.privateAlias
                            ? "com.pdfchemy.app.InternalDocumentOpen" : "com.pdfchemy.app.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("crossUidProbeToken", key).putExtra("isScreenshotRun", true);
                    if (Intent.ACTION_VIEW.equals(record.action)) outgoing.setDataAndType(record.uri, "application/pdf");
                    else if (Intent.ACTION_SEND.equals(record.action)) outgoing.setType("application/pdf").putExtra(Intent.EXTRA_STREAM, record.uri);
                    else {
                        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                        uris.add(record.uri);
                        outgoing.setType("application/pdf").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    }
                    outgoing.setClipData(ClipData.newRawUri("Synthetic cross-UID input", record.uri));
                    if (record.grant) outgoing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    record.receipt.putInt("launch_uid", Process.myUid());
                    startActivity(outgoing);
                    record.receipt.putBoolean("sent", true);
                } catch (Exception error) {
                    record.receipt.putString("launch_error", error.getClass().getSimpleName());
                    record.receipt.putString("launch_error_message", error.getMessage());
                } finally { StrictMode.setVmPolicy(previous); }
            }
            finish();
        }
    }

    public abstract static class EmptyProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String sort) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    }
}
