package com.pdfchemy.app.logic

import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ImportedSigningKeystoreTest {
    @Test fun completeEncryptedInputAtTheLimitIsPreserved() = runBlocking {
        val expected = ByteArray(SignatureEngine.MAX_IMPORTED_KEYSTORE_BYTES) { it.toByte() }
        assertArrayEquals(expected, SignatureEngine.readImportedKeystore(ByteArrayInputStream(expected)))
    }

    @Test fun oversizedInputStopsAfterOneSentinelByte() = runBlocking {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int { consumed++; return 42 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(42, off, off + len)
                consumed += len
                return len
            }
        }
        val failure = runCatching { SignatureEngine.readImportedKeystore(input) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(SignatureEngine.MAX_IMPORTED_KEYSTORE_BYTES + 1, consumed)
    }

    @Test fun emptyAndFailedReadsNeverBecomeAnAbsentIdentity() = runBlocking {
        assertTrue(runCatching {
            SignatureEngine.readImportedKeystore(ByteArrayInputStream(byteArrayOf()))
        }.exceptionOrNull() is IllegalArgumentException)
        val failure = IOException("synthetic read failure")
        val input = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, runCatching { SignatureEngine.readImportedKeystore(input) }.exceptionOrNull())
    }

    @Test fun cancelledImportDoesNotReadTheProvider() = runBlocking {
        var reads = 0
        val input = object : InputStream() { override fun read(): Int { reads++; return -1 } }
        val failure = runCatching {
            coroutineScope {
                currentCoroutineContext().cancel()
                SignatureEngine.readImportedKeystore(input)
            }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(0, reads)
    }
}
