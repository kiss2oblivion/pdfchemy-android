package com.pdfchemy.app.security

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.BoundedWorkerStorage
import com.pdfchemy.app.jail.BoundedWorkerStorage.Kind
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.jail.IPdfJailService
import com.pdfchemy.app.jail.IPdfJailStringCallback
import com.pdfchemy.app.jail.OperationScratchBroker
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Small raw-syscall probes; no disk-exhaustion experiment and no cooperative writer wrapper. */
@RunWith(AndroidJUnit4::class)
class WorkerStorageBoundarySecurityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun producer(sink: BoundedWorkerStorage.Sink): ParcelFileDescriptor = sink.writer.dup().also { sink.writer.close() }

    private fun write(fd: ParcelFileDescriptor, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = Os.write(fd.fileDescriptor, bytes, offset, bytes.size - offset)
            check(count > 0)
            offset += count
        }
    }

    private fun rejects(block: () -> Unit): Throwable = try { block(); throw AssertionError("Raw syscall unexpectedly succeeded") }
        catch (error: ErrnoException) { error }

    private suspend fun <T> withWorker(block: suspend (IPdfJailService) -> T): T {
        val bound = CompletableDeferred<IPdfJailService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound.complete(IPdfJailService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) { bound.completeExceptionally(IOException("Worker disconnected")) }
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        try { return block(withTimeout(15_000) { bound.await() }) }
        finally { context.unbindService(connection) }
    }

    private suspend fun execute(worker: IPdfJailService, scratch: OperationScratchBroker, output: ParcelFileDescriptor?, raw: Boolean): String {
        val result = CompletableDeferred<String>()
        val token = worker.beginOperation(scratch)
        check(token > 0)
        try {
            worker.executeEngine(token, "DEBUG_SCRATCH", null, output, "{\"rawOutput\":$raw}", null, "", 0, scratch,
                object : IPdfJailStringCallback.Stub() {
                    override fun onSuccess(resultJson: String) { result.complete(resultJson) }
                    override fun onFailure(errorCode: Int, errorMessage: String) { result.completeExceptionally(IOException(errorMessage)) }
                })
            output?.close()
            val response = withTimeout(15_000) { result.await() }
            scratch.verifyBudget()
            check(worker.completeOperation(token))
            return response
        } catch (error: Throwable) {
            runCatching { worker.abortOperation(token) }
            throw error
        }
    }

    @Test fun isolatedScratchHandoffIsReadOnlyAndCannotReopenBacking() = runBlocking {
        withWorker { worker ->
            OperationScratchBroker(context).use { scratch ->
                val response = JSONObject(execute(worker, scratch, null, false))
                assertTrue(response.getBoolean("scratchCapability"))
                assertTrue(response.getBoolean("readOnly"))
                assertNotEquals(android.os.Process.myUid(), response.getInt("uid"))
            }
        }
    }

    @Test fun isolatedRawOutputCannotBypassHostQuotaOrOpenDestination() = runBlocking {
        val id = "raw_storage_${System.nanoTime()}"
        val target = Uri.parse("content://com.pdfchemy.app.security.outputfixture/$id")
        context.contentResolver.call(target, "configure", id, Bundle())
        withWorker { worker ->
            OperationScratchBroker(context, BoundedWorkerStorage(context, 32 * 1024L, 32 * 1024L, 64 * 1024L)).use { scratch ->
                HostOutputTransaction(context, listOf(target), scratch.storage).use { transaction ->
                    val result = runCatching { execute(worker, scratch, transaction.workerDescriptors.single(), true) }
                    assertTrue(result.isFailure)
                    val uid = Regex("uid=(\\d+)").find(result.exceptionOrNull()?.message.orEmpty())?.groupValues?.get(1)?.toInt()
                    assertNotNull("Raw syscall rejection must come from the actual isolated producer", uid)
                    assertNotEquals(android.os.Process.myUid(), uid)
                    assertTrue(runCatching { transaction.validateAndSnapshot("true") }.isFailure)
                    assertTrue(runCatching { transaction.commit() }.isFailure)
                    assertTrue(scratch.storage.peakStoredBytes <= 32 * 1024L)
                    val state = requireNotNull(context.contentResolver.call(target, "state", id, null))
                    assertEquals(0, state.getInt("opens"))
                    assertEquals("existing destination", String(state.getByteArray("bytes")!!))
                }
            }
        }
    }

    @Test fun producerIsPipeAndCannotSeekOrSparseTruncate() {
        BoundedWorkerStorage(context, 4096, 4096, 8192).use { storage ->
            val sink = storage.create(Kind.OUTPUT)
            producer(sink).use { fd ->
                assertTrue(OsConstants.S_ISFIFO(Os.fstat(fd.fileDescriptor).st_mode))
                assertEquals(OsConstants.ESPIPE, (rejects { Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET) } as ErrnoException).errno)
                rejects { Os.ftruncate(fd.fileDescriptor, 1_000_000_000) }
                assertEquals(0L, sink.storedBytes)
            }
        }
    }

    @Test fun completedSnapshotIsSeekableAndReadOnly() {
        BoundedWorkerStorage(context, 4096, 4096, 8192).use { storage ->
            val sink = storage.create(Kind.SCRATCH)
            producer(sink).use { write(it, "seekable scratch".toByteArray()) }
            storage.snapshot(sink.id).use { fd ->
                assertTrue(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode))
                assertEquals(OsConstants.O_RDONLY, Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE)
                assertEquals(9L, Os.lseek(fd.fileDescriptor, 9, OsConstants.SEEK_SET))
                val bytes = ByteArray(7)
                assertEquals(7, Os.read(fd.fileDescriptor, bytes, 0, bytes.size))
                assertEquals("scratch", String(bytes))
                rejects { Os.write(fd.fileDescriptor, byteArrayOf(1), 0, 1) }
                rejects { Os.ftruncate(fd.fileDescriptor, 1_000_000_000) }
            }
        }
    }

    @Test fun retainedReadOnlyDescriptorCannotPinPayloadAfterClose() {
        val storage = BoundedWorkerStorage(context, 128 * 1024L, 128 * 1024L, 256 * 1024L)
        try {
            val empty = storage.create(Kind.SCRATCH)
            producer(empty).close()
            val emptyBlocks = storage.snapshot(empty.id).use { Os.fstat(it.fileDescriptor).st_blocks }
            val sink = storage.create(Kind.SCRATCH)
            producer(sink).use { write(it, ByteArray(64 * 1024) { (it * 31).toByte() }) }
            storage.snapshot(sink.id).use { retained ->
                val before = Os.fstat(retained.fileDescriptor)
                assertEquals(64 * 1024L, before.st_size)
                storage.close()
                val after = Os.fstat(retained.fileDescriptor)
                assertEquals("Host truncation must release retained scratch payload", 0L, after.st_size)
                assertTrue("Payload blocks retained: empty=$emptyBlocks before=${before.st_blocks} after=${after.st_blocks}",
                    after.st_blocks <= emptyBlocks)
            }
        } finally { storage.close() }
    }

    @Test fun rawWritesCannotBypassHostOutputLimit() {
        BoundedWorkerStorage(context, 32 * 1024L, 32 * 1024L, 64 * 1024L).use { storage ->
            val sink = storage.create(Kind.OUTPUT)
            producer(sink).use { fd ->
                rejects { write(fd, ByteArray(256 * 1024)) }
            }
            assertThrows(IOException::class.java) { storage.snapshot(sink.id).close() }
            assertTrue(storage.peakStoredBytes <= 32 * 1024L)
            assertTrue(sink.storedBytes <= 32 * 1024L)
        }
    }

    @Test fun outputAndScratchShareHostAggregateLimit() {
        BoundedWorkerStorage(context, 64 * 1024L, 64 * 1024L, 96 * 1024L).use { storage ->
            val output = storage.create(Kind.OUTPUT)
            producer(output).use { write(it, ByteArray(48 * 1024)) }
            storage.snapshot(output.id).use { retained ->
                val scratch = storage.create(Kind.SCRATCH)
                producer(scratch).use { fd -> rejects { write(fd, ByteArray(256 * 1024)) } }
                assertThrows(IOException::class.java) { storage.verify(Kind.SCRATCH) }
                assertTrue(storage.peakStoredBytes <= 96 * 1024L)
                assertTrue(output.storedBytes + scratch.storedBytes <= 96 * 1024L)
            }
        }
    }

    @Test fun retainedWriterCannotBlockPastHostDeadline() {
        BoundedWorkerStorage(context, 4096, 4096, 8192, timeoutMs = 1_000).use { storage ->
            val sink = storage.create(Kind.SCRATCH)
            producer(sink).use { fd ->
                write(fd, byteArrayOf(1))
                assertThrows(IOException::class.java) { storage.snapshot(sink.id).close() }
            }
        }
    }

    @Test fun scratchDescriptorAllocationIsBounded() {
        BoundedWorkerStorage(context, 4096, 4096, 8192).use { storage ->
            repeat(SecurityLimits.MAX_SCRATCH_FDS) { storage.create(Kind.SCRATCH) }
            assertThrows(IllegalArgumentException::class.java) { storage.create(Kind.SCRATCH) }
        }
    }

    @Test fun snapshotWaitRemainsCancellableWithRetainedWriter() = runBlocking {
        BoundedWorkerStorage(context, 4096, 4096, 8192, timeoutMs = 10_000).use { storage ->
            val sink = storage.create(Kind.SCRATCH)
            producer(sink).use { retained ->
                write(retained, byteArrayOf(1))
                val waiting = async(Dispatchers.IO) { storage.snapshotCancellable(sink.id).close() }
                delay(100)
                withTimeout(2_000) { waiting.cancelAndJoin() }
                assertTrue(waiting.isCancelled)
            }
        }
    }
}
