package com.pdfchemy.app.security;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic SAF store with controlled creation and output-open gates. */
public class BatchOutputProvider extends ContentProvider {
    private final Map<String, String[]> documents = new LinkedHashMap<>();
    private File directory;
    private CountDownLatch release = new CountDownLatch(1);
    private int sequence, writes, deleted;
    private String firstId, blockedId;
    private volatile CountDownLatch creationRelease = new CountDownLatch(1);
    private volatile boolean creationEntered;
    private boolean blockCreation, returnExisting;
    @Override public boolean onCreate() {
        directory = new File(getContext().getCacheDir(), "sc03-batch-fixture");
        directory.mkdirs();
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        // Creation can model an uninterruptible provider call without blocking its test control channel.
        if ("creationState".equals(method)) {
            Bundle result = new Bundle(); result.putBoolean("entered", creationEntered); return result;
        }
        if ("releaseCreation".equals(method)) { creationRelease.countDown(); return new Bundle(); }
        synchronized (this) {
        try {
            if ("android:createDocument".equals(method)) {
                Uri parent = extras.getParcelable("uri");
                String id = createDocument(DocumentsContract.getDocumentId(parent),
                    extras.getString(Document.COLUMN_MIME_TYPE), extras.getString(Document.COLUMN_DISPLAY_NAME));
                Bundle result = new Bundle(); result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(parent, id)); return result;
            }
            if ("android:deleteDocument".equals(method)) {
                Uri uri = extras.getParcelable("uri"); deleteDocument(DocumentsContract.getDocumentId(uri)); return new Bundle();
            }
            if ("configure".equals(method)) {
                creationRelease.countDown(); creationRelease = new CountDownLatch(1); creationEntered = false;
                blockCreation = extras != null && extras.getBoolean("blockCreation");
                returnExisting = extras != null && extras.getBoolean("returnExisting");
                release.countDown(); release = new CountDownLatch(1);
                for (File file : directory.listFiles()) file.delete();
                documents.clear(); sequence = writes = deleted = 0; firstId = blockedId = null;
                documents.put("existing", new String[]{"existing.pdf", "application/pdf"});
                try (FileOutputStream output = new FileOutputStream(new File(directory, "existing"))) {
                    output.write("existing destination bytes".getBytes(StandardCharsets.UTF_8));
                }
                long identity = android.os.Binder.clearCallingIdentity();
                try {
                    getContext().grantUriPermission("com.pdfchemy.app",
                        DocumentsContract.buildTreeDocumentUri("com.pdfchemy.app.security.batchfixture", "root"),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                } finally { android.os.Binder.restoreCallingIdentity(identity); }
            }
            if ("release".equals(method)) release.countDown();
            Bundle result = new Bundle();
            result.putString("firstId", firstId); result.putString("blockedId", blockedId);
            result.putInt("createdCount", sequence); result.putInt("deletedCount", deleted);
            return result;
        } catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
    public synchronized String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException();
        if (returnExisting) return "existing";
        String id = "doc_" + (++sequence);
        documents.put(id, new String[]{name, mime});
        try { new File(directory, id).createNewFile(); }
        catch (Exception error) { throw new FileNotFoundException(error.toString()); }
        if (blockCreation) {
            creationEntered = true;
            try { if (!creationRelease.await(30, TimeUnit.SECONDS)) throw new FileNotFoundException("Creation fixture timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Creation interrupted"); }
        }
        return id;
    }
    public synchronized void deleteDocument(String id) throws FileNotFoundException {
        if (!documents.containsKey(id)) throw new FileNotFoundException();
        if (new File(directory, id).delete()) deleted++;
    }
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    private void row(MatrixCursor cursor, String id) {
        boolean root = "root".equals(id);
        String[] info = documents.get(id);
        File file = new File(directory, id);
        if (!root && (info == null || !file.exists())) return;
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(root ? "outputs" : info[0]); break;
                case Document.COLUMN_MIME_TYPE: row.add(root ? Document.MIME_TYPE_DIR : info[1]); break;
                case Document.COLUMN_FLAGS: row.add(root ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE); break;
                case Document.COLUMN_SIZE: row.add(root ? 0L : file.length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(0L); break;
                default: row.add(null);
            }
        }
    }
    public synchronized Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection); row(cursor, id); return cursor;
    }
    public synchronized Cursor queryChildDocuments(String parent, String[] projection, String sort) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        for (String id : documents.keySet()) row(cursor, id);
        return cursor;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return "children".equals(uri.getLastPathSegment()) ? queryChildDocuments("root", projection, sort) : queryDocument(DocumentsContract.getDocumentId(uri), projection);
    }
    @Override public synchronized String getType(Uri uri) {
        String id = DocumentsContract.getDocumentId(uri);
        return "root".equals(id) ? Document.MIME_TYPE_DIR : documents.get(id)[1];
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        try { deleteDocument(DocumentsContract.getDocumentId(uri)); return 1; }
        catch (FileNotFoundException error) { return 0; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = DocumentsContract.getDocumentId(uri);
        CountDownLatch wait = null;
        synchronized (this) {
            if (!documents.containsKey(id)) throw new FileNotFoundException();
            if (mode.contains("w")) {
                writes++;
                if (writes == 1) firstId = id;
                if (writes == 2) { blockedId = id; wait = release; }
            }
        }
        if (wait != null) {
            try { if (!wait.await(60, TimeUnit.SECONDS)) throw new FileNotFoundException("Fixture release timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Fixture interrupted"); }
        }
        return ParcelFileDescriptor.open(new File(directory, id), ParcelFileDescriptor.parseMode(mode));
    }
}
