package com.pdfchemy.desktop.engine

import com.pdfchemy.desktop.jail.DesktopJailManager
import com.pdfchemy.desktop.jail.JailRequest
import com.pdfchemy.desktop.jail.JailIpc
import kotlinx.coroutines.runBlocking
import java.io.File
import java.awt.image.BufferedImage
import java.awt.geom.Point2D
import javax.imageio.ImageIO
import java.io.FileOutputStream

object DesktopPdfEngine {
    fun inspectMetadata(file: File): DesktopPdfMetadata {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "inspectMetadata", config = emptyMap())
                session.sendRequest(req, file)
                var result: DesktopPdfMetadata? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<DesktopPdfMetadata>() {}.type)
                    }
                }
                result!!
            }
        }
    }

    fun stripMetadata(inputFile: File, outputFile: File): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "stripMetadata",
                    config = emptyMap(),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun getPageCount(file: File): Int {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "getPageCount", config = emptyMap())
                session.sendRequest(req, file)
                var result: Int? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, Int::class.javaObjectType)
                    }
                }
                result ?: 0
            }
        }
    }

    fun extractText(file: File): String {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "extractText", config = emptyMap())
                session.sendRequest(req, file)
                var result: String? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, String::class.javaObjectType)
                    }
                }
                result ?: ""
            }
        }
    }

    fun readImageSafely(file: File, maxDim: Int = 4096): BufferedImage? {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "readImageSafely", config = mapOf("maxDim" to maxDim.toString()))
                session.sendRequest(req, file)
                var img: BufferedImage? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 50 * 1024 * 1024) throw IllegalStateException("Image payload too large: $payloadLength bytes")
                        img = ImageIO.read(boundedStream)
                    }
                }
                img
            }
        }
    }

    fun getPageDimensions(file: File): List<PageDimension> {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "getPageDimensions", config = emptyMap())
                session.sendRequest(req, file)
                var result: List<PageDimension>? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<PageDimension>>() {}.type)
                    }
                }
                result ?: emptyList()
            }
        }
    }

    fun extractAllPagesText(file: File): List<String> {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "extractAllPagesText", config = emptyMap())
                session.sendRequest(req, file)
                var result: List<String>? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type)
                    }
                }
                result ?: emptyList()
            }
        }
    }

    fun extractBookmarks(file: File): List<DesktopPdfBookmark> {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "extractBookmarks", config = emptyMap())
                session.sendRequest(req, file)
                var result: List<DesktopPdfBookmark>? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<DesktopPdfBookmark>>() {}.type)
                    }
                }
                result ?: emptyList()
            }
        }
    }

    fun rotateSinglePage(inputFile: File, outputFile: File, pageIndex: Int, degreesDelta: Int): Result<Boolean> {
        return runCatching {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "rotateSinglePage",
                    config = mapOf("pageIndex" to pageIndex.toString(), "degreesDelta" to degreesDelta.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            true
        }
        // Missing return for Result<Boolean>
    }

    fun renderPage(file: File, pageIndex: Int, dpi: Float = 144f, viewRotation: Int = 0): BufferedImage {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "renderPage", config = mapOf("pageIndex" to pageIndex.toString(), "dpi" to dpi.toString(), "viewRotation" to viewRotation.toString()))
                session.sendRequest(req, file)
                var img: BufferedImage? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 50 * 1024 * 1024) throw IllegalStateException("Image payload too large: $payloadLength bytes")
                        img = ImageIO.read(boundedStream)
                    }
                }
                img ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            }
        }
    }

    fun rotateImage(src: BufferedImage, degrees: Int): BufferedImage {
        val tempImg_src = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("img_", ".png")
        tempImg_src.deleteOnExit()
        ImageIO.write(src, "PNG", tempImg_src)
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "rotateImage", config = mapOf("src" to tempImg_src.absolutePath, "degrees" to degrees.toString()))
                session.sendRequest(req, null as ByteArray?)
                var img: BufferedImage? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 50 * 1024 * 1024) throw IllegalStateException("Image payload too large: $payloadLength bytes")
                        img = ImageIO.read(boundedStream)
                    }
                }
                img ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            }
        }
    }

    fun renderThumbnail(file: File, pageIndex: Int, targetWidth: Int = 260): BufferedImage {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "renderThumbnail", config = mapOf("pageIndex" to pageIndex.toString(), "targetWidth" to targetWidth.toString()))
                session.sendRequest(req, file)
                var img: BufferedImage? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 50 * 1024 * 1024) throw IllegalStateException("Image payload too large: $payloadLength bytes")
                        img = ImageIO.read(boundedStream)
                    }
                }
                img ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            }
        }
    }

    fun renderAllThumbnails(file: File,         targetWidth: Int = 260,         onThumbnailRendered: (pageIndex: Int, BufferedImage) -> Unit): Unit {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "renderAllThumbnails", config = mapOf("targetWidth" to targetWidth.toString()))
                session.sendRequest(req, file)
                while (true) {
                    var isDone = false
                    session.receiveResponse { response, payloadLength, boundedStream ->
                        if (response.status == "SUCCESS") {
                            isDone = true
                        } else if (response.status == "STREAM_DATA") {
                            val pageIdx = response.payload?.toIntOrNull() ?: 0
                            if (payloadLength > 0) {
                                val img = javax.imageio.ImageIO.read(boundedStream)
                                if (img != null) onThumbnailRendered(pageIdx, img)
                            }
                        } else if (response.status == "ERROR") {
                            throw RuntimeException("Jail Worker Error: ${response.errorMessage}")
                        }
                    }
                    if (isDone) break
                }
            }
        }
    }

    fun saveReorderedPdf(inputFile: File, outputFile: File, pageSpecs: List<PageItemSpec>): Unit {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "saveReorderedPdf",
                    config = mapOf("pageSpecs" to JailIpc.gson.toJson(pageSpecs)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
    }

    fun mergePdfs(inputFiles: List<File>, outputFile: File): Boolean {
        val sandboxFiles = mutableMapOf<String, File>()
        val mappedFileNames = inputFiles.mapIndexed { index, file ->
            val key = "file_$index"
            sandboxFiles[key] = file
            key
        }

        val jailResult = runBlocking {
            DesktopJailManager.executeWithResult(
                operation = "mergePdfs",
                config = mapOf("inputFilesKeys" to JailIpc.gson.toJson(mappedFileNames)),
                sourceFile = null,
                sandboxFiles = sandboxFiles
            )
        }
        jailResult.first.copyTo(outputFile, overwrite = true)
        jailResult.first.delete()
        return true
    }

    fun splitPdf(inputFile: File, outputDir: File, splitEveryNPages: Int = 1): List<File> {
        val resultList = mutableListOf<File>()
        runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "splitPdf", config = mapOf("splitEveryNPages" to splitEveryNPages.toString()))
                session.sendRequest(req, inputFile)
                var done = false
                while (!done) {
                    session.receiveResponse { response, payloadLength, boundedStream ->
                        if (response.status == "SUCCESS") { done = true }
                        else if (response.status == "FILE_READY") {
                            val fname = response.config["fileName"] ?: "out.pdf"
                            val outF = File(outputDir, fname)
                            FileOutputStream(outF).use { fos -> boundedStream.copyTo(fos) }
                            resultList.add(outF)
                        }
                    }
                }
            }
        }
        return resultList
    }

    fun rotatePages(inputFile: File, outputFile: File, degrees: Int, pageIndices: Set<Int> = emptySet()): Unit {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "rotatePages",
                    config = mapOf("degrees" to degrees.toString(), "pageIndices" to JailIpc.gson.toJson(pageIndices)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
    }

    fun deletePages(inputFile: File, outputFile: File, pagesToDelete: Set<Int>): Unit {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "deletePages",
                    config = mapOf("pagesToDelete" to JailIpc.gson.toJson(pagesToDelete)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
    }

    fun encryptPdf(inputFile: File, outputFile: File, userPass: String, ownerPass: String = userPass, keyLengthBits: Int = 256): Unit {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "encryptPdf",
                    config = mapOf("userPass" to userPass.toString(), "ownerPass" to ownerPass.toString(), "keyLengthBits" to keyLengthBits.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
    }

    fun decryptPdf(inputFile: File, outputFile: File, password: String): Unit {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "decryptPdf",
                    config = mapOf("password" to password.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
    }

    fun compressPdf(inputFile: File,         outputFile: File,         targetDpi: Float = 140f,         quality: Float = 0.7f,         rasterizePages: Boolean = false,         onProgress: (Int, Int) -> Unit = { _, _ -> }): Long {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "compressPdf",
                    config = mapOf("targetDpi" to targetDpi.toString(), "quality" to quality.toString(), "rasterizePages" to rasterizePages.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return outputFile.length()
    }

    fun compressToTargetSize(inputFile: File,         outputFile: File,         targetBytes: Long,         onProgress: (String) -> Unit = {}): Long {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "compressToTargetSize",
                    config = mapOf("targetBytes" to JailIpc.gson.toJson(targetBytes)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return outputFile.length()
    }

    fun extractPagesToImages(inputFile: File,         outputFolder: File,         format: String = "png",         dpi: Float = 150f,         onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        val resultList = mutableListOf<File>()
        runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "extractPagesToImages", config = mapOf("outputFolder" to outputFolder.absolutePath, "format" to format.toString(), "dpi" to dpi.toString()))
                session.sendRequest(req, inputFile)
                var done = false
                while (!done) {
                    session.receiveResponse { response, payloadLength, boundedStream ->
                        if (response.status == "SUCCESS") { done = true }
                        else if (response.status == "FILE_READY") {
                            val fname = response.config["fileName"] ?: "out.pdf"
                            val outF = File(com.pdfchemy.desktop.engine.DesktopStaging.stagingDir, fname)
                            FileOutputStream(outF).use { fos -> boundedStream.copyTo(fos) }
                            resultList.add(outF)
                        }
                    }
                }
            }
        }
        return resultList
    }

    fun imagesToPdf(imageFiles: List<File>, outputFile: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): Unit {
        val sandboxFiles = mutableMapOf<String, File>()
        val mappedFileNames = imageFiles.mapIndexed { index, file ->
            val key = "img_$index"
            sandboxFiles[key] = file
            key
        }

        val jailResult = runBlocking {
            DesktopJailManager.execute(
                operation = "imagesToPdf",
                config = mapOf("imageFilesKeys" to JailIpc.gson.toJson(mappedFileNames)),
                sourceFile = null,
                sandboxFiles = sandboxFiles
            )
        }
        jailResult.copyTo(outputFile, overwrite = true)
        jailResult.delete()
    }

    fun repairPdf(inputFile: File, outputFile: File): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "repairPdf",
                    config = emptyMap(),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun redactPdf(inputFile: File,         outputFile: File,         query: String,         overlayText: String = "REDACTED",         forensicSanitize: Boolean = true,         dpi: Float = 150f): Int {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "redactPdf",
                    config = mapOf("query" to query.toString(), "overlayText" to overlayText.toString(), "forensicSanitize" to forensicSanitize.toString(), "dpi" to dpi.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return 1
    }

    fun stampDocument(inputFile: File,         outputFile: File,         pageIndex: Int,         stampImage: BufferedImage,         xRatio: Float,         yRatio: Float,         widthRatio: Float = 0.35f): Boolean {
        val tempImg_stampImage = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("img_", ".png")
        tempImg_stampImage.deleteOnExit()
        ImageIO.write(stampImage, "PNG", tempImg_stampImage)
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "stampDocument",
                    config = mapOf("pageIndex" to pageIndex.toString(), "xRatio" to xRatio.toString(), "yRatio" to yRatio.toString(), "widthRatio" to widthRatio.toString()),
                    sourceFile = inputFile,
                    sandboxFiles = mapOf("stampImage" to tempImg_stampImage)
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun createBusinessStamp(title: String,         subtext: String? = null,         colorHex: String = "#1E3A8A"): BufferedImage {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "createBusinessStamp", config = mapOf("title" to title.toString(), "subtext" to subtext.toString(), "colorHex" to colorHex.toString()))
                session.sendRequest(req, null as ByteArray?)
                var img: BufferedImage? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 50 * 1024 * 1024) throw IllegalStateException("Image payload too large: $payloadLength bytes")
                        img = ImageIO.read(boundedStream)
                    }
                }
                img ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            }
        }
    }

    fun renderStrokesToImage(strokes: List<List<Point2D.Float>>,         canvasWidth: Int,         canvasHeight: Int,         colorHex: String = "#1E3A8A",         strokeWidth: Float = 4f): BufferedImage {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "renderStrokesToImage", config = mapOf("strokes" to JailIpc.gson.toJson(strokes), "canvasWidth" to canvasWidth.toString(), "canvasHeight" to canvasHeight.toString(), "colorHex" to colorHex.toString(), "strokeWidth" to strokeWidth.toString()))
                session.sendRequest(req, null as ByteArray?)
                var img: BufferedImage? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 50 * 1024 * 1024) throw IllegalStateException("Image payload too large: $payloadLength bytes")
                        img = ImageIO.read(boundedStream)
                    }
                }
                img ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            }
        }
    }

    fun addTextAnnotations(inputFile: File,         outputFile: File,         pageIndex: Int,         items: List<TextAnnotationItem>): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "addTextAnnotations",
                    config = mapOf("pageIndex" to pageIndex.toString(), "items" to JailIpc.gson.toJson(items)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun addPageNumbers(inputFile: File,         outputFile: File,         formatPattern: String = "Page %1\$d of %2\$d",         position: HeaderFooterPos = HeaderFooterPos.BOTTOM_CENTER,         fontSize: Float = 10f,         colorHex: String = "#52525B"): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "addPageNumbers",
                    config = mapOf("formatPattern" to formatPattern.toString(), "position" to JailIpc.gson.toJson(position), "fontSize" to fontSize.toString(), "colorHex" to colorHex.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun addWatermark(inputFile: File,         outputFile: File,         watermarkText: String,         opacity: Float = 0.22f,         rotationDegrees: Float = 45f,         colorHex: String = "#DC2626"): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "addWatermark",
                    config = mapOf("watermarkText" to watermarkText.toString(), "opacity" to opacity.toString(), "rotationDegrees" to rotationDegrees.toString(), "colorHex" to colorHex.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun hasAcroForm(inputFile: File): Boolean {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "hasAcroForm", config = emptyMap())
                session.sendRequest(req, inputFile)
                var result: Boolean? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, Boolean::class.javaObjectType)
                    }
                }
                result ?: false
            }
        }
    }

    fun extractAcroFields(inputFile: File): List<DesktopAcroField> {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "extractAcroFields", config = emptyMap())
                session.sendRequest(req, inputFile)
                var result: List<DesktopAcroField>? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<DesktopAcroField>>() {}.type)
                    }
                }
                result ?: emptyList()
            }
        }
    }

    fun fillAndFlattenAcroForm(inputFile: File,         outputFile: File,         fieldValues: Map<String, String>,         flatten: Boolean = true): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "fillAndFlattenAcroForm",
                    config = mapOf("fieldValues" to JailIpc.gson.toJson(fieldValues), "flatten" to flatten.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun compareDocuments(fileA: File, fileB: File): PdfDiffSummary {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val jailFileA = File(session.worker!!.workingDirectory, "compare_A_${fileA.name}")
                fileA.copyTo(jailFileA, overwrite = true)
                val jailFileB = File(session.worker!!.workingDirectory, "compare_B_${fileB.name}")
                fileB.copyTo(jailFileB, overwrite = true)

                val req = JailRequest(operation = "compareDocuments", config = mapOf("fileA" to jailFileA.absolutePath, "fileB" to jailFileB.absolutePath))
                session.sendRequest(req, null as ByteArray?)
                var result: PdfDiffSummary? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<PdfDiffSummary>() {}.type)
                    }
                }
                result!!
            }
        }
    }

    fun applyBatesStamping(inputFile: File, outputFile: File, config: DesktopBatesConfig): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "applyBatesStamping",
                    config = mapOf("config" to JailIpc.gson.toJson(config)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun auditDocumentThreats(file: File): DesktopSanitizeResult {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "auditDocumentThreats", config = emptyMap())
                session.sendRequest(req, file)
                var result: DesktopSanitizeResult? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<DesktopSanitizeResult>() {}.type)
                    }
                }
                result!!
            }
        }
    }

    fun smartRedact(inputFile: File,         outputFile: File,         patterns: List<RedactPattern>): File {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "smartRedact",
                    config = mapOf("patterns" to JailIpc.gson.toJson(patterns)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return outputFile
    }

    fun sanitizeDocument(inputFile: File,         outputFile: File,         purgeJs: Boolean = true,         purgeActions: Boolean = true,         purgeMetadata: Boolean = true,         purgeAttachments: Boolean = true,         purgePrivateAnnotations: Boolean = false): DesktopSanitizeResult {
            val jailResultPair = runBlocking {
                DesktopJailManager.executeWithResult(
                    operation = "sanitizeDocument",
                    config = mapOf("purgeJs" to purgeJs.toString(), "purgeActions" to purgeActions.toString(), "purgeMetadata" to purgeMetadata.toString(), "purgeAttachments" to purgeAttachments.toString(), "purgePrivateAnnotations" to purgePrivateAnnotations.toString()),
                    sourceFile = inputFile
                )
            }
            val jailResult = jailResultPair.first
            val jailPayload = jailResultPair.second
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            if (jailPayload.isNotBlank()) return com.pdfchemy.desktop.jail.JailIpc.gson.fromJson(jailPayload, DesktopSanitizeResult::class.java)
            return DesktopSanitizeResult(0, 0, 0, false, 0, 0)
    }

    fun repairCorruptedPdf(inputFile: File, outputFile: File): DesktopRepairResult {
            val jailResultPair = runBlocking {
                DesktopJailManager.executeWithResult(
                    operation = "repairCorruptedPdf",
                    config = emptyMap(),
                    sourceFile = inputFile
                )
            }
            val jailResult = jailResultPair.first
            val jailPayload = jailResultPair.second
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            if (jailPayload.isNotBlank()) return com.pdfchemy.desktop.jail.JailIpc.gson.fromJson(jailPayload, DesktopRepairResult::class.java)
            return DesktopRepairResult(true, 0, emptyList(), 0L, 0L)
    }

    fun convertToPdfA(inputFile: File, outputFile: File): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "convertToPdfA",
                    config = emptyMap(),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun cropMargins(inputFile: File, outputFile: File, config: DesktopCropConfig): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "cropMargins",
                    config = mapOf("config" to JailIpc.gson.toJson(config)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun detectContentCrop(inputFile: File, pageIndex: Int = 0, paddingPt: Float = 18f): DesktopCropConfig {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "detectContentCrop", config = mapOf("pageIndex" to pageIndex.toString(), "paddingPt" to paddingPt.toString()))
                session.sendRequest(req, inputFile)
                var result: DesktopCropConfig? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<DesktopCropConfig>() {}.type)
                    }
                }
                result!!
            }
        }
    }

    fun extractTablesToCsv(inputFile: File, pageIndex: Int? = null, safeMode: Boolean = false): String {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "extractTablesToCsv", config = mapOf("pageIndex" to pageIndex.toString(), "safeMode" to safeMode.toString()))
                session.sendRequest(req, inputFile)
                var result: String? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, String::class.javaObjectType)
                    }
                }
                result ?: ""
            }
        }
    }

    fun detectSkewAngle(image: BufferedImage): Float {
        val tempImg_image = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("img_", ".png")
        tempImg_image.deleteOnExit()
        ImageIO.write(image, "PNG", tempImg_image)
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val jailImg = File(session.worker!!.workingDirectory, "skew_img.png")
                tempImg_image.copyTo(jailImg, overwrite = true)
                val req = JailRequest(operation = "detectSkewAngle", config = mapOf("image" to jailImg.absolutePath))
                session.sendRequest(req, null as ByteArray?)
                var result: Float? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, Float::class.javaObjectType)
                    }
                }
                result!!
            }
        }
    }

    fun deskewDocument(inputFile: File, outputFile: File, targetPages: Set<Int>? = null): Int {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "deskewDocument",
                    config = mapOf("targetPages" to JailIpc.gson.toJson(targetPages)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return 1
    }

    fun generateBooklet(inputFile: File, outputFile: File, drawFoldGuide: Boolean = true): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "generateBooklet",
                    config = mapOf("drawFoldGuide" to drawFoldGuide.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun generateNUp(inputFile: File, outputFile: File, pagesPerSheet: Int = 2): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "generateNUp",
                    config = mapOf("pagesPerSheet" to pagesPerSheet.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun splitByBlankPages(inputFile: File, outputDir: File, whiteThreshold: Float = 0.999f): List<File> {
        val resultList = mutableListOf<File>()
        runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "splitByBlankPages", config = mapOf("whiteThreshold" to whiteThreshold.toString()))
                session.sendRequest(req, inputFile)
                var done = false
                while (!done) {
                    session.receiveResponse { response, payloadLength, boundedStream ->
                        if (response.status == "SUCCESS") { done = true }
                        else if (response.status == "FILE_READY") {
                            val fname = response.config["fileName"] ?: "out.pdf"
                            val outF = File(outputDir, fname)
                            FileOutputStream(outF).use { fos -> boundedStream.copyTo(fos) }
                            resultList.add(outF)
                        }
                    }
                }
            }
        }
        return resultList
    }

    fun splitByBookmarks(inputFile: File, outputDir: File): List<File> {
        val resultList = mutableListOf<File>()
        runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "splitByBookmarks", config = emptyMap())
                session.sendRequest(req, inputFile)
                var done = false
                while (!done) {
                    session.receiveResponse { response, payloadLength, boundedStream ->
                        if (response.status == "SUCCESS") { done = true }
                        else if (response.status == "FILE_READY") {
                            val fname = response.config["fileName"] ?: "out.pdf"
                            val outF = File(outputDir, fname)
                            FileOutputStream(outF).use { fos -> boundedStream.copyTo(fos) }
                            resultList.add(outF)
                        }
                    }
                }
            }
        }
        return resultList
    }

    fun listAttachments(inputFile: File): List<DesktopAttachment> {
        return runBlocking {
            DesktopJailManager.executeInteractive { session ->
                val req = JailRequest(operation = "listAttachments", config = emptyMap())
                session.sendRequest(req, inputFile)
                var result: List<DesktopAttachment>? = null
                session.receiveResponse { response, payloadLength, boundedStream ->
                    if (response.status == "SUCCESS" && payloadLength > 0) {
                        if (payloadLength > 10 * 1024 * 1024) throw IllegalStateException("JSON payload too large: $payloadLength bytes")
                        val json = boundedStream.bufferedReader().use { it.readText() }
                        result = JailIpc.gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<DesktopAttachment>>() {}.type)
                    }
                }
                result ?: emptyList()
            }
        }
    }

    fun extractAttachment(inputFile: File, attachmentName: String, outputDir: File): File? {
        val jailResult = runBlocking {
            DesktopJailManager.execute(
                operation = "extractAttachment",
                config = mapOf("attachmentName" to attachmentName.toString()),
                sourceFile = inputFile
            )
        }
        val finalOut = SecurityUtil.safeOutputChild(outputDir, attachmentName)
        jailResult.copyTo(finalOut, overwrite = true)
        jailResult.delete()
        return finalOut
    }

    fun embedAttachment(inputFile: File, attachmentFile: File, outputFile: File): Boolean {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "embedAttachment",
                    config = mapOf("originalFileName" to attachmentFile.name),
                    sourceFile = inputFile,
                    sandboxFiles = mapOf("attachmentFile" to attachmentFile)
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            return true
    }

    fun signDocument(inputFile: File, outputFile: File, signerName: String, reason: String, location: String): Result<Boolean> {
        return runCatching {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "signDocument",
                    config = mapOf("signerName" to signerName.toString(), "reason" to reason.toString(), "location" to location.toString()),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            true
        }
        // Missing return for Result<Boolean>
    }

    fun makeSearchable(inputFile: File, outputFile: File, onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Result<Boolean> {
        return runCatching {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "makeSearchable",
                    config = emptyMap(),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            true
        }
        // Missing return for Result<Boolean>
    }

    fun addAcroFormFields(inputFile: File, outputFile: File, fields: List<DesktopFormFieldSpec>): Result<Boolean> {
        return runCatching {
            val jailResult = runBlocking {
                DesktopJailManager.execute(
                    operation = "addAcroFormFields",
                    config = mapOf("fields" to JailIpc.gson.toJson(fields)),
                    sourceFile = inputFile
                )
            }
            jailResult.copyTo(outputFile, overwrite = true)
            jailResult.delete()
            true
        }
        // Missing return for Result<Boolean>
    }

    fun speak(text: String, rate: Int = 0, onFinished: () -> Unit = {}) {}
    fun stop() {}
}