package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.sandbox.JailNativeRendererCoordinator
import com.pdfchemy.app.logic.RedactionBox
import com.pdfchemy.app.logic.RedactionConfig
import com.pdfchemy.app.jail.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream

object PdfRedactionEngineWorker {
    fun execute(context: Context, sourceFd: ParcelFileDescriptor?, destFd: ParcelFileDescriptor?, paramsJson: String, rendererBinder: android.os.IBinder? = null): String {
        return try {
            val params = JSONObject(paramsJson)
            val configObj = params.getJSONObject("config")
            val config = RedactionConfig(
                isBlackout = configObj.optBoolean("isBlackout", true),
                defaultOverlayText = configObj.optString("defaultOverlayText", ""),
                forensicSanitize = configObj.optBoolean("forensicSanitize", true)
            )
            
            val boxesArr = params.getJSONArray("boxes")
            val boxes = mutableListOf<RedactionBox>()
            for (i in 0 until boxesArr.length()) {
                val box = boxesArr.getJSONObject(i)
                boxes.add(RedactionBox(
                    pageIndex = box.getInt("pageIndex"),
                    xRatio = box.getDouble("xRatio").toFloat(),
                    yRatio = box.getDouble("yRatio").toFloat(),
                    widthRatio = box.getDouble("widthRatio").toFloat(),
                    heightRatio = box.getDouble("heightRatio").toFloat()
                ))
            }

            val redactedCount = runBlocking {
                applyRedactions(context, sourceFd!!, destFd!!, boxes, config, rendererBinder)
            }
            JSONObject().put("success", true).put("count", redactedCount).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    internal class PositionalSearchStripper(
        private val query: String,
        private val isRegex: Boolean
    ) : PDFTextStripper() {

        init {
            sortByPosition = true
        }

        val matches = mutableListOf<JSONObject>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val currentPage = (currentPageNo - 1).coerceAtLeast(0)
            if (textPositions.isEmpty()) return

            val charToPosition = ArrayList<TextPosition>(text.length + 16)
            val fullTextBuilder = StringBuilder(text.length + 16)
            for (tp in textPositions) {
                val unicode = tp.unicode.orEmpty()
                if (unicode.isNotEmpty()) {
                    for (ch in unicode) {
                        charToPosition.add(tp)
                        fullTextBuilder.append(ch)
                    }
                }
            }
            val line = fullTextBuilder.toString()
            if (line.isEmpty() || charToPosition.isEmpty()) return

            if (isRegex) {
                val regex = Regex(query)
                val results = regex.findAll(line)
                for (match in results) {
                    val start = match.range.first
                    val end = match.range.last + 1
                    if (start in 0 until charToPosition.size && end <= charToPosition.size) {
                        val matchedPositions = (start until end).map { charToPosition[it] }.distinct()
                        addMatch(currentPage, matchedPositions)
                    }
                }
            } else {
                var startIndex = 0
                val target = query.lowercase()
                val source = line.lowercase()
                while (startIndex < source.length) {
                    val foundIndex = source.indexOf(target, startIndex)
                    if (foundIndex == -1) break
                    val endIndex = foundIndex + target.length
                    if (foundIndex in 0 until charToPosition.size && endIndex <= charToPosition.size) {
                        val matchedPositions = (foundIndex until endIndex).map { charToPosition[it] }.distinct()
                        addMatch(currentPage, matchedPositions)
                    }
                    startIndex = foundIndex + 1
                }
            }
        }

        private fun addMatch(currentPage: Int, matchPositions: List<TextPosition>) {
            if (matchPositions.isNotEmpty()) {
                val minX = matchPositions.minOf { it.xDirAdj } - 1f
                val maxX = matchPositions.maxOf { it.xDirAdj + it.widthDirAdj } + 1f
                val width = (maxX - minX).coerceAtLeast(1f)
                // yDirAdj is the baseline, not the top of the glyph. Include
                // ascenders/descenders with conservative padding before rotating.
                val minY = matchPositions.minOf { it.yDirAdj - it.heightDir * 1.5f } - 1f
                val maxY = matchPositions.maxOf { it.yDirAdj + it.heightDir * 0.5f } + 1f
                val height = (maxY - minY).coerceAtLeast(8f)

                val boxObj = JSONObject()
                boxObj.put("pageIndex", currentPage)
                boxObj.put("rawLeft", minX.toDouble())
                boxObj.put("rawTop", minY.toDouble())
                boxObj.put("rawRight", (minX + width).toDouble())
                boxObj.put("rawBottom", (minY + height).toDouble())
                matches.add(boxObj)
            }
        }
    }

    fun searchTargets(context: Context, sourceFd: ParcelFileDescriptor, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson)
            val query = params.getString("query")
            val isRegex = params.optBoolean("isRegex", false)

            PDFBoxResourceLoader.init(context)
            var document: PDDocument? = null

            val resultMatches = mutableListOf<JSONObject>()
            try {
                FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                    document = PDDocument.load(inStream, com.pdfchemy.app.jail.JailMemory.settings())
                }

                resultMatches.addAll(searchDocumentTargets(document!!, query, isRegex))
            } finally {
                try { document?.close() } catch (e: Exception) {}
            }

