package com.pdfchemy.app.jail.engines

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.pdfchemy.app.logic.*
import com.pdfchemy.app.security.PixelWire
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import org.json.JSONObject

/** Only receiver-owned descriptor URIs are supplied to migrated workers. */
object MigratedEngineDispatch {
    private fun uri(fd: ParcelFileDescriptor?) = Uri.fromFile(java.io.File("/proc/self/fd/${requireNotNull(fd).fd}"))
    private val gson = GsonBuilder().registerTypeAdapter(android.graphics.Bitmap::class.java,
        com.google.gson.JsonSerializer<android.graphics.Bitmap> { _, _, _ -> com.google.gson.JsonNull.INSTANCE }).create()

    suspend fun execute(context: Context, engine: String, source: ParcelFileDescriptor?, target: ParcelFileDescriptor?, extra: ParcelFileDescriptor?, json: String): String {
        val p = JSONObject(json)
        val src by lazy { uri(source) }; val dst by lazy { uri(target) }
        fun rgb(key: String): Triple<Float, Float, Float> {
            val o = p.getJSONObject(key)
            return Triple(o.getDouble("first").toFloat(), o.getDouble("second").toFloat(), o.getDouble("third").toFloat())
        }
        val result: Any? = when (engine) {
            "TEXT_FORMAT" -> TextFormatConverter.convert(p.getString("text"), TextFormatConverter.Format.valueOf(p.getString("from")), TextFormatConverter.Format.valueOf(p.getString("to"))).getOrThrow()
            "LAYOUT_RESIZE" -> PdfLayoutEngine.resizePages(context, src, dst, TargetPaperSize.valueOf(p.getString("size"))).also { check(it) }
            "LAYOUT_NUP" -> PdfLayoutEngine.createNUpLayout(context, src, dst, NUpMode.valueOf(p.getString("mode")), TargetPaperSize.valueOf(p.getString("size")), p.getBoolean("drawBorders")).also { check(it) }
            "HEADER_FOOTER" -> PdfHeaderFooterEngine.applyHeaderFooter(context, src, dst, Gson().fromJson(json, StampConfig::class.java)).getOrThrow()
            "FIND_OCCURRENCES" -> PdfFindAndReplaceEngine.findOccurrences(context, src, p.getString("query"), p.getBoolean("matchCase"))
            "REPLACE_ALL" -> PdfFindAndReplaceEngine.replaceAll(context, src, dst, p.getString("findText"), p.getString("replaceText"), p.getBoolean("matchCase"), rgb("maskColorRgb"), rgb("textColorRgb")).getOrThrow()
            "GRAYSCALE_CONVERT" -> PdfGrayscaleEngine.convertPdf(context, src, dst, GrayscaleMode.valueOf(p.getString("mode")), p.getInt("threshold")).getOrThrow()
            "GRAYSCALE_PREVIEW" -> {
                val bitmap = PdfGrayscaleEngine.generatePreview(context, src, p.getInt("pageIndex"), GrayscaleMode.valueOf(p.getString("mode")), p.getInt("threshold")).getOrThrow()
                try { FileOutputStream(target!!.fileDescriptor).use { PixelWire.write(bitmap, it) } } finally { bitmap.recycle() }
                true
            }
            "TEXT_TO_PDF" -> { TextToPdfConverter.convert(context, p.getString("text"), dst).getOrThrow(); true }
            "MARKDOWN_TO_PDF" -> MarkdownEngine.markdownToPdf(context, p.getString("text"), dst, p.getString("title")).getOrThrow()
            "PDF_TO_MARKDOWN" -> MarkdownEngine.pdfToMarkdown(context, src).getOrThrow()
            "IMAGE_ANALYZE" -> ImageCompressor.analyzeImage(context, src, p.getInt("quality"), ImageOutputFormat.valueOf(p.getString("format")), if (p.isNull("targetBytes")) null else p.getLong("targetBytes"))
            "IMAGE_COMPRESS" -> ImageCompressor.compressImage(context, src, dst, p.getInt("quality"), ImageOutputFormat.valueOf(p.getString("format")), p.getInt("maxDimension"), p.getBoolean("stripExif"))
            "IMAGE_TARGET" -> ImageCompressor.compressToTargetSize(context, src, dst, p.getLong("targetBytes"), ImageOutputFormat.valueOf(p.getString("format")), p.getBoolean("stripExif"))
            "IMAGE_BOUNDS" -> ImageCompressor.decodeImageBounds(context, src)?.let { mapOf("outWidth" to it.outWidth, "outHeight" to it.outHeight) }
            "IMAGE_DECODE" -> {
                val bitmap = SafeImageDecoder.decode(source!!)
                try { FileOutputStream(target!!.fileDescriptor).use { PixelWire.write(bitmap, it) } } finally { bitmap.recycle() }
                true
            }
            "IMAGE_LIST" -> {
                val images = PdfImageReplacerEngine.listEmbeddedImages(context, src)
                SecurityLimits.enforceItemCount(images.size, SecurityLimits.MAX_OUTPUT_FILES)
                FileOutputStream(target!!.fileDescriptor).buffered().use { stream -> images.forEach { PixelWire.write(it.thumbnailBitmap, stream); it.thumbnailBitmap?.recycle() } }
                images
            }
            "IMAGE_REPLACE" -> {
                val bitmap = FileInputStream(extra!!.fileDescriptor).use(PixelWire::read) ?: error("Missing replacement pixels")
                try { PdfImageReplacerEngine.replaceEmbeddedImage(context, src, dst, p.getInt("pageIndex"), p.getString("resourceName"), bitmap, p.getBoolean("isLossless")).getOrThrow() } finally { bitmap.recycle() }
            }
            "TEXT_PAGES" -> FileInputStream(source!!.fileDescriptor).use { input ->
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(input, com.pdfchemy.app.jail.JailMemory.settings()).use(PdfTextExtractorWorker::extractAllPagesText)
            }
            else -> throw IllegalArgumentException("Unknown engine: $engine")
        }
        return gson.toJson(result)
    }
}
