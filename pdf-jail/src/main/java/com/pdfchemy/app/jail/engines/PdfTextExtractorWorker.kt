package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.pdfchemy.app.security.*
import kotlinx.coroutines.runBlocking

object PdfTextExtractorWorker {
    private class LimitedWriter : java.io.Writer() {
        val text = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            require(length <= SecurityLimits.MAX_TEXT_BYTES - text.length) { "Extracted text quota exceeded" }
            text.append(chars, offset, length)
        }
        override fun flush() {}
        override fun close() {}
    }
    fun extractAllPagesText(document: PDDocument): List<String> {
        require(document.numberOfPages <= SecurityLimits.MAX_OUTPUT_FILES) { "Page count quota exceeded" }
        val pages = mutableListOf<String>()
        var totalBytes = 0
        val writer = LimitedWriter()
        val stripper = object : PDFTextStripper() {
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                super.endPage(page)
                val text = writer.text.toString()
                totalBytes += text.toByteArray(Charsets.UTF_8).size
                require(totalBytes <= SecurityLimits.MAX_TEXT_BYTES) { "Extracted text quota exceeded" }
                pages.add(text)
                writer.text.setLength(0)
            }
        }
        stripper.writeText(document, writer)
        return pages
    }
    fun extractText(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor, paramsJson: String): String {
        val force = org.json.JSONObject(paramsJson).optBoolean("forceOcr", false)
        val text = if (force) extractUsingOcr(context, sourceFd) else com.pdfchemy.app.jail.capabilityInput(sourceFd.fileDescriptor).use { input ->
            PDDocument.load(input, com.pdfchemy.app.jail.JailMemory.settings()).use { extractAllPagesText(it).joinToString("\n\n") }
        }
        val result = if (!force && text.trim().length < 50) extractUsingOcr(context, sourceFd) else text
        SecurityLimits.enforceStringLength(result)
        com.pdfchemy.app.jail.boundedFileOutput(destFd.fileDescriptor).use { BoundedOutputStream(it).write(result.toByteArray(Charsets.UTF_8)) }
        return org.json.JSONObject().put("success", true).put("textLength", result.length).toString()
    }
    fun extractUsingOcr(context: Context, fd: ParcelFileDescriptor): String = runBlocking {
        android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
        val ocrBackend = com.pdfchemy.app.jail.ocr.OcrBackendFactory.create(context)
        val result = StringBuilder()
        try {
            PdfRenderer(fd.dup()).use { renderer ->
                require(renderer.pageCount <= SecurityLimits.MAX_OUTPUT_FILES)
                for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                    val (w, h) = SecurityLimits.safeRenderSize(page.width.toDouble(), page.height.toDouble())
                    val bitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(w, h); Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
                    try {
                        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = ocrBackend.recognize(bitmap).text
                        require(text.length + result.length <= SecurityLimits.MAX_TEXT_BYTES)
                        result.append(text).append("\n")
                    } finally { bitmap.recycle() }
                }
            }
            result.toString().also { SecurityLimits.enforceStringLength(it) }
        } finally { ocrBackend.close() }
    }
}
