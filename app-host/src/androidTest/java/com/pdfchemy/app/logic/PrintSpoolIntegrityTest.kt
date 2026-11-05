package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintDocumentAdapter
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.security.SecurityLimits
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PrintSpoolIntegrityTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val provider=Uri.parse("content://com.pdfchemy.app.test.printsource")
    private fun remote(method:String,token:String,extras:Bundle?=null)=context.contentResolver.call(provider,method,token,extras)!!
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private class Result {
        val callback=mockk<PrintDocumentAdapter.WriteResultCallback>()
        val done=CountDownLatch(1); val calls=AtomicInteger(); @Volatile var status="pending"
        private fun complete(value:String) { status=value; calls.incrementAndGet(); done.countDown() }
        init {
            every { callback.onWriteFinished(any()) } answers { complete("finished") }
            every { callback.onWriteCancelled() } answers { complete("cancelled") }
            every { callback.onWriteFailed(any()) } answers { complete("failed") }
        }
        fun await(seconds:Long=10):String { assertTrue("Print callback did not terminate",done.await(seconds,TimeUnit.SECONDS)); return status }
    }
    private fun fixture(mode:String,size:Long=8192,block:(PrintDocumentAdapter,ParcelFileDescriptor,CancellationSignal,Result,File,String)->Unit) {
        val token="print_${System.nanoTime()}"
        remote("configure",token,Bundle().apply { putString("mode",mode); putLong("size",size) })
        val output=File.createTempFile("print_spool_",".pdf",context.cacheDir)
        val destination=ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        val signal=CancellationSignal(); val result=Result()
        val adapter=DocumentActions.createPrintAdapter(context,Uri.withAppendedPath(provider,token),"Fixture.pdf")
        try { block(adapter,destination,signal,result,output,token) }
        finally {
            main { signal.cancel(); adapter.onFinish() }
            remote("release",token)
            runCatching { destination.close() }; output.delete()
        }
    }
    private fun start(a:PrintDocumentAdapter,d:ParcelFileDescriptor,s:CancellationSignal,r:Result,pages:Array<PageRange> = arrayOf(PageRange.ALL_PAGES)) =
        main { a.onWrite(pages,d,s,r.callback) }

    @Test fun completedSpoolPreservesEveryInputByte() = fixture("bytes") { a,d,s,r,file,_ ->
        start(a,d,s,r); assertEquals("finished",r.await())
        assertArrayEquals(ByteArray(8192){(it%251).toByte()},file.readBytes()); assertEquals(1,r.calls.get())
    }
    @Test fun assetSlicePrintsOnlyItsDeclaredBytes() = fixture("slice") { a,d,s,r,file,_ ->
        start(a,d,s,r); assertEquals("finished",r.await())
        assertArrayEquals(ByteArray(8192){(it%251).toByte()},file.readBytes()); assertEquals(1,r.calls.get())
    }
    @Test fun reliablePipeProducerFailureCannotReportCompletedPrint() = fixture("pipeerror") { a,d,s,r,_,_ ->
        start(a,d,s,r); assertEquals("failed",r.await()); assertEquals(1,r.calls.get())
    }
    @Test fun cancellationBeforeStartNeverOpensProviderOrWritesOutput() = fixture("bytes") { a,d,s,r,file,token ->
        s.cancel(); start(a,d,s,r); assertEquals("cancelled",r.await())
        assertFalse(remote("state",token).getBoolean("opened")); assertEquals(0L,file.length())
    }
    @Test fun cancellationOfStalledProviderTerminatesExactlyOnce() = fixture("stall") { a,d,s,r,_,token ->
        start(a,d,s,r); assertTrue(remote("awaitOpen",token).getBoolean("opened"))
        main { repeat(16) { s.cancel() } }; assertEquals("cancelled",r.await()); assertEquals(1,r.calls.get())
    }
    @Test fun cancellationClosesTheWriterBeforeNotifyingTheSpooler() = fixture("bytes",1024*1024) { a,_,s,r,_,token ->
        val pipe=ParcelFileDescriptor.createPipe()
        try {
            val flags=Os.fcntlInt(pipe[1].fileDescriptor,OsConstants.F_GETFL,0)
            Os.fcntlInt(pipe[1].fileDescriptor,OsConstants.F_SETFL,flags or OsConstants.O_NONBLOCK)
            try {
                val buffer=ByteArray(4096)
                while(true) Os.write(pipe[1].fileDescriptor,buffer,0,buffer.size)
            } catch(error:ErrnoException) { assertEquals(OsConstants.EAGAIN,error.errno) }
            finally { Os.fcntlInt(pipe[1].fileDescriptor,OsConstants.F_SETFL,flags) }
            start(a,pipe[1],s,r); assertTrue(remote("awaitOpen",token).getBoolean("opened"))
            main { s.cancel() }; assertEquals("cancelled",r.await())
            val poll=StructPollfd().apply { fd=pipe[0].fileDescriptor; events=OsConstants.POLLIN.toShort() }
            Os.poll(arrayOf(poll),0)
            assertTrue("No output descriptor may outlive its terminal callback",poll.revents.toInt() and OsConstants.POLLHUP != 0)
        } finally { pipe.forEach { runCatching { it.close() } } }
    }
    @Test fun adapterFinishRetiresAStalledWrite() = fixture("stall") { a,d,s,r,_,token ->
        start(a,d,s,r); assertTrue(remote("awaitOpen",token).getBoolean("opened"))
        main { a.onFinish() }; assertEquals("cancelled",r.await()); assertEquals(1,r.calls.get())
    }
    @Test fun outputOneChunkOverTheRealQuotaFailsWithoutSuccess() = fixture("bytes",SecurityLimits.MAX_OUTPUT_BYTES+8192) { a,d,s,r,file,_ ->
        start(a,d,s,r); assertEquals("failed",r.await(TimeUnit.MILLISECONDS.toSeconds(SecurityLimits.WORKER_DEADLINE_MS)+5))
        assertEquals(SecurityLimits.MAX_OUTPUT_BYTES,file.length()); assertEquals(1,r.calls.get())
    }
    @Test fun unavailableInputReportsFailureAndLeavesNoSpoolBytes() = fixture("error") { a,d,s,r,file,token ->
        start(a,d,s,r); assertEquals("failed",r.await()); assertEquals(0L,file.length())
        assertTrue(remote("state",token).getBoolean("opened"))
    }
    @Test fun unsupportedPageRangeCannotProduceAnOutput() = fixture("bytes") { a,d,s,r,file,token ->
        start(a,d,s,r,arrayOf(PageRange(0,0))); assertEquals("failed",r.await())
        assertFalse(remote("state",token).getBoolean("opened")); assertEquals(0L,file.length())
    }
}
