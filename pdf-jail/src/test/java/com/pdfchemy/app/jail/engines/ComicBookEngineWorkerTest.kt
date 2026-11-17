package com.pdfchemy.app.jail.engines

import org.junit.Assert.*
import org.junit.Test

class ComicBookEngineWorkerTest {
    private fun rejection(header:ByteArray,label:String) {
        val error=assertThrows(IllegalArgumentException::class.java) {ComicBookEngineWorker.requireSupportedArchive(header)}
        assertTrue(error.message!!.contains(label));assertTrue(error.message!!.contains("ZIP (.cbz)"))
    }
    @Test fun rar4HasActionableConversionGuidance() {
        rejection(byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0),"RAR")
    }
    @Test fun rar5HasActionableConversionGuidance() {
        rejection(byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,1,0),"RAR")
    }
    @Test fun sevenZipHasActionableConversionGuidance() {
        rejection(byteArrayOf(0x37,0x7a,0xbc.toByte(),0xaf.toByte(),0x27,0x1c),"7-Zip")
    }
    @Test fun zipAndShortOrUnrelatedHeadersAreNotMisclassified() {
        for(header in listOf(byteArrayOf(0x50,0x4b,3,4),byteArrayOf(0x52,0x61,0x72,0x21),byteArrayOf(),ByteArray(8))) {
            ComicBookEngineWorker.requireSupportedArchive(header)
        }
    }
}
