package com.pdfchemy.app.ui;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.util.concurrent.atomic.AtomicInteger;
/** Platform-only fixture; separate test APK processes do not inherit target APK Kotlin classes. */
public final class MetadataFixtureProvider extends ContentProvider {
 private final AtomicInteger active = new AtomicInteger();
 private volatile String renamed;
 @Override public boolean onCreate() { return true; }
 @Override public Bundle call(String method, String arg, Bundle extras) {
  if ("rename".equals(method)) renamed = arg;
  if ("reset".equals(method)) renamed = null;
  Bundle result = new Bundle(); result.putInt("active", active.get()); return result;
 }
 @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return query(u,p,s,a,o,null); }
 @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o, CancellationSignal signal) {
  active.incrementAndGet();
  try { if (u.getPathSegments().get(0).equals("slow")) SystemClock.sleep(2500); }
  finally { active.decrementAndGet(); }
  MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
  String name = "rename".equals(u.getLastPathSegment()) && renamed != null ? renamed : "provider-"+u.getLastPathSegment()+".pdf";
  cursor.addRow(new Object[]{name,123L}); return cursor;
 }
 @Override public String getType(Uri u) {
  if (u.getPathSegments().get(0).equals("slow-type")) SystemClock.sleep(2500);
  return "application/pdf";
 }
 @Override public Uri insert(Uri u, ContentValues v) { return null; }
 @Override public int delete(Uri u,String s,String[] a) { return 0; }
 @Override public int update(Uri u,ContentValues v,String s,String[] a) { return 0; }
}
