package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.HostOutputTransaction
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises completed host snapshots and actual publication, not just a CSV model. */
@RunWith(AndroidJUnit4::class)
class CsvPublicationBoundaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private suspend fun write(transaction: HostOutputTransaction, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val fd = transaction.workerDescriptors.single().fileDescriptor
        var offset = 0
        while (offset < bytes.size) {
            val written = Os.write(fd, bytes, offset, minOf(8192, bytes.size - offset))
            check(written > 0); offset += written
        }
    }
    private suspend fun rejected(bytes: ByteArray, message: String? = null) {
        val target = File.createTempFile("csv_existing_", ".csv", context.cacheDir)
        val old = "existing destination bytes".toByteArray()
        target.writeBytes(old)
        try {
            HostOutputTransaction(context, listOf(Uri.fromFile(target))).use { transaction ->
                write(transaction, bytes)
                val error = runCatching { transaction.validateAndSnapshot("true", "TABLE_EXTRACT") }.exceptionOrNull()
                assertTrue(error.toString(), error is IllegalArgumentException)
                if (message != null) assertTrue(error!!.message.orEmpty().contains(message))
                assertTrue("A rejected snapshot cannot be committed", runCatching { transaction.commit() }.isFailure)
            }
            assertArrayEquals(old, target.readBytes())
        } finally { target.delete() }
    }
    @Test fun malformedCsvSnapshotsCannotReplaceExistingDestination() = runBlocking {
        for (csv in listOf("\"unclosed", "\"ok\"stray", "bare\"quote", "\"a\n" + "b".repeat(1024) + "\"")) {
            rejected(csv.toByteArray())
        }
    }
    @Test fun oversizedCsvSnapshotFailsBeforePublication() = runBlocking {
        rejected(ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() }, "CSV file exceeds limit")
    }
    @Test fun validMultilineAndEscapedQuotesPublishExactBytes() = runBlocking {
        val csv = "header,notes\r\nitem,\"first\r\nsecond, \"\"quoted\"\"\"\r\n"
        val target = File.createTempFile("csv_valid_", ".csv", context.cacheDir)
        try {
            HostOutputTransaction(context, listOf(Uri.fromFile(target))).use {
                write(it, csv.toByteArray())
                it.validateAndSnapshot("true", "TABLE_EXTRACT")
                it.commit()
            }
            assertEquals(csv, target.readText())
        } finally { target.delete() }
    }
}

