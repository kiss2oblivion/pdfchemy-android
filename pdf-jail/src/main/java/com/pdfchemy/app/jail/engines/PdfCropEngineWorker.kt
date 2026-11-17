package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
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
        require(
            cropLeftParam.isFinite() && cropTopParam.isFinite() &&
                cropRightParam.isFinite() && cropBottomParam.isFinite()
        ) { "Invalid crop parameters" }
        val left = cropLeftParam.coerceIn(0f, 1f)
        val top = cropTopParam.coerceIn(0f, 1f)
        val right = cropRightParam.coerceIn(0f, 1f)
        val bottom = cropBottomParam.coerceIn(0f, 1f)
        require(left < right && top < bottom) { "Invalid crop box bounds" }

        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())
                val pageCount = document!!.numberOfPages
                if (pageCount == 0) return false

                val pagesToCrop = if (targetPageIndex != null) {
                    listOf(targetPageIndex.coerceIn(0, pageCount - 1))
                } else {
                    (0 until pageCount).toList()
                }

                for (idx in pagesToCrop) {
                    val page = document!!.getPage(idx)
                    val baseBox = page.cropBox ?: page.mediaBox ?: PDRectangle(PDRectangle.A4.width, PDRectangle.A4.height)

                    val llx = baseBox.lowerLeftX
                    val lly = baseBox.lowerLeftY
                    val width = baseBox.width
                    val height = baseBox.height
                    val rotation = ((page.rotation % 360) + 360) % 360
                    val (ux0, ux1, uy0, uy1) = when (rotation) {
                        90 -> floatArrayOf(top, bottom, left, right)
                        180 -> floatArrayOf(1f - right, 1f - left, top, bottom)
                        270 -> floatArrayOf(1f - bottom, 1f - top, 1f - right, 1f - left)
                        else -> floatArrayOf(left, right, 1f - bottom, 1f - top)
                    }.let { array ->
                        listOf(array[0], array[1], array[2], array[3])
                    }

                    val cropLeft = llx + (ux0 * width)
                    val cropRight = llx + (ux1 * width)
                    val cropBottomPdf = lly + (uy0 * height)
                    val cropTopPdf = lly + (uy1 * height)

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
