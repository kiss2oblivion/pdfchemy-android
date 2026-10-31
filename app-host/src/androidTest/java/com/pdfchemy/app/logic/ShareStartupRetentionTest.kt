package com.pdfchemy.app.logic

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.MainActivity
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual startup cleanup and FileProvider readback, including the table-share naming convention. */
@RunWith(AndroidJUnit4::class)
class ShareStartupRetentionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val payload = "column\nrecipient copy\n".toByteArray()
    private val sandbox = File(context.cacheDir, "exports/startup_retention_${UUID.randomUUID()}").apply { mkdirs() }
    private val cleanupContext = object : ContextWrapper(context) {
        override fun getCacheDir(): File = sandbox
    }

    @After fun removeOnlyThisFixture() {
        check(sandbox.canonicalFile.parentFile == File(context.cacheDir, "exports").canonicalFile)
        check(sandbox.deleteRecursively())
    }

    private fun cleanupOnStartup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("cleanupOrphanedCacheFiles", Context::class.java)
                .apply { isAccessible = true }.invoke(MainActivity(), cleanupContext)
        }
    }

    private fun retainedCopy(): File = File(File(sandbox, "exports").apply { mkdirs() },
        "shared_table_${UUID.randomUUID()}.csv").apply {
        writeBytes(payload)
        check(setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))
    }

    @Test fun completedShareCanBeReopenedAfterStartupCleanup() {
        val copy = retainedCopy()
        val unrelated = File(sandbox, "obsolete_${UUID.randomUUID()}.csv").apply {
            writeText("ordinary temporary output")
            check(setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(payload, it.readBytes()) }
            cleanupOnStartup()
            assertTrue("Completed recipient copies must not expire automatically", copy.exists())
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(payload, it.readBytes()) }
            assertFalse("Ordinary stale temporary outputs should still be reclaimed", unrelated.exists())
        } finally { copy.delete(); unrelated.delete() }
    }

    @Test fun overlappingReadersAndLaterReopenSurviveStartupCleanup() {
        val copy = retainedCopy()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
            val first = context.contentResolver.openFileDescriptor(uri, "r")!!
            val second = context.contentResolver.openFileDescriptor(uri, "r")!!
            try {
                first.close()
                cleanupOnStartup()
                assertTrue("Startup must preserve a shared file while a recipient still reads", copy.exists())
                ParcelFileDescriptor.AutoCloseInputStream(second.dup()).use { assertArrayEquals(payload, it.readBytes()) }
                context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(payload, it.readBytes()) }
            } finally { first.close(); second.close() }
        } finally { copy.delete() }
    }
}
