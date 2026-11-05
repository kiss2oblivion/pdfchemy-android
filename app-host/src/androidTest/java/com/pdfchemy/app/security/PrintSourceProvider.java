package com.pdfchemy.app.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded synthetic stream/pipe fixtures in the test APK's separate process. */
public class PrintSourceProvider extends ContentProvider {
    static class Source {
        final String mode; final long size;
        final CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean stopped; volatile Thread writer; volatile java.io.File file;
        Source(Bundle extras) { mode=extras.getString("mode"); size=extras.getLong("size"); }
    }
    private final ConcurrentHashMap<String,Source> sources = new ConcurrentHashMap<>();
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String token, Bundle extras) {
        Bundle result = new Bundle();
        if ("configure".equals(method)) {
            if (sources.size() >= 4) throw new IllegalStateException("Fixture slots full");
            sources.put(token, new Source(extras));
        } else {
            Source source = sources.get(token);
            if (source == null) throw new IllegalStateException("Unknown fixture");
            if ("awaitOpen".equals(method)) {
                try { result.putBoolean("opened",source.opened.await(10,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else if ("state".equals(method)) {
                result.putBoolean("opened",source.opened.getCount()==0);
            } else if ("release".equals(method)) {
                sources.remove(token); source.stopped=true; source.release.countDown();
                if(source.writer!=null) source.writer.interrupt();
                if(source.file!=null) source.file.delete();
            }
        }
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        Source source=sources.get(uri.getLastPathSegment());
        if(source==null || !"r".equals(mode)) throw new FileNotFoundException("Unknown fixture");
        source.opened.countDown();
        if("error".equals(source.mode)) throw new FileNotFoundException("Synthetic unavailable input");
        try {
            ParcelFileDescriptor[] pipe="pipeerror".equals(source.mode)
                ? ParcelFileDescriptor.createReliablePipe() : ParcelFileDescriptor.createPipe();
            source.writer=new Thread(() -> {
                try (java.io.OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if("stall".equals(source.mode)) { source.release.await(); return; }
                    byte[] buffer=new byte[8192]; for(int i=0;i<buffer.length;i++) buffer[i]=(byte)(i%251);
                    long left=source.size;
                    while(left>0 && !source.stopped) {
                        int count=(int)Math.min(left,buffer.length); output.write(buffer,0,count); left-=count;
                    }
                    if("pipeerror".equals(source.mode)) pipe[1].closeWithError("Synthetic print source failure");
                } catch(Exception expected) { /* Reader cancellation closes its pipe. */ }
            },"PrintFixtureWriter");
            source.writer.start();
            return pipe[0];
        } catch(IOException error) { throw new FileNotFoundException("Fixture pipe unavailable"); }
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode) throws FileNotFoundException {
        Source source=sources.get(uri.getLastPathSegment());
        if(source!=null && "slice".equals(source.mode)) {
            try {
                source.file=java.io.File.createTempFile("print_asset_",".bin",getContext().getCacheDir());
                byte[] buffer=new byte[8192]; for(int i=0;i<buffer.length;i++) buffer[i]=(byte)(i%251);
                try(java.io.OutputStream output=new java.io.FileOutputStream(source.file)) {
                    output.write(new byte[17]); output.write(buffer); output.write(new byte[23]);
                }
                source.opened.countDown();
                return new AssetFileDescriptor(ParcelFileDescriptor.open(source.file,ParcelFileDescriptor.MODE_READ_ONLY),17,8192);
            } catch(IOException error) { throw new FileNotFoundException("Fixture asset unavailable"); }
        }
        return new AssetFileDescriptor(openFile(uri,mode),0,AssetFileDescriptor.UNKNOWN_LENGTH);
    }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) { return null; }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
