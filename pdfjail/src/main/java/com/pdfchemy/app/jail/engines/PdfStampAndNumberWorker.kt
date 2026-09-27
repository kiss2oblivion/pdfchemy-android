package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfStampAndNumberWorker {
    fun applyWatermark(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val text = params.optString("text", "")
        val rotationDegrees = params.optDouble("rotationDegrees", 45.0).toFloat()
        val opacity = params.optDouble("opacity", 0.3).toFloat()
        val fontSize = params.optDouble("fontSize", 40.0).toFloat()
        val isTiled = params.optBoolean("isTiled", false)

        var doc: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                if (doc == null) return JSONObject().put("success", false).toString()

                val totalPages = doc!!.numberOfPages
                val font = PDType1Font.HELVETICA_BOLD

                for (i in 0 until totalPages) {
                    val page = doc!!.getPage(i)
                    val mediaBox = page.cropBox ?: page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        val gs = PDExtendedGraphicsState()
                        gs.nonStrokingAlphaConstant = opacity
                        cs.setGraphicsStateParameters(gs)

                        if (text.isNotEmpty()) {
                            val textWidth = (font.getStringWidth(text) / 1000f) * fontSize
                            val textHeight = font.fontDescriptor.fontBoundingBox.height / 1000f * fontSize

                            if (isTiled) {
                                val xStep = textWidth * 1.5f
                                val yStep = textHeight * 3.0f

                                for (y in 0..pageHeight.toInt() step yStep.toInt()) {
                                    for (x in 0..pageWidth.toInt() step xStep.toInt()) {
                                        cs.saveGraphicsState()
                                        val rad = Math.toRadians(rotationDegrees.toDouble())
                                        val matrix = Matrix.getRotateInstance(rad, x.toFloat(), y.toFloat())
                                        cs.transform(matrix)

                                        cs.beginText()
                                        cs.setFont(font, fontSize)
                                        cs.setNonStrokingColor(128, 128, 128)
                                        cs.newLineAtOffset(-textWidth * 0.35f, -textHeight * 0.35f)
                                        cs.showText(text)
                                        cs.endText()

                                        cs.restoreGraphicsState()
                                    }
                                }
                            } else {
                                cs.saveGraphicsState()
                                val centerX = pageWidth / 2f
                                val centerY = pageHeight / 2f
                                val rad = Math.toRadians(rotationDegrees.toDouble())
                                val matrix = Matrix.getRotateInstance(rad, centerX, centerY)
                                cs.transform(matrix)

                                cs.beginText()
                                cs.setFont(font, fontSize)
                                cs.setNonStrokingColor(128, 128, 128)
                                cs.newLineAtOffset(-textWidth / 2f, -textHeight / 2f)
                                cs.showText(text)
                                cs.endText()

                                cs.restoreGraphicsState()
                            }
                        }
                    }
                }

                FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                    doc!!.save(outStream)
                }
                return JSONObject().put("success", true).toString()
            }
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            doc?.close()
        }
    }

    fun applyPageNumbers(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val position = params.optString("position", "BOTTOM_CENTER")
        val format = params.optString("format", "PAGE_X_OF_Y")
        val skipFirstPage = params.optBoolean("skipFirstPage", false)
        val fontSize = params.optDouble("fontSize", 10.0).toFloat()
        val marginPts = params.optDouble("marginPts", 36.0).toFloat()

        var doc: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                if (doc == null) return JSONObject().put("success", false).toString()

                val totalPages = doc!!.numberOfPages
                val font = PDType1Font.HELVETICA

                for (i in 0 until totalPages) {
                    if (i == 0 && skipFirstPage) continue

                    val page = doc!!.getPage(i)
                    val mediaBox = page.cropBox ?: page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height

                    val pageNumberText = when (format) {
                        "SIMPLE" -> "${i + 1}"
                        "PAGE_X_OF_Y" -> "Page ${i + 1} of $totalPages"
                        "SLASH" -> "${i + 1} / $totalPages"
                        else -> "Page ${i + 1} of $totalPages"
                    }

                    val textWidth = (font.getStringWidth(pageNumberText) / 1000f) * fontSize
                    val margin = marginPts

                    val (x, y) = when (position) {
                        "TOP_LEFT" -> margin to (pageHeight - margin)
                        "TOP_CENTER" -> ((pageWidth - textWidth) / 2f) to (pageHeight - margin)
                        "TOP_RIGHT" -> (pageWidth - margin - textWidth) to (pageHeight - margin)
                        "BOTTOM_LEFT" -> margin to margin
                        "BOTTOM_CENTER" -> ((pageWidth - textWidth) / 2f) to margin
                        "BOTTOM_RIGHT" -> (pageWidth - margin - textWidth) to margin
                        else -> ((pageWidth - textWidth) / 2f) to margin
                    }

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setNonStrokingColor(80, 80, 80)
                        cs.newLineAtOffset(x, y)
                        cs.showText(pageNumberText)
                        cs.endText()
                    }
                }

                FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                    doc!!.save(outStream)
                }
                return JSONObject().put("success", true).toString()
            }
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            doc?.close()
        }
    }

    fun applyBatesStamping(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val prefix = params.optString("prefix", "")
        val suffix = params.optString("suffix", "")
        val startNumber = params.optInt("startNumber", 1)
        val digits = params.optInt("digits", 6)
        val position = params.optString("position", "BOTTOM_RIGHT")
        val fontSize = params.optDouble("fontSize", 10.0).toFloat()
        val marginPts = params.optDouble("marginPts", 36.0).toFloat()

        var doc: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                if (doc == null) return JSONObject().put("success", false).toString()

                val totalPages = doc!!.numberOfPages
                val font = PDType1Font.HELVETICA

                for (i in 0 until totalPages) {
                    val page = doc!!.getPage(i)
                    val currentNum = startNumber + i
                    val formattedNum = "%0${digits}d".format(currentNum)
                    val batesText = "$prefix$formattedNum$suffix"

                    val textWidth = font.getStringWidth(batesText) / 1000f * fontSize
                    val mediaBox = page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height
                    val margin = marginPts

                    val (x, y) = when (position) {
                        "TOP_LEFT" -> margin to (pageHeight - margin)
                        "TOP_CENTER" -> ((pageWidth - textWidth) / 2f) to (pageHeight - margin)
                        "TOP_RIGHT" -> (pageWidth - margin - textWidth) to (pageHeight - margin)
                        "BOTTOM_LEFT" -> margin to margin
                        "BOTTOM_CENTER" -> ((pageWidth - textWidth) / 2f) to margin
                        "BOTTOM_RIGHT" -> (pageWidth - margin - textWidth) to margin
                        else -> (pageWidth - margin - textWidth) to margin
                    }

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setNonStrokingColor(24, 24, 27)
                        cs.newLineAtOffset(x, y)
                        cs.showText(batesText)
                        cs.endText()
                    }
                }

                FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                    doc!!.save(outStream)
                }
                return JSONObject().put("success", true).toString()
            }
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            doc?.close()
        }
    }
}
