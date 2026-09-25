package com.pdfchemy.desktop.jail

import java.io.File
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import com.pdfchemy.desktop.engine.*
import java.io.InputStream
import java.awt.image.BufferedImage
import java.awt.geom.Point2D

object PdfJailRouter {
    fun routeRequest(operation: String, request: JailRequest, payloadFile: java.io.File?, outStream: java.io.OutputStream, secret: String, sessionId: String): Boolean {
        return when(operation) {
            "inspectMetadata" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.inspectMetadata(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "stripMetadata" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.stripMetadata(inputFile = inputFile, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "getPageCount" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.getPageCount(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "extractText" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.extractText(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "readImageSafely" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val maxDim = request.config["maxDim"]?.toIntOrNull() ?: 0
                val result = DesktopJailPdfEngine.readImageSafely(file = file, maxDim = maxDim)
                val baos = ByteArrayOutputStream()
                ImageIO.write(result, "PNG", baos)
                val imgBytes = baos.toByteArray()
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                true
            }
            "getPageDimensions" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.getPageDimensions(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "extractAllPagesText" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.extractAllPagesText(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "extractBookmarks" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.extractBookmarks(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "rotateSinglePage" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val degreesDelta = request.config["degreesDelta"]?.toIntOrNull() ?: 0
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                val resultObj = DesktopJailPdfEngine.rotateSinglePage(inputFile = inputFile, pageIndex = pageIndex, degreesDelta = degreesDelta, outputFile = tempOut)
                if (resultObj.isFailure) {
                    val ex = resultObj.exceptionOrNull()
                    val errResponse = JailResponse(status = "ERROR", errorMessage = ex?.message ?: "Unknown error")
                errResponse.sendChunked(outStream, tempOut, null, secret, sessionId)
                    tempOut.delete()
                    return true
                }
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "renderPage" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val dpi = request.config["dpi"]?.toFloatOrNull() ?: 0f
                val viewRotation = request.config["viewRotation"]?.toIntOrNull() ?: 0
                val result = DesktopJailPdfEngine.renderPage(file = file, pageIndex = pageIndex, dpi = dpi, viewRotation = viewRotation)
                val baos = ByteArrayOutputStream()
                ImageIO.write(result, "PNG", baos)
                val imgBytes = baos.toByteArray()
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                true
            }
            "rotateImage" -> {
                val src_path = request.config["src"] ?: ""
                val src = if (src_path.isNotEmpty()) ImageIO.read(File(src_path)) else BufferedImage(1,1, BufferedImage.TYPE_INT_ARGB)
                val degrees = request.config["degrees"]?.toIntOrNull() ?: 0
                val result = DesktopJailPdfEngine.rotateImage(src = src, degrees = degrees)
                val baos = ByteArrayOutputStream()
                ImageIO.write(result, "PNG", baos)
                val imgBytes = baos.toByteArray()
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                true
            }
            "renderThumbnail" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val targetWidth = request.config["targetWidth"]?.toIntOrNull() ?: 0
                val result = DesktopJailPdfEngine.renderThumbnail(file = file, pageIndex = pageIndex, targetWidth = targetWidth)
                val baos = ByteArrayOutputStream()
                ImageIO.write(result, "PNG", baos)
                val imgBytes = baos.toByteArray()
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                true
            }
            "renderAllThumbnails" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val targetWidth = request.config["targetWidth"]?.toIntOrNull() ?: 0
                DesktopJailPdfEngine.renderAllThumbnails(file = file, targetWidth = targetWidth, onThumbnailRendered = { pageIdx, img ->
                    val baos = java.io.ByteArrayOutputStream()
                    javax.imageio.ImageIO.write(img, "PNG", baos)
                    val imgBytes = baos.toByteArray()
                    val response = JailResponse(status = "STREAM_DATA", payload = pageIdx.toString())
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                })
                val finalResponse = JailResponse(status = "SUCCESS")
                finalResponse.sendChunked(outStream, null, null, secret, sessionId)
                true
            }
            "saveReorderedPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageSpecs = JailIpc.gson.fromJson(request.config["pageSpecs"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<PageItemSpec>>() {}.type) ?: emptyList<PageItemSpec>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.saveReorderedPdf(inputFile = inputFile, pageSpecs = pageSpecs, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "mergePdfs" -> {
                val inputFilesKeys = JailIpc.gson.fromJson(request.config["inputFilesKeys"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<String>>() {}.type) ?: emptyList<String>()
                val inputFiles = inputFilesKeys.mapNotNull { request.config[it]?.let { path -> File(path) } }
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.mergePdfs(inputFiles = inputFiles, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "splitPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val splitEveryNPages = request.config["splitEveryNPages"]?.toIntOrNull() ?: 0
                val tempDir = File(System.getProperty("user.dir"), "jail_split_${java.util.UUID.randomUUID()}").apply { mkdirs(); deleteOnExit() }
                val result = DesktopJailPdfEngine.splitPdf(inputFile = inputFile, splitEveryNPages = splitEveryNPages, outputDir = tempDir)
                for (f in result) {
                     val response = JailResponse(status = "FILE_READY", config = mapOf("fileName" to f.name))
                response.sendChunked(outStream, f, null, secret, sessionId)
                }
                val finalResponse = JailResponse(status = "SUCCESS")
                finalResponse.sendChunked(outStream, null, null, secret, sessionId)
                true
            }
            "rotatePages" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val degrees = request.config["degrees"]?.toIntOrNull() ?: 0
                val pageIndices = JailIpc.gson.fromJson(request.config["pageIndices"] ?: "[]", object : com.google.gson.reflect.TypeToken<Set<Int>>() {}.type) ?: emptySet<Int>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.rotatePages(inputFile = inputFile, degrees = degrees, pageIndices = pageIndices, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "deletePages" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pagesToDelete = JailIpc.gson.fromJson(request.config["pagesToDelete"] ?: "[]", object : com.google.gson.reflect.TypeToken<Set<Int>>() {}.type) ?: emptySet<Int>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.deletePages(inputFile = inputFile, pagesToDelete = pagesToDelete, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "encryptPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val userPass = request.config["userPass"] ?: ""
                val ownerPass = request.config["ownerPass"] ?: ""
                val keyLengthBits = request.config["keyLengthBits"]?.toIntOrNull() ?: 0
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.encryptPdf(inputFile = inputFile, userPass = userPass, ownerPass = ownerPass, keyLengthBits = keyLengthBits, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "decryptPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val password = request.config["password"] ?: ""
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.decryptPdf(inputFile = inputFile, password = password, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "compressPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val targetDpi = request.config["targetDpi"]?.toFloatOrNull() ?: 0f
                val quality = request.config["quality"]?.toFloatOrNull() ?: 0f
                val rasterizePages = request.config["rasterizePages"]?.toBooleanStrictOrNull() ?: false
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.compressPdf(inputFile = inputFile, targetDpi = targetDpi, quality = quality, rasterizePages = rasterizePages, onProgress = { _, _ -> }, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "compressToTargetSize" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val targetBytes = JailIpc.gson.fromJson(request.config["targetBytes"] ?: "{}", Long::class.java)
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.compressToTargetSize(inputFile = inputFile, targetBytes = targetBytes, onProgress = { _ -> }, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "extractPagesToImages" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val format = request.config["format"] ?: ""
                val dpi = request.config["dpi"]?.toFloatOrNull() ?: 0f
                val outputFolder = File(System.getProperty("user.dir"), "jail_extract_${java.util.UUID.randomUUID()}").apply { mkdirs(); deleteOnExit() }
                val result = DesktopJailPdfEngine.extractPagesToImages(inputFile = inputFile, outputFolder = outputFolder, format = format, dpi = dpi)
                for (f in result) {
                     val response = JailResponse(status = "FILE_READY", config = mapOf("fileName" to f.name))
                response.sendChunked(outStream, f, null, secret, sessionId)
                }
                val finalResponse = JailResponse(status = "SUCCESS")
                finalResponse.sendChunked(outStream, null, null, secret, sessionId)
                true
            }
            "imagesToPdf" -> {
                val imageFilesKeys = JailIpc.gson.fromJson(request.config["imageFilesKeys"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<String>>() {}.type) ?: emptyList<String>()
                val imageFiles = imageFilesKeys.mapNotNull { request.config[it]?.let { path -> File(path) } }
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.imagesToPdf(imageFiles = imageFiles, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "repairPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.repairPdf(inputFile = inputFile, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "redactPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val query = request.config["query"] ?: ""
                val overlayText = request.config["overlayText"] ?: ""
                val forensicSanitize = request.config["forensicSanitize"]?.toBooleanStrictOrNull() ?: false
                val dpi = request.config["dpi"]?.toFloatOrNull() ?: 0f
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.redactPdf(inputFile = inputFile, query = query, overlayText = overlayText, forensicSanitize = forensicSanitize, dpi = dpi, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "stampDocument" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val stampImage_path = request.config["stampImage"] ?: ""
                val stampImage = if (stampImage_path.isNotEmpty()) ImageIO.read(File(stampImage_path)) else BufferedImage(1,1, BufferedImage.TYPE_INT_ARGB)
                val xRatio = request.config["xRatio"]?.toFloatOrNull() ?: 0f
                val yRatio = request.config["yRatio"]?.toFloatOrNull() ?: 0f
                val widthRatio = request.config["widthRatio"]?.toFloatOrNull() ?: 0f
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.stampDocument(inputFile = inputFile, pageIndex = pageIndex, stampImage = stampImage, xRatio = xRatio, yRatio = yRatio, widthRatio = widthRatio, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "createBusinessStamp" -> {
                val title = request.config["title"] ?: ""
                val subtext = request.config["subtext"] ?: ""
                val colorHex = request.config["colorHex"] ?: ""
                val result = DesktopJailPdfEngine.createBusinessStamp(title = title, subtext = subtext, colorHex = colorHex)
                val baos = ByteArrayOutputStream()
                ImageIO.write(result, "PNG", baos)
                val imgBytes = baos.toByteArray()
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                true
            }
            "renderStrokesToImage" -> {
                val strokes = JailIpc.gson.fromJson(request.config["strokes"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<List<Point2D.Float>>>() {}.type) ?: emptyList<List<Point2D.Float>>()
                val canvasWidth = request.config["canvasWidth"]?.toIntOrNull() ?: 0
                val canvasHeight = request.config["canvasHeight"]?.toIntOrNull() ?: 0
                val colorHex = request.config["colorHex"] ?: ""
                val strokeWidth = request.config["strokeWidth"]?.toFloatOrNull() ?: 0f
                val result = DesktopJailPdfEngine.renderStrokesToImage(strokes = strokes, canvasWidth = canvasWidth, canvasHeight = canvasHeight, colorHex = colorHex, strokeWidth = strokeWidth)
                val baos = ByteArrayOutputStream()
                ImageIO.write(result, "PNG", baos)
                val imgBytes = baos.toByteArray()
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, imgBytes, secret, sessionId)
                true
            }
            "addTextAnnotations" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val items = JailIpc.gson.fromJson(request.config["items"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<TextAnnotationItem>>() {}.type) ?: emptyList<TextAnnotationItem>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.addTextAnnotations(inputFile = inputFile, pageIndex = pageIndex, items = items, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "addPageNumbers" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val formatPattern = request.config["formatPattern"] ?: ""
                val position = JailIpc.gson.fromJson(request.config["position"] ?: "{}", HeaderFooterPos::class.java)
                val fontSize = request.config["fontSize"]?.toFloatOrNull() ?: 0f
                val colorHex = request.config["colorHex"] ?: ""
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.addPageNumbers(inputFile = inputFile, formatPattern = formatPattern, position = position, fontSize = fontSize, colorHex = colorHex, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "addWatermark" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val watermarkText = request.config["watermarkText"] ?: ""
                val opacity = request.config["opacity"]?.toFloatOrNull() ?: 0f
                val rotationDegrees = request.config["rotationDegrees"]?.toFloatOrNull() ?: 0f
                val colorHex = request.config["colorHex"] ?: ""
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.addWatermark(inputFile = inputFile, watermarkText = watermarkText, opacity = opacity, rotationDegrees = rotationDegrees, colorHex = colorHex, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "hasAcroForm" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.hasAcroForm(inputFile = inputFile)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "extractAcroFields" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.extractAcroFields(inputFile = inputFile)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "fillAndFlattenAcroForm" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val fieldValues = JailIpc.gson.fromJson(request.config["fieldValues"] ?: "{}", object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type) ?: emptyMap<String, String>()
                val flatten = request.config["flatten"]?.toBooleanStrictOrNull() ?: false
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.fillAndFlattenAcroForm(inputFile = inputFile, fieldValues = fieldValues, flatten = flatten, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "compareDocuments" -> {
                val fileA = File(request.config["fileA"] ?: "")
                val fileB = File(request.config["fileB"] ?: "")
                val result = DesktopJailPdfEngine.compareDocuments(fileA = fileA, fileB = fileB)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "applyBatesStamping" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val config = JailIpc.gson.fromJson(request.config["config"] ?: "{}", DesktopBatesConfig::class.java)
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.applyBatesStamping(inputFile = inputFile, config = config, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "auditDocumentThreats" -> {
                val file = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.auditDocumentThreats(file = file)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "smartRedact" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val patterns = JailIpc.gson.fromJson(request.config["patterns"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<RedactPattern>>() {}.type) ?: emptyList<RedactPattern>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.smartRedact(inputFile = inputFile, patterns = patterns, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "sanitizeDocument" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val purgeJs = request.config["purgeJs"]?.toBooleanStrictOrNull() ?: false
                val purgeActions = request.config["purgeActions"]?.toBooleanStrictOrNull() ?: false
                val purgeMetadata = request.config["purgeMetadata"]?.toBooleanStrictOrNull() ?: false
                val purgeAttachments = request.config["purgeAttachments"]?.toBooleanStrictOrNull() ?: false
                val purgePrivateAnnotations = request.config["purgePrivateAnnotations"]?.toBooleanStrictOrNull() ?: false
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                val resultObj = DesktopJailPdfEngine.sanitizeDocument(inputFile = inputFile, purgeJs = purgeJs, purgeActions = purgeActions, purgeMetadata = purgeMetadata, purgeAttachments = purgeAttachments, purgePrivateAnnotations = purgePrivateAnnotations, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS", type = "FILE", payload = JailIpc.gson.toJson(resultObj))
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "repairCorruptedPdf" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                val resultObj = DesktopJailPdfEngine.repairCorruptedPdf(inputFile = inputFile, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS", type = "FILE", payload = JailIpc.gson.toJson(resultObj))
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "convertToPdfA" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.convertToPdfA(inputFile = inputFile, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "cropMargins" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val config = JailIpc.gson.fromJson(request.config["config"] ?: "{}", DesktopCropConfig::class.java)
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.cropMargins(inputFile = inputFile, config = config, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "detectContentCrop" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val paddingPt = request.config["paddingPt"]?.toFloatOrNull() ?: 0f
                val result = DesktopJailPdfEngine.detectContentCrop(inputFile = inputFile, pageIndex = pageIndex, paddingPt = paddingPt)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "extractTablesToCsv" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pageIndex = request.config["pageIndex"]?.toIntOrNull() ?: 0
                val safeMode = request.config["safeMode"]?.toBooleanStrictOrNull() ?: false
                val result = DesktopJailPdfEngine.extractTablesToCsv(inputFile = inputFile, pageIndex = pageIndex, safeMode = safeMode)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "detectSkewAngle" -> {
                val image_path = request.config["image"] ?: ""
                val image = if (image_path.isNotEmpty()) ImageIO.read(File(image_path)) else BufferedImage(1,1, BufferedImage.TYPE_INT_ARGB)
                val result = DesktopJailPdfEngine.detectSkewAngle(image = image)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "deskewDocument" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val targetPages = JailIpc.gson.fromJson(request.config["targetPages"] ?: "[]", object : com.google.gson.reflect.TypeToken<Set<Int>>() {}.type) ?: emptySet<Int>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.deskewDocument(inputFile = inputFile, targetPages = targetPages, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "generateBooklet" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val drawFoldGuide = request.config["drawFoldGuide"]?.toBooleanStrictOrNull() ?: false
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.generateBooklet(inputFile = inputFile, drawFoldGuide = drawFoldGuide, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "generateNUp" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val pagesPerSheet = request.config["pagesPerSheet"]?.toIntOrNull() ?: 0
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.generateNUp(inputFile = inputFile, pagesPerSheet = pagesPerSheet, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "splitByBlankPages" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val whiteThreshold = request.config["whiteThreshold"]?.toFloatOrNull() ?: 0f
                val tempDir = java.nio.file.Files.createTempDirectory("jail_split").toFile()
                tempDir.deleteOnExit()
                val result = DesktopJailPdfEngine.splitByBlankPages(inputFile = inputFile, whiteThreshold = whiteThreshold, outputDir = tempDir)
                for (f in result) {
                     val response = JailResponse(status = "FILE_READY", config = mapOf("fileName" to f.name))
                response.sendChunked(outStream, f, null, secret, sessionId)
                }
                val finalResponse = JailResponse(status = "SUCCESS")
                finalResponse.sendChunked(outStream, null, null, secret, sessionId)
                true
            }
            "splitByBookmarks" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val tempDir = java.nio.file.Files.createTempDirectory("jail_split").toFile()
                tempDir.deleteOnExit()
                val result = DesktopJailPdfEngine.splitByBookmarks(inputFile = inputFile, outputDir = tempDir)
                for (f in result) {
                     val response = JailResponse(status = "FILE_READY", config = mapOf("fileName" to f.name))
                response.sendChunked(outStream, f, null, secret, sessionId)
                }
                val finalResponse = JailResponse(status = "SUCCESS")
                finalResponse.sendChunked(outStream, null, null, secret, sessionId)
                true
            }
            "listAttachments" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val result = DesktopJailPdfEngine.listAttachments(inputFile = inputFile)
                val jsonStr = JailIpc.gson.toJson(result)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, null, jsonBytes, secret, sessionId)
                true
            }
            "extractAttachment" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val attachmentName = request.config["attachmentName"] ?: ""
                val tempDir = java.nio.file.Files.createTempDirectory("jail_extract").toFile()
                tempDir.deleteOnExit()
                val result = DesktopJailPdfEngine.extractAttachment(inputFile = inputFile, attachmentName = attachmentName, outputDir = tempDir)
                if (result != null) {
                    val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, result, null, secret, sessionId)
                } else {
                    val errResponse = JailResponse(status = "ERROR", errorMessage = "Attachment not found")
                errResponse.sendChunked(outStream, null, null, secret, sessionId)
                }
                tempDir.deleteRecursively()
                true
            }
            "embedAttachment" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val attachmentFile = File(request.config["attachmentFile"] ?: "")
                val originalFileName = request.config["originalFileName"] ?: attachmentFile.name
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                DesktopJailPdfEngine.embedAttachment(inputFile = inputFile, attachmentFile = attachmentFile, originalFileName = originalFileName, outputFile = tempOut)
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "signDocument" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val signerName = request.config["signerName"] ?: ""
                val reason = request.config["reason"] ?: ""
                val location = request.config["location"] ?: ""
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                val resultObj = DesktopJailPdfEngine.signDocument(inputFile = inputFile, signerName = signerName, reason = reason, location = location, outputFile = tempOut)
                if (resultObj.isFailure) {
                    val ex = resultObj.exceptionOrNull()
                    val errResponse = JailResponse(status = "ERROR", errorMessage = ex?.message ?: "Unknown error")
                errResponse.sendChunked(outStream, tempOut, null, secret, sessionId)
                    tempOut.delete()
                    return true
                }
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "makeSearchable" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                val resultObj = DesktopJailPdfEngine.makeSearchable(inputFile = inputFile, outputFile = tempOut)
                if (resultObj.isFailure) {
                    val ex = resultObj.exceptionOrNull()
                    val errResponse = JailResponse(status = "ERROR", errorMessage = ex?.message ?: "Unknown error")
                errResponse.sendChunked(outStream, tempOut, null, secret, sessionId)
                    tempOut.delete()
                    return true
                }
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            "addAcroFormFields" -> {
                val inputFile = payloadFile ?: throw IllegalArgumentException("Missing payload file")
                val fields = JailIpc.gson.fromJson(request.config["fields"] ?: "[]", object : com.google.gson.reflect.TypeToken<List<DesktopFormFieldSpec>>() {}.type) ?: emptyList<DesktopFormFieldSpec>()
                val tempOut = File(System.getProperty("user.dir"), "jail_out_${java.util.UUID.randomUUID()}.pdf")
                tempOut.deleteOnExit()
                val resultObj = DesktopJailPdfEngine.addAcroFormFields(inputFile = inputFile, fields = fields, outputFile = tempOut)
                if (resultObj.isFailure) {
                    val ex = resultObj.exceptionOrNull()
                    val errResponse = JailResponse(status = "ERROR", errorMessage = ex?.message ?: "Unknown error")
                errResponse.sendChunked(outStream, tempOut, null, secret, sessionId)
                    tempOut.delete()
                    return true
                }
                val response = JailResponse(status = "SUCCESS")
                response.sendChunked(outStream, tempOut, null, secret, sessionId)
                tempOut.delete()
                true
            }
            else -> false
        }
    }
}
