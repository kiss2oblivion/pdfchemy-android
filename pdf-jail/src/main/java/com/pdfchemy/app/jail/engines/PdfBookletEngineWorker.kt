package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.min

object PdfBookletEngineWorker {

    fun generateBooklet(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val paperHeightPts = params.optDouble("paperHeightPts", 842.0).toFloat()
        val paperWidthPts = params.optDouble("paperWidthPts", 595.0).toFloat()
        val drawFoldGuide = params.optBoolean("drawFoldGuide", true)

        var srcDoc: PDDocument? = null
        var outDoc: PDDocument? = null
        var tempFile: File? = null

        try {
            srcDoc = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val origPageCount = srcDoc.numberOfPages
            if (origPageCount == 0) {
                throw IllegalStateException("PDF contains no pages")
            }

            val plan = computeBookletPlan(origPageCount)
            outDoc = PDDocument()
            val layerUtil = LayerUtility(outDoc)

            val sheetWidth = paperHeightPts // Landscape
            val sheetHeight = paperWidthPts
            val sheetRect = PDRectangle(sheetWidth, sheetHeight)

            val margin = 20f
            val halfWidth = (sheetWidth - (margin * 2)) / 2f
            val contentHeight = sheetHeight - (margin * 2)

            for (sidePlan in plan) {
                val sheetPage = PDPage(sheetRect)
                outDoc.addPage(sheetPage)

                PDPageContentStream(outDoc, sheetPage).use { cs ->
                    if (sidePlan.leftPageOriginalIndex != null) {
                        drawVectorPageOnSheet(srcDoc, layerUtil, cs, sidePlan.leftPageOriginalIndex, margin, margin, halfWidth - 8f, contentHeight)
                    }
                    if (sidePlan.rightPageOriginalIndex != null) {
                        drawVectorPageOnSheet(srcDoc, layerUtil, cs, sidePlan.rightPageOriginalIndex, margin + halfWidth + 8f, margin, halfWidth - 8f, contentHeight)
                    }
                    if (drawFoldGuide) {
                        cs.setStrokingColor(210, 210, 210)
                        cs.setLineWidth(0.5f)
                        cs.setLineDashPattern(floatArrayOf(4f, 4f), 0f)
                        cs.moveTo(sheetWidth / 2f, margin)
                        cs.lineTo(sheetWidth / 2f, sheetHeight - margin)
                        cs.stroke()
                    }
                }
            }

            tempFile = File.createTempFile("booklet_", ".pdf")
            outDoc.save(tempFile)
            outDoc.close()
            outDoc = null
            srcDoc.close()
            srcDoc = null

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                tempFile.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            }
            return "{}"
        } finally {
            try { outDoc?.close() } catch (_: Exception) {}
            try { srcDoc?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    private data class PlanEntry(
        val sheetNumber: Int,
        val isFront: Boolean,
        val leftPageOriginalIndex: Int?,
        val rightPageOriginalIndex: Int?
    )

    private fun computeBookletPlan(originalPageCount: Int): List<PlanEntry> {
        val paddedTotal = ceil(originalPageCount / 4.0).toInt() * 4
        val sheetCount = paddedTotal / 4
        val plan = mutableListOf<PlanEntry>()
        for (k in 0 until sheetCount) {
            val frontLeft = paddedTotal - 1 - (2 * k)
            val frontRight = 2 * k
            plan.add(PlanEntry(k + 1, true, if (frontLeft < originalPageCount) frontLeft else null, if (frontRight < originalPageCount) frontRight else null))
            val backLeft = (2 * k) + 1
            val backRight = paddedTotal - 1 - ((2 * k) + 1)
            plan.add(PlanEntry(k + 1, false, if (backLeft < originalPageCount) backLeft else null, if (backRight < originalPageCount) backRight else null))
        }
        return plan
    }

    private fun drawVectorPageOnSheet(
        srcDoc: PDDocument,
        layerUtil: LayerUtility,
        cs: PDPageContentStream,
        pageIndex: Int,
        targetX: Float,
        targetY: Float,
        availWidth: Float,
        availHeight: Float
    ) {
        val srcPage = srcDoc.getPage(pageIndex)
        val srcBox = srcPage.cropBox ?: srcPage.mediaBox
        val srcW = srcBox.width
        val srcH = srcBox.height

        val formXObject = layerUtil.importPageAsForm(srcDoc, pageIndex)
        val scale = min(availWidth / srcW, availHeight / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val drawX = targetX + ((availWidth - drawW) / 2f)
        val drawY = targetY + ((availHeight - drawH) / 2f)

        cs.saveGraphicsState()
        val matrix = Matrix.getTranslateInstance(drawX, drawY)
        matrix.scale(scale, scale)
        cs.transform(matrix)
        cs.drawForm(formXObject)
        cs.restoreGraphicsState()
    }
}
