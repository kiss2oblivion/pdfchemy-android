package com.pdfchemy.app.security

import android.content.*
import android.net.Uri
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.sandbox.IPdfNativeRendererService
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeRendererIsolationSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Binding(val renderer: IPdfNativeRendererService, val unbind: () -> Unit) : Closeable {
        override fun close() = unbind()
    }
    private suspend fun bind(): Binding {
        val channel = Channel<IBinder>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { channel.trySend(requireNotNull(service)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.sandbox.PdfNativeRendererService"), connection, Context.BIND_AUTO_CREATE))
        return Binding(IPdfNativeRendererService.Stub.asInterface(withTimeout(10_000) { channel.receive() })) { context.unbindService(connection) }
    }
    @Test(timeout = 60_000) fun nativeRendererHasAnIsolatedUidAndItsOwnHardDeadline() = runBlocking<Unit> {
        val original = File.createTempFile("renderer_deadline_", ".pdf", context.cacheDir)
        PDFBoxResourceLoader.init(context)
        PDDocument().use { it.addPage(PDPage()); it.save(original) }
        val snapshot = DocumentStager.stageDocument(context, Uri.fromFile(original))
        val hostPid = Process.myPid()
        val oldPid: Int
        try {
            bind().use { binding ->
                oldPid = binding.renderer.workerPid
                assertNotEquals(hostPid, oldPid)
                assertNotEquals(Process.myUid(), binding.renderer.workerUid)
                val death = CompletableDeferred<Unit>()
                binding.renderer.asBinder().linkToDeath({ death.complete(Unit) }, 0)
                val started = SystemClock.elapsedRealtime()
                val blockedCall = async(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(snapshot.uri, "r")!!.use { source ->
                        try { binding.renderer.debugBlock(source, snapshot.sha256, snapshot.size); fail("Blocked render returned") }
                        catch (_: RemoteException) {}
                    }
                }
                withTimeout(SecurityLimits.RENDER_DEADLINE_MS + 10_000) { death.await() }
                assertTrue(SystemClock.elapsedRealtime() - started <= SecurityLimits.RENDER_DEADLINE_MS + 10_000)
                withTimeout(5_000) { blockedCall.await() }
            }
            assertEquals(hostPid, Process.myPid())
            bind().use { binding ->
                assertNotEquals(oldPid, binding.renderer.workerPid)
                context.contentResolver.openFileDescriptor(snapshot.uri, "r")!!.use { source ->
                    assertEquals(1, binding.renderer.getPageCount(source, snapshot.sha256, snapshot.size))
                }
            }
        } finally { DocumentStager.release(snapshot); original.delete() }
    }
}
