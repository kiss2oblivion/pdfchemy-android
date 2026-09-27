package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.min

object PdfNUpEngineWorker {

    fun generateNUpPdf(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        var srcDoc: PDDocument? = null
        var outDoc: PDDocument? = null

        try {
            val params = JSONObject(paramsJson)
            val layoutCols = params.optInt("layoutCols", 2)
            val layoutRows = params.optInt("layoutRows", 2)
            val pagesPerSheet = params.optInt("pagesPerSheet", 4)
            val isLandscapeDefault = params.optBoolean("isLandscapeDefault", true)
            
            val orderHorizontal = params.optBoolean("orderHorizontal", true)
            val paperWidthPts = params.optDouble("paperWidthPts", 595.276f.toDouble()).toFloat()
            val paperHeightPts = params.optDouble("paperHeightPts", 841.89f.toDouble()).toFloat()
            
            val drawBorders = params.optBoolean("drawBorders", true)
            val marginPt = params.optDouble("marginPt", 24.0).toFloat()
            val spacingPt = params.optDouble("spacingPt", 12.0).toFloat()

            srcDoc = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val origPageCount = srcDoc.numberOfPages
            if (origPageCount == 0) {
                return JSONObject().put("success", false).put("error", "PDF contains no pages").toString()
            }

            outDoc = PDDocument()
            val layerUtil = LayerUtility(outDoc)

            val totalSheets = ceil(origPageCount.toDouble() / pagesPerSheet.toDouble()).toInt()

            val sheetW = if (isLandscapeDefault) paperHeightPts else paperWidthPts
            val sheetH = if (isLandscapeDefault) paperWidthPts else paperHeightPts
            val sheetRect = PDRectangle(sheetW, sheetH)

            val availW = sheetW - (marginPt * 2) - (spacingPt * (layoutCols - 1))
            val availH = sheetH - (marginPt * 2) - (spacingPt * (layoutRows - 1))
            val cellW = availW / layoutCols
            val cellH = availH / layoutRows

            for (sheetIdx in 0 until totalSheets) {
                val sheetPage = PDPage(sheetRect)
                outDoc.addPage(sheetPage)

                PDPageContentStream(outDoc, sheetPage).use { cs ->
                    for (slotIdx in 0 until pagesPerSheet) {
                        val (col, row) = if (orderHorizontal) {
                            val c = slotIdx % layoutCols
                            val r = slotIdx / layoutCols
                            c to (layoutRows - 1 - r)
                        } else {
                            val r = slotIdx % layoutRows
                            val c = slotIdx / layoutRows
                            c to (layoutRows - 1 - r)
                        }

                        val srcPageIdx = (sheetIdx * pagesPerSheet) + slotIdx
                        if (srcPageIdx < origPageCount) {
                            val cellX = marginPt + (col * (cellW + spacingPt))
                            val cellY = marginPt + (row * (cellH + spacingPt))

                            drawPageInCell(
                                srcDoc = srcDoc,
                                layerUtil = layerUtil,
                                cs = cs,
                                pageIndex = srcPageIdx,
                                cellX = cellX,
                                cellY = cellY,
                                cellW = cellW,
                                cellH = cellH,
                                drawBorder = drawBorders
                            )
                        }
                    }
                }
            }

            FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                outDoc.save(outStream)
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { outDoc?.close() } catch (e: Exception) {}
            try { srcDoc?.close() } catch (e: Exception) {}
        }
    }

    private fun drawPageInCell(
        srcDoc: PDDocument,
        layerUtil: LayerUtility,
        cs: PDPageContentStream,
        pageIndex: Int,
        cellX: Float,
        cellY: Float,
        cellW: Float,
        cellH: Float,
        drawBorder: Boolean
    ) {
        val srcPage = srcDoc.getPage(pageIndex)
        val srcBox = srcPage.cropBox ?: srcPage.mediaBox
        val srcW = srcBox.width
        val srcH = srcBox.height

        val formXObject = layerUtil.importPageAsForm(srcDoc, pageIndex)
        val scale = min(cellW / srcW, cellH / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val drawX = cellX + ((cellW - drawW) / 2f)
        val drawY = cellY + ((cellH - drawH) / 2f)

        cs.saveGraphicsState()
        val matrix = Matrix.getTranslateInstance(drawX, drawY)
        matrix.scale(scale, scale)
        cs.transform(matrix)
        cs.drawForm(formXObject)
        cs.restoreGraphicsState()

        if (drawBorder) {
            cs.saveGraphicsState()
            cs.setStrokingColor(200, 205, 215)
            cs.setLineWidth(0.6f)
            cs.addRect(drawX, drawY, drawW, drawH)
            cs.stroke()
            cs.restoreGraphicsState()
        }
    }
}
