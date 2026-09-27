package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.Writer

data class TextMatchOccurrence(
    val pageIndex: Int,
    val matchedText: String,
    val snippet: String,
    val bounds: RectF, // In PDF page coordinate space (origin at bottom-left)
    val fontSize: Float
)

data class FindReplaceSummary(
    val totalMatches: Int,
    val pagesAffected: Int,
    val occurrences: List<TextMatchOccurrence>
)

object PdfFindAndReplaceEngine {

    /**
     * Custom text stripper that captures character bounding boxes and locates query occurrences.
     */
    private class PositionalSearchStripper(
        private val query: String,
        private val matchCase: Boolean
    ) : PDFTextStripper() {

        val matches = mutableListOf<TextMatchOccurrence>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val currentPage = (currentPageNo - 1).coerceAtLeast(0)
            val line = text
            val target = if (matchCase) query else query.lowercase()
            val source = if (matchCase) line else line.lowercase()

            var startIndex = 0
            while (startIndex < source.length) {
                val foundIndex = source.indexOf(target, startIndex)
                if (foundIndex == -1) break

                val endIndex = foundIndex + target.length
                if (endIndex <= textPositions.size) {
                    val matchPositions = textPositions.subList(foundIndex, endIndex)
                    if (matchPositions.isNotEmpty()) {
                        val firstPos = matchPositions.first()
                        val lastPos = matchPositions.last()

                        val minX = firstPos.xDirAdj
                        val maxX = lastPos.xDirAdj + lastPos.widthDirAdj
                        val width = (maxX - minX).coerceAtLeast(1f)
                        val avgHeight = matchPositions.map { it.heightDir }.average().toFloat().coerceAtLeast(8f)
                        val avgFontSize = matchPositions.map { it.fontSizeInPt }.average().toFloat().coerceAtLeast(10f)

                        // yDirAdj is measured from top of page. We will compute standard PDF coordinate (bottom-left) when applying.
                        val topY = firstPos.yDirAdj

                        val snippetStart = (foundIndex - 20).coerceAtLeast(0)
                        val snippetEnd = (endIndex + 20).coerceAtMost(line.length)
                        val snippet = line.substring(snippetStart, snippetEnd).trim()

                        matches.add(
                            TextMatchOccurrence(
                                pageIndex = currentPage,
                                matchedText = line.substring(foundIndex, endIndex),
                                snippet = "...$snippet...",
                                bounds = RectF(minX, topY, minX + width, topY + avgHeight),
                                fontSize = avgFontSize
                            )
                        )
                    }
                }
                startIndex = foundIndex + 1
            }
        }
    }

    /**
     * Finds all text occurrences across all pages of a PDF.
     */
    suspend fun findOccurrences(
        context: Context,
        pdfUri: Uri,
        query: String,
        matchCase: Boolean = false
    ): FindReplaceSummary = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext FindReplaceSummary(0, 0, emptyList())

        PDFBoxResourceLoader.init(context)
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        val allMatches = mutableListOf<TextMatchOccurrence>()

        try {
            inputStream = context.contentResolver.openInputStream(pdfUri)
                ?: return@withContext FindReplaceSummary(0, 0, emptyList())
            document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            val stripper = PositionalSearchStripper(query, matchCase)
            val nullWriter = OutputStreamWriter(ByteArrayOutputStream())
            stripper.startPage = 1
            stripper.endPage = document.numberOfPages
            stripper.writeText(document, nullWriter)
            allMatches.addAll(stripper.matches)
        } catch (e: Exception) {
            AppLogger.e("PdfFindAndReplaceEngine: Error finding text occurrences", e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
        }

        val pagesAffected = allMatches.map { it.pageIndex }.distinct().size
        return@withContext FindReplaceSummary(allMatches.size, pagesAffected, allMatches)
    }

    /**
     * Replaces occurrences of findText with replaceText across the PDF and writes output to destUri.
     */
    suspend fun replaceAll(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        findText: String,
        replaceText: String,
        matchCase: Boolean = false,
        maskColorRgb: Triple<Float, Float, Float> = Triple(1f, 1f, 1f), // Pure white mask by default
        textColorRgb: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)  // Black text by default
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (findText.isBlank()) return@withContext Result.failure(IllegalArgumentException("Search query cannot be blank"))

        PDFBoxResourceLoader.init(context)
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        var tempFile: File? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null

        return@withContext try {
            pfd = try { com.pdfchemy.app.utils.FileUtils.openParcelFileDescriptor(context, sourcePdfUri) } catch (_: Exception) { null }
            if (pfd != null) {
                renderer = try { android.graphics.pdf.PdfRenderer(pfd) } catch (_: Exception) { null }
            }

            inputStream = context.contentResolver.openInputStream(sourcePdfUri)
                ?: throw IllegalArgumentException("Cannot open source PDF")
            document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            val stripper = PositionalSearchStripper(findText, matchCase)
            val nullWriter = OutputStreamWriter(ByteArrayOutputStream())
            stripper.startPage = 1
            stripper.endPage = document.numberOfPages
            stripper.writeText(document, nullWriter)

            val matchesByPage = stripper.matches.groupBy { it.pageIndex }
            var totalReplaced = 0

            for ((pageIndex, pageMatches) in matchesByPage) {
                if (pageIndex !in 0 until document.numberOfPages) continue
                val page = document.getPage(pageIndex)
                val cropBox = page.cropBox ?: page.mediaBox
                var rasterized = false

                if (renderer != null && pageIndex in 0 until renderer.pageCount) {
                    var renderPage: android.graphics.pdf.PdfRenderer.Page? = null
                    var baseBmp: android.graphics.Bitmap? = null
                    try {
                        renderPage = renderer.openPage(pageIndex)
                        val origW = renderPage.width.coerceAtLeast(1)
                        val origH = renderPage.height.coerceAtLeast(1)
                        val maxDim = 2400f
                        val scale = (maxDim / maxOf(origW, origH)).coerceIn(1.5f, 2.5f)
                        val targetW = (origW * scale).toInt().coerceAtLeast(1)
                        val targetH = (origH * scale).toInt().coerceAtLeast(1)

                        baseBmp = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(baseBmp)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        renderPage.render(baseBmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val maskPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.rgb(
                                (maskColorRgb.first * 255f).toInt().coerceIn(0, 255),
                                (maskColorRgb.second * 255f).toInt().coerceIn(0, 255),
                                (maskColorRgb.third * 255f).toInt().coerceIn(0, 255)
                            )
                            style = android.graphics.Paint.Style.FILL
                        }

                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(
                                (textColorRgb.first * 255f).toInt().coerceIn(0, 255),
                                (textColorRgb.second * 255f).toInt().coerceIn(0, 255),
                                (textColorRgb.third * 255f).toInt().coerceIn(0, 255)
                            )
                            isFakeBoldText = true
                        }

                        for (match in pageMatches) {
                            val normLeft = (match.bounds.left / origW.toFloat()).coerceIn(0f, 1f)
                            val normTop = (match.bounds.top / origH.toFloat()).coerceIn(0f, 1f)
                            val normWidth = (match.bounds.width() / origW.toFloat()).coerceIn(0.001f, 1f)
                            val normHeight = (match.bounds.height() / origH.toFloat()).coerceIn(0.001f, 1f)

                            val pxLeft = normLeft * targetW
                            val pxTop = normTop * targetH
                            val pxWidth = normWidth * targetW
                            val pxHeight = normHeight * targetH

                            // 1. Mask out old text completely from raster canvas
                            canvas.drawRect(pxLeft - 2f, pxTop - 1f, pxLeft + pxWidth + 2f, pxTop + pxHeight + 2f, maskPaint)

                            // 2. Draw replacement text with native Android font support (handles all Unicode/locales)
                            if (replaceText.isNotEmpty()) {
                                textPaint.textSize = (match.fontSize * scale * 1.05f).coerceAtLeast(10f)
                                val baselineY = pxTop + (pxHeight * 0.82f)
                                canvas.drawText(replaceText, pxLeft, baselineY, textPaint)
                            }
                            totalReplaced++
                        }

                        val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(document, baseBmp, 0.92f)
                        baseBmp.recycle()
                        baseBmp = null

                        // Replace page content stream and clear annotations to completely destroy original text
                        page.rotation = 0
                        page.cropBox = null
                        page.mediaBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(origW.toFloat(), origH.toFloat())
                        page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.ANNOTS)

                        PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false).use { cs ->
                            cs.drawImage(pdImage, 0f, 0f, origW.toFloat(), origH.toFloat())

                            // 3. Inject transparent selectable text layer for searchability
                            if (replaceText.isNotEmpty()) {
                                val extGState = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                                    nonStrokingAlphaConstant = 0.0f
                                }
                                cs.setGraphicsStateParameters(extGState)
                                val winAnsiText = sanitizeForWinAnsi(replaceText)
                                if (winAnsiText.isNotBlank()) {
                                    for (match in pageMatches) {
                                        try {
                                            cs.beginText()
                                            cs.setFont(PDType1Font.HELVETICA, match.fontSize)
                                            val pdfX = match.bounds.left
                                            val pdfY = origH.toFloat() - match.bounds.bottom
                                            cs.newLineAtOffset(pdfX, pdfY)
                                            cs.showText(winAnsiText)
                                            cs.endText()
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                        rasterized = true
                    } catch (e: Exception) {
                        AppLogger.w("PdfFindAndReplaceEngine: raster flattening failed for page $pageIndex: ${e.message}")
                    } finally {
                        try { renderPage?.close() } catch (_: Exception) {}
                        try { baseBmp?.recycle() } catch (_: Exception) {}
                    }
                }

                if (!rasterized) {
                    throw SecurityException("Cannot forensically rasterize the PDF page because PdfRenderer is unavailable. Aborting find and replace to ensure maximum security without data loss.")
                }
            }

            tempFile = File(context.cacheDir, "find_replace_tmp_${System.currentTimeMillis()}.pdf")
            FileOutputStream(tempFile).use { out ->
                document.save(out)
            }

            context.contentResolver.openOutputStream(destPdfUri)?.use { destStream ->
                tempFile.inputStream().use { tempIn ->
                    tempIn.copyTo(destStream)
                }
            } ?: throw IllegalStateException("Cannot open destination output stream")

            tempFile.delete()
            tempFile = null

            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "replaced.pdf", "Find & Replace PDF")

            Result.success(totalReplaced)
        } catch (e: Exception) {
            AppLogger.e("PdfFindAndReplaceEngine: Error replacing text", e)
            Result.failure(e)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    private fun sanitizeForWinAnsi(text: String): String {
        // Expand common Latin ligatures and special characters
        val expanded = text
            .replace("ß", "ss").replace("ẞ", "SS")
            .replace("æ", "ae").replace("Æ", "AE")
            .replace("œ", "oe").replace("Œ", "OE")
            .replace("ø", "o").replace("Ø", "O")
            .replace("đ", "d").replace("Đ", "D")
            .replace("ł", "l").replace("Ł", "L")

        // Decompose all diacritics to base letters + combining marks, then strip combining marks
        val normalized = java.text.Normalizer.normalize(expanded, java.text.Normalizer.Form.NFD)
        val stripped = normalized.replace("\\p{M}+".toRegex(), "")

        val sb = StringBuilder()
        for (ch in stripped) {
            when {
                ch.code in 32..126 -> sb.append(ch)
                ch == '‘' || ch == '’' -> sb.append('\'')
                ch == '“' || ch == '”' -> sb.append('"')
                ch == '—' || ch == '–' -> sb.append('-')
                ch == '…' -> sb.append("...")
                ch == ' ' -> sb.append(' ')
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                else -> {
                    // Skip unsupported glyphs for Type 1 font to avoid IllegalArgumentException
                }
            }
        }
        return sb.toString()
    }
}