            val resultObj = JSONObject()
            resultObj.put("success", true)
            val boxesArr = org.json.JSONArray()
            for (m in resultMatches) boxesArr.put(m)
            resultObj.put("boxes", boxesArr)
            
            resultObj.toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    internal fun searchDocumentTargets(document: PDDocument, query: String, isRegex: Boolean): List<JSONObject> {
        val stripper = PositionalSearchStripper(query, isRegex)
        stripper.sortByPosition = true
        val nullWriter = java.io.OutputStreamWriter(java.io.ByteArrayOutputStream())
        stripper.startPage = 1
        stripper.endPage = document.numberOfPages
        stripper.writeText(document, nullWriter)

        val resultMatches = mutableListOf<JSONObject>()
        for (match in stripper.matches) {
            val pageIndex = match.getInt("pageIndex")
            val page = document.getPage(pageIndex)
            val cropBox = page.cropBox ?: page.mediaBox
            val pw = cropBox.width
            val ph = cropBox.height

            val rawLeft = match.getDouble("rawLeft").toFloat()
            val rawTop = match.getDouble("rawTop").toFloat()
            val rawRight = match.getDouble("rawRight").toFloat()
            val rawBottom = match.getDouble("rawBottom").toFloat()

            val normLeft = (minOf(rawLeft, rawRight) / pw).coerceIn(0f, 1f)
            val normTop = (minOf(rawTop, rawBottom) / ph).coerceIn(0f, 1f)
            val normRight = (maxOf(rawLeft, rawRight) / pw).coerceIn(normLeft, 1f)
            val normBottom = (maxOf(rawTop, rawBottom) / ph).coerceIn(normTop, 1f)

            val boxObj = JSONObject()
            boxObj.put("pageIndex", pageIndex)
            // TextPosition's direction-adjusted coordinates are in the unrotated
            // crop plane; review/export boxes use the displayed rotated plane.
            val bounds = when (((page.rotation % 360) + 360) % 360) {
                90 -> floatArrayOf(1f - normBottom, normLeft, 1f - normTop, normRight)
                180 -> floatArrayOf(1f - normRight, 1f - normBottom, 1f - normLeft, 1f - normTop)
                270 -> floatArrayOf(normTop, 1f - normRight, normBottom, 1f - normLeft)
                else -> floatArrayOf(normLeft, normTop, normRight, normBottom)
            }
            boxObj.put("left", bounds[0].toDouble())
            boxObj.put("top", bounds[1].toDouble())
            boxObj.put("right", bounds[2].toDouble())
            boxObj.put("bottom", bounds[3].toDouble())
            boxObj.put("overlayLabel", "REDACTED")

            resultMatches.add(boxObj)
        }
        return resultMatches
    }

