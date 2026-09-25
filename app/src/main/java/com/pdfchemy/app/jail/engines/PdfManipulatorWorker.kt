package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfManipulatorWorker {

    fun mergePdfs(context: Context, sourceFds: Array<out ParcelFileDescriptor>, targetFd: ParcelFileDescriptor?): String {
        if (targetFd == null) {
            return JSONObject().put("success", false).put("error", "No target file descriptor provided").toString()
        }

        try {
            val merger = PDFMergerUtility()
            val inputStreams = mutableListOf<FileInputStream>()

            for (fd in sourceFds) {
                val stream = FileInputStream(fd.fileDescriptor)
                inputStreams.add(stream)
                merger.addSource(stream)
            }

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                merger.destinationStream = out
                merger.mergeDocuments(null)
            }

            inputStreams.forEach { try { it.close() } catch (e: Exception) {} }

            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        }
    }

    fun splitPdf(context: Context, sourceFd: ParcelFileDescriptor?, targetFds: Array<out ParcelFileDescriptor>, paramsJson: String): String {
        if (sourceFd == null) {
            return JSONObject().put("success", false).put("error", "No source file descriptor provided").toString()
        }

        var document: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val pagesToKeepStr = params.optString("pagesToKeep", "")
            val multiGroupsStr = params.optString("multiGroups", "")
            
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                
                if (multiGroupsStr.isNotBlank()) {
                    // It's a multi-group split (like bookmarks or blank pages)
                    val groupsArray = JSONArray(multiGroupsStr)
                    if (groupsArray.length() == targetFds.size) {
                        for (i in 0 until groupsArray.length()) {
                            val group = groupsArray.getJSONArray(i)
                            PDDocument().use { subDoc ->
                                for (j in 0 until group.length()) {
                                    val pageNumber = group.getInt(j)
                                    subDoc.importPage(document!!.getPage(pageNumber))
                                }
                                FileOutputStream(targetFds[i].fileDescriptor).use { outStream ->
                                    subDoc.save(outStream)
                                }
                            }
                        }
                    }
                } else if (pagesToKeepStr.isNotBlank()) {
                    // Original single-page per target behavior
                    val pagesToKeepList = pagesToKeepStr.split(",").mapNotNull { it.toIntOrNull() }
                    if (pagesToKeepList.size == targetFds.size) {
                        for (i in targetFds.indices) {
                            val pageNumber = pagesToKeepList[i]
                            PDDocument().use { singleDoc ->
                                val page = document!!.getPage(pageNumber - 1)
                                singleDoc.importPage(page)
                                FileOutputStream(targetFds[i].fileDescriptor).use { outStream ->
                                    singleDoc.save(outStream)
                                }
                            }
                        }
                    }
                }
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
        }
    }

    fun planSplitByBlankPages(context: Context, sourceFd: ParcelFileDescriptor?, paramsJson: String): String {
        if (sourceFd == null) return JSONObject().put("success", false).put("error", "No source fd").toString()
        
        var renderer: PdfRenderer? = null
        try {
            val params = JSONObject(paramsJson)
            val whiteThreshold = params.optDouble("whiteThreshold", 0.999).toFloat()
            
            renderer = PdfRenderer(sourceFd)
            val totalPages = renderer.pageCount
            
            val splitGroups = mutableListOf<MutableList<Int>>()
            var currentGroup = mutableListOf<Int>()
            val sampleW = 72
            val sampleH = 96
            val sampleBmp = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(sampleW * sampleH)

            try {
                for (i in 0 until totalPages) {
                    var page: PdfRenderer.Page? = null
                    val isBlank = try {
                        page = renderer.openPage(i)
                        sampleBmp.eraseColor(Color.WHITE)
                        page.render(sampleBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        sampleBmp.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

                        var nonWhite = 0
                        for (p in pixels) {
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            if (r < 240 || g < 240 || b < 240) nonWhite++
                        }
                        val whiteRatio = (pixels.size - nonWhite).toFloat() / pixels.size.toFloat()
                        whiteRatio >= whiteThreshold
                    } catch (e: Exception) {
                        false
                    } finally {
                        page?.close()
                    }

                    if (isBlank) {
                        if (currentGroup.isNotEmpty()) {
                            splitGroups.add(currentGroup)
                            currentGroup = mutableListOf()
                        }
                    } else {
                        currentGroup.add(i)
                    }
                }
                if (currentGroup.isNotEmpty()) {
                    splitGroups.add(currentGroup)
                }
            } finally {
                sampleBmp.recycle()
            }
            
            val groupsArray = JSONArray()
            for (g in splitGroups) {
                val arr = JSONArray()
                for (pIdx in g) arr.put(pIdx)
                groupsArray.put(arr)
            }
            return JSONObject().put("success", true).put("groups", groupsArray).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { renderer?.close() } catch (e: Exception) {}
        }
    }

    fun planSplitByBookmarks(context: Context, sourceFd: ParcelFileDescriptor?): String {
        if (sourceFd == null) return JSONObject().put("success", false).put("error", "No source fd").toString()

        var document: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val outline = document!!.documentCatalog.documentOutline
                if (outline == null || outline.firstChild == null) {
                    return JSONObject().put("success", false).put("error", "No bookmarks found").toString()
                }

                val pageIndexMap = HashMap<com.tom_roush.pdfbox.cos.COSBase, Int>(document!!.numberOfPages)
                for (i in 0 until document!!.numberOfPages) {
                    pageIndexMap[document!!.getPage(i).cosObject] = i
                }

                val splitIndexes = mutableListOf<Int>()
                var currentItem = outline.firstChild
                while (currentItem != null) {
                    try {
                        val dest = currentItem.destination ?: (currentItem.action as? com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo)?.destination
                        if (dest is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination) {
                            var pageObj: com.tom_roush.pdfbox.cos.COSBase? = dest.page?.cosObject
                            if (pageObj == null) {
                                val pNum = dest.pageNumber
                                if (pNum >= 0 && pNum < document!!.numberOfPages) {
                                    pageObj = document!!.getPage(pNum).cosObject
                                }
                            }
                            if (pageObj != null) {
                                val pageIdx = pageIndexMap[pageObj]
                                if (pageIdx != null && pageIdx !in splitIndexes) {
                                    splitIndexes.add(pageIdx)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    currentItem = currentItem.nextSibling
                }

                splitIndexes.sort()
                if (splitIndexes.isEmpty()) {
                    return JSONObject().put("success", false).put("error", "Could not resolve bookmark destinations").toString()
                }
                if (splitIndexes[0] != 0) {
                    splitIndexes.add(0, 0)
                }
                splitIndexes.add(document!!.numberOfPages)

                val groupsArray = JSONArray()
                for (i in 0 until splitIndexes.size - 1) {
                    val start = splitIndexes[i]
                    val end = splitIndexes[i + 1]
                    val arr = JSONArray()
                    for (pIdx in start until end) {
                        arr.put(pIdx)
                    }
                    if (arr.length() > 0) {
                        groupsArray.put(arr)
                    }
                }
                return JSONObject().put("success", true).put("groups", groupsArray).toString()
            }
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
        }
    }

    fun deletePages(context: Context, sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?, paramsJson: String): String {
        if (sourceFd == null || targetFd == null) return JSONObject().put("success", false).put("error", "Missing FDs").toString()
        var document: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val pagesToDelete = params.optString("pagesToDelete", "").split(",").mapNotNull { it.toIntOrNull() }
            
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val totalPages = document!!.numberOfPages
                
                val pagesToKeep = (1..totalPages).filter { it !in pagesToDelete }
                
                PDDocument().use { newDoc ->
                    for (p in pagesToKeep) {
                        newDoc.importPage(document!!.getPage(p - 1))
                    }
                    FileOutputStream(targetFd.fileDescriptor).use { out ->
                        newDoc.save(out)
                    }
                }
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
        }
    }

    fun rotatePdf(context: Context, sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?, paramsJson: String): String {
        if (sourceFd == null || targetFd == null) return JSONObject().put("success", false).put("error", "Missing FDs").toString()
        var document: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val degrees = params.optInt("degrees", 0)
            val pagesToRotate = params.optString("pagesToRotate", "").split(",").mapNotNull { it.toIntOrNull() }

            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                
                for (i in 0 until document!!.numberOfPages) {
                    if (pagesToRotate.isEmpty() || (i + 1) in pagesToRotate) {
                        val page = document!!.getPage(i)
                        val rotation = page.rotation
                        page.rotation = rotation + degrees
                    }
                }
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    document!!.save(out)
                }
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
        }
    }

    fun protectPdf(context: Context, sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?, paramsJson: String): String {
        if (sourceFd == null || targetFd == null) return JSONObject().put("success", false).put("error", "Missing FDs").toString()
        var document: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val userPassword = params.optString("userPassword", "")
            var ownerPassword = params.optString("ownerPassword", "")

            // Remove ambiguity: if owner password is empty or identical to user password,
            // opening with user password grants owner rights. Generate a secure random owner password.
            if (ownerPassword.isEmpty() || ownerPassword == userPassword) {
                ownerPassword = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
            }
            
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                
                val accessPermission = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
                accessPermission.setCanPrint(params.optBoolean("canPrint", true))
                accessPermission.setCanPrintDegraded(params.optBoolean("canPrintDegraded", true))
                accessPermission.setCanModify(params.optBoolean("canModify", true))
                accessPermission.setCanModifyAnnotations(params.optBoolean("canModifyAnnotations", true))
                accessPermission.setCanExtractContent(params.optBoolean("canExtractContent", true))
                accessPermission.setCanExtractForAccessibility(params.optBoolean("canExtractForAccessibility", true))
                accessPermission.setCanAssembleDocument(params.optBoolean("canAssembleDocument", true))
                accessPermission.setCanFillInForm(params.optBoolean("canFillInForm", true))

                val spp = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(ownerPassword, userPassword, accessPermission)
                spp.encryptionKeyLength = 256 // Enforce 256-bit AES
                spp.permissions = accessPermission
                
                document!!.protect(spp)
                
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    document!!.save(out)
                }
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
        }
    }

    fun unlockPdf(context: Context, sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?, paramsJson: String): String {
        if (sourceFd == null || targetFd == null) return JSONObject().put("success", false).put("error", "Missing FDs").toString()
        var document: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val password = params.optString("password", "")
            
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, password, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                document!!.isAllSecurityToBeRemoved = true
                
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    document!!.save(out)
                }
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
        }
    }

    fun checkEncryption(context: Context, sourceFd: ParcelFileDescriptor?): String {
        if (sourceFd == null) return JSONObject().put("success", false).put("error", "Missing source Fd").toString()
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                PDDocument.load(inputStream, "", com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    return JSONObject().put("success", true).put("isEncrypted", doc.isEncrypted).toString()
                }
            }
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            return JSONObject().put("success", true).put("isEncrypted", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        }
    }

    fun pdfToImages(context: Context, sourceFd: ParcelFileDescriptor?, targetFds: Array<out ParcelFileDescriptor>, paramsJson: String): String {
        if (sourceFd == null) return JSONObject().put("success", false).put("error", "Missing source Fd").toString()
        var renderer: PdfRenderer? = null
        try {
            val params = JSONObject(paramsJson)
            val quality = params.optInt("quality", 100)
            val formatStr = params.optString("format", "jpeg").lowercase()
            val format = if (formatStr == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val scale = params.optDouble("scale", 2.0).toFloat()

            renderer = PdfRenderer(sourceFd)
            val totalPages = renderer.pageCount
            
            // Render up to the number of targetFds provided
            val count = minOf(totalPages, targetFds.size)
            for (i in 0 until count) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = renderer.openPage(i)
                    val w = (page.width * scale).toInt()
                    val h = (page.height * scale).toInt()
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    
                    FileOutputStream(targetFds[i].fileDescriptor).use { out ->
                        bitmap.compress(format, quality, out)
                    }
                } finally {
                    try { bitmap?.recycle() } catch (e: Exception) {}
                    try { page?.close() } catch (e: Exception) {}
                }
            }
            return JSONObject().put("success", true).put("renderedCount", count).put("totalPages", totalPages).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { renderer?.close() } catch (e: Exception) {}
        }
    }

    fun getPageCount(context: Context, sourceFd: ParcelFileDescriptor): String {
        var renderer: android.graphics.pdf.PdfRenderer? = null
        return try {
            renderer = android.graphics.pdf.PdfRenderer(sourceFd)
            val pageCount = renderer.pageCount
            org.json.JSONObject().put("pageCount", pageCount).toString()
        } catch (e: Exception) {
            org.json.JSONObject().put("pageCount", 0).put("error", e.message).toString()
        } finally {
            renderer?.close()
        }
    }
}
