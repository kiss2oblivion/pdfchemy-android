package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max

object PdfCropEngineWorker {
    fun execute(context: Context, sourceFd: ParcelFileDescriptor?, destFd: ParcelFileDescriptor?, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson)
            val left = params.getDouble("left").toFloat()
            val top = params.getDouble("top").toFloat()
            val right = params.getDouble("right").toFloat()
            val bottom = params.getDouble("bottom").toFloat()
            val targetPageIndex = if (params.has("targetPageIndex") && !params.isNull("targetPageIndex")) params.getInt("targetPageIndex") else null

            val success = cropPdf(context, sourceFd!!, destFd!!, left, top, right, bottom, targetPageIndex)
            JSONObject().put("success", success).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    private fun cropPdf(
        context: Context,
        sourceFd: ParcelFileDescriptor,
        destFd: ParcelFileDescriptor,
        cropLeftParam: Float,
        cropTopParam: Float,
        cropRightParam: Float,
        cropBottomParam: Float,
        targetPageIndex: Int?
    ): Boolean {
        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val pageCount = document!!.numberOfPages
                if (pageCount == 0) return false

                val pagesToCrop = if (targetPageIndex != null) {
                    listOf(targetPageIndex.coerceIn(0, pageCount - 1))
                } else {
                    (0 until pageCount).toList()
                }

                for (idx in pagesToCrop) {
                    val page = document!!.getPage(idx)
                    val mediaBox = page.mediaBox ?: PDRectangle(PDRectangle.A4.width, PDRectangle.A4.height)

                    val llx = mediaBox.lowerLeftX
                    val lly = mediaBox.lowerLeftY
                    val width = mediaBox.width
                    val height = mediaBox.height

                    val cropLeft = llx + (cropLeftParam * width)
                    val cropRight = llx + (cropRightParam * width)
                    val cropTopPdf = lly + ((1f - cropTopParam) * height)
                    val cropBottomPdf = lly + ((1f - cropBottomParam) * height)

                    val newCropBox = PDRectangle(
                        cropLeft,
                        cropBottomPdf,
                        max(10f, cropRight - cropLeft),
                        max(10f, cropTopPdf - cropBottomPdf)
                    )

                    page.cropBox = newCropBox
                }

                FileOutputStream(destFd.fileDescriptor).use { destStream ->
                    document!!.save(destStream)
                }
                return true
            }
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }
}