    private suspend fun applyRedactions(
        context: Context,
        sourceFd: ParcelFileDescriptor,
        destFd: ParcelFileDescriptor,
        boxes: List<RedactionBox>,
        config: RedactionConfig,
        rendererBinder: android.os.IBinder?
    ): Int {
        if (boxes.isEmpty()) {
            FileInputStream(sourceFd.fileDescriptor).use { input ->
                FileOutputStream(destFd.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            return 0
        }

        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null

        try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                document = PDDocument.load(inStream, com.pdfchemy.app.jail.JailMemory.settings())
            }
            val pageCount = document!!.numberOfPages
            if (pageCount == 0) return 0

            val font = PDType1Font.HELVETICA_BOLD
            val boxesByPage = boxes.groupBy { it.pageIndex }
            if (config.forensicSanitize) RedactionResources.prepareForRedaction(document!!)

            for ((pageIdx, pageBoxes) in boxesByPage) {
                if (pageIdx < 0 || pageIdx >= pageCount) continue

                val page = document!!.getPage(pageIdx)
                val cropBox = page.cropBox ?: page.mediaBox
                val pw = cropBox.width
                val ph = cropBox.height
                val rot = ((page.rotation % 360) + 360) % 360

                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (box in pageBoxes) {
                        val drawX = when (rot) {
                            90 -> cropBox.lowerLeftX + box.yRatio.coerceIn(0f, 1f) * pw
                            180 -> cropBox.lowerLeftX + pw - (box.xRatio.coerceIn(0f, 1f) + box.widthRatio.coerceIn(0.01f, 1f)) * pw
                            270 -> cropBox.lowerLeftX + pw - (box.yRatio.coerceIn(0f, 1f) + box.heightRatio.coerceIn(0.01f, 1f)) * pw
                            else -> cropBox.lowerLeftX + (box.xRatio.coerceIn(0f, 1f) * pw)
                        }

                        val drawY = when (rot) {
                            90 -> cropBox.lowerLeftY + box.xRatio.coerceIn(0f, 1f) * ph
                            180 -> cropBox.lowerLeftY + box.yRatio.coerceIn(0f, 1f) * ph
                            270 -> cropBox.lowerLeftY + ph - (box.xRatio.coerceIn(0f, 1f) + box.widthRatio.coerceIn(0.01f, 1f)) * ph
                            else -> cropBox.lowerLeftY + ph - (box.yRatio.coerceIn(0f, 1f) + box.heightRatio.coerceIn(0.01f, 1f)) * ph
                        }

                        val drawW = if (rot == 90 || rot == 270) box.heightRatio.coerceIn(0.01f, 1f) * pw else box.widthRatio.coerceIn(0.01f, 1f) * pw
                        val drawH = if (rot == 90 || rot == 270) box.widthRatio.coerceIn(0.01f, 1f) * ph else box.heightRatio.coerceIn(0.01f, 1f) * ph

                        val wv = box.widthRatio.coerceIn(0.01f, 1f) * pw
                        val hv = box.heightRatio.coerceIn(0.01f, 1f) * ph

                        cs.saveGraphicsState()
                        if (config.isBlackout) {
                            cs.setNonStrokingColor(0, 0, 0)
                        } else {
                            cs.setNonStrokingColor(255, 255, 255)
                        }
                        cs.addRect(drawX, drawY, drawW, drawH)
                        cs.fill()
                        cs.restoreGraphicsState()

                        val overlay = config.defaultOverlayText
                        if (overlay.isNotBlank() && wv > 30f && hv > 10f) {
                            val fontSize = (hv * 0.6f).coerceIn(6f, 10f)
                            val textW = (font.getStringWidth(overlay) / 1000f) * fontSize
                            if (textW < wv) {
                                cs.saveGraphicsState()
                                cs.beginText()
                                cs.setFont(font, fontSize)
                                if (config.isBlackout) {
                                    cs.setNonStrokingColor(255, 255, 255)
                                } else {
                                    cs.setNonStrokingColor(80, 80, 80)
                                }
                                
                                val rad = Math.toRadians((360 - rot).toDouble())
                                val tx = when (rot) {
                                    90 -> drawX + drawW - ((drawW - fontSize) / 2f)
                                    180 -> drawX + drawW - ((drawW - textW) / 2f)
                                    270 -> drawX + ((drawW - fontSize) / 2f)
                                    else -> drawX + ((drawW - textW) / 2f)
                                }
                                val ty = when (rot) {
                                    90 -> drawY + drawH - ((drawH - textW) / 2f)
                                    180 -> drawY + drawH - ((drawH - fontSize) / 2f)
                                    270 -> drawY + ((drawH - textW) / 2f)
                                    else -> drawY + ((drawH - fontSize) / 2f)
                                }
                                
                                val matrix = com.tom_roush.pdfbox.util.Matrix.getRotateInstance(rad, tx, ty)
                                cs.setTextMatrix(matrix)
                                cs.showText(overlay)
                                cs.endText()
                                cs.restoreGraphicsState()
                            }
                        }
                    }
                }
            }

            val tempFile = com.pdfchemy.app.jail.JailScratch.namedFile("redacted_" + System.currentTimeMillis() + ".pdf")
            var rasterFile: File? = null
            var pfd: ParcelFileDescriptor? = null
            var newDoc: PDDocument? = null

            try {
                com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { document!!.save(it) }
                document!!.close()
                document = null

                val finalFile = if (config.forensicSanitize) {
                    rasterFile = com.pdfchemy.app.jail.JailScratch.namedFile("rasterized_" + System.currentTimeMillis() + ".pdf")
                    pfd = try { com.pdfchemy.app.jail.CapabilityIo.fd(tempFile) } catch (_: Exception) { null }
                    val renderedPageCount = if (pfd != null) JailNativeRendererCoordinator.getPageCount(pfd, rendererBinder) else null
                    val memSettings = com.pdfchemy.app.jail.JailMemory.settings()
                    val baseDoc = com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { PDDocument.load(it, memSettings) }
                    
                    // Keep the loaded page identities: annotations/destinations then
                    // resolve to the very pages whose original streams we replace.
                    // importPage retains references to the source page graph and its
                    // streams also become unreadable when that source is closed.
                    newDoc = baseDoc
                    if (renderedPageCount != null && renderedPageCount == baseDoc.numberOfPages) {
                        val plan = RedactionResources.rasterizationPlan(baseDoc, boxesByPage.keys, emptySet())
                        for (i in 0 until renderedPageCount) {
                            if (i in plan.pages) {
                                val pipe = ParcelFileDescriptor.createPipe()
                                val readFd = pipe[0]
                                val writeFd = pipe[1]
                                var pdImage: com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject? = null

                                val renderJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(Dispatchers.IO) {
                                    try {
                                        JailNativeRendererCoordinator.renderPageToJpeg(pfd!!, i, writeFd, rendererBinder)
                                    } finally {
                                        try { writeFd.close() } catch (e: Exception) {}
                                    }
                                }

                                try {
                                    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { pipeIn ->
                                        pdImage = JPEGFactory.createFromStream(newDoc, pipeIn)
                                    }
                                } finally {
                                    renderJob.await()
                                }

                                if (pdImage != null) {
                                    val pdfPage = baseDoc.getPage(i)
                                    val crop = pdfPage.cropBox ?: pdfPage.mediaBox
                                    val rotation = ((pdfPage.rotation % 360) + 360) % 360
                                    // The renderer returns the rotated crop plane.
                                    // Preserve PDF coordinates for existing XYZ/Fit
                                    // destinations instead of rebasing pages to pixels.
                                    RedactionResources.replacePageContent(baseDoc, pdfPage) { cs ->
                                        cs.saveGraphicsState()
                                        val x = crop.lowerLeftX
                                        val y = crop.lowerLeftY
                                        val w = crop.width
                                        val h = crop.height
                                        when (rotation) {
                                            90 -> { cs.transform(com.tom_roush.pdfbox.util.Matrix(0f, 1f, -1f, 0f, x + w, y)); cs.drawImage(pdImage!!, 0f, 0f, h, w) }
                                            180 -> { cs.transform(com.tom_roush.pdfbox.util.Matrix(-1f, 0f, 0f, -1f, x + w, y + h)); cs.drawImage(pdImage!!, 0f, 0f, w, h) }
                                            270 -> { cs.transform(com.tom_roush.pdfbox.util.Matrix(0f, -1f, 1f, 0f, x, y + h)); cs.drawImage(pdImage!!, 0f, 0f, h, w) }
                                            else -> cs.drawImage(pdImage!!, x, y, w, h)
                                        }
                                        cs.restoreGraphicsState()
                                    }
                                } else {
                                    throw SecurityException("Native renderer failed to yield valid image data.")
                                }
                            }
                        }
                        RedactionResources.pruneUnused(baseDoc)
                        plan.requireOriginalsDetached(baseDoc)
                        try { pfd?.close() } catch (_: Exception) {}
                        pfd = null
                        
                        com.pdfchemy.app.jail.boundedFileOutput(rasterFile).use { newDoc!!.save(it) }
                        newDoc!!.close()
                        newDoc = null
                        tempFile.delete()
                        rasterFile
                    } else {
                        try { pfd?.close() } catch (_: Exception) {}
                        tempFile.delete()
                        throw SecurityException("Cannot forensically rasterize the PDF because NativeRendererCoordinator is unavailable. Aborting redaction to ensure maximum security without data loss.")
                    }
                } else {
                    tempFile
                }

                com.pdfchemy.app.jail.CapabilityIo.input(finalFile).use { inp ->
                    FileOutputStream(destFd.fileDescriptor).use { output ->
                        inp.copyTo(output)
                    }
                }

                return boxes.size
            } finally {
                try { pfd?.close() } catch (_: Exception) {}
                try { newDoc?.close() } catch (_: Exception) {}
                if (tempFile.exists()) tempFile.delete()
                if (rasterFile != null && rasterFile.exists()) rasterFile.delete()
            }
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }
}
