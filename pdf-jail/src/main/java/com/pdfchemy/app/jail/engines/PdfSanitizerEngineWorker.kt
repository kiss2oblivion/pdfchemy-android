package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfSanitizerEngineWorker {

    fun audit(sourceFd: ParcelFileDescriptor): String {
        var doc: PDDocument? = null
        try {
            val memSettings = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(10 * 1024 * 1024, 250 * 1024 * 1024)
            try {
                FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                    doc = PDDocument.load(inStream, "", memSettings)
                }
            } catch (e: InvalidPasswordException) {
                return JSONObject().apply {
                    put("threatsFound", 1)
                    put("isClean", false)
                    put("isEncrypted", true)
                    put("parseFailed", false)
                }.toString()
            } catch (e: Exception) {
                return JSONObject().apply {
                    put("threatsFound", 1)
                    put("isClean", false)
                    put("isEncrypted", false)
                    put("parseFailed", true)
                }.toString()
            }

            if (doc!!.isEncrypted) {
                return JSONObject().apply {
                    put("threatsFound", 1)
                    put("isClean", false)
                    put("isEncrypted", true)
                    put("parseFailed", false)
                }.toString()
            }

            var jsCount = 0
            var actionCount = 0
            var attachmentCount = 0
            var uriCount = 0

            if (doc!!.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("JavaScript")) != null) jsCount++
            if (doc!!.documentCatalog.names?.cosObject?.getDictionaryObject(COSName.getPDFName("JavaScript")) != null) jsCount++

            val processAction: (COSDictionary) -> Unit = { actionDict ->
                val s = actionDict.getNameAsString(COSName.S)
                if (s == "JavaScript") jsCount++
                else if (s in listOf("Launch", "SubmitForm", "ImportData", "Sound", "Movie", "GoToE", "GoToR")) actionCount++
                else if (s == "URI") uriCount++
            }

            walkActions(doc!!.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction")), mutableSetOf(), processAction)
            scanAaDictionary(doc!!.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AA")), processAction)

            val acroForm = doc!!.documentCatalog.acroForm
            if (acroForm != null) {
                scanAaDictionary(acroForm.cosObject.getDictionaryObject(COSName.getPDFName("AA")), processAction)
                for (field in acroForm.fieldTree) {
                    scanAaDictionary(field.cosObject.getDictionaryObject(COSName.getPDFName("AA")), processAction)
                }
            }

            if (doc!!.documentCatalog.names?.cosObject?.getDictionaryObject(COSName.getPDFName("EmbeddedFiles")) != null) attachmentCount++

            for (page in doc!!.pages) {
                scanAaDictionary(page.cosObject.getDictionaryObject(COSName.getPDFName("AA")), processAction)
                for (annot in page.annotations) {
                    walkActions(annot.cosObject.getDictionaryObject(COSName.A), mutableSetOf(), processAction)
                    scanAaDictionary(annot.cosObject.getDictionaryObject(COSName.getPDFName("AA")), processAction)
                }
            }

            val info = doc!!.documentInformation
            val hasMeta = info != null && (!info.author.isNullOrBlank() || !info.title.isNullOrBlank() || !info.creator.isNullOrBlank())
            val totalThreats = jsCount + actionCount + attachmentCount + uriCount + (if (hasMeta) 1 else 0)

            return JSONObject().apply {
                put("threatsFound", totalThreats)
                put("jsCount", jsCount)
                put("launchActionsCount", actionCount)
                put("attachmentCount", attachmentCount)
                put("uriCount", uriCount)
                put("hasMetadata", hasMeta)
                put("isClean", totalThreats == 0)
                put("isEncrypted", false)
                put("parseFailed", false)
            }.toString()

        } catch (e: Exception) {
            val isEnc = e is InvalidPasswordException || e.cause is InvalidPasswordException
            return JSONObject().apply {
                put("threatsFound", 1)
                put("isClean", false)
                put("isEncrypted", isEnc)
                put("parseFailed", !isEnc)
            }.toString()
        } finally {
            try { doc?.close() } catch (e: Exception) {}
        }
    }

    fun sanitize(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val purgeJs = params.optBoolean("purgeJs", true)
        val purgeActions = params.optBoolean("purgeActions", true)
        val purgeMetadata = params.optBoolean("purgeMetadata", true)
        val purgeAttachments = params.optBoolean("purgeAttachments", true)

        var doc: PDDocument? = null
        try {
            val memSettings = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(10 * 1024 * 1024, 250 * 1024 * 1024)
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                doc = PDDocument.load(inStream, memSettings)
            }

            var jsPurged = 0
            var actionsPurged = 0
            var attachmentsPurged = 0

            if (purgeJs) {
                if (doc!!.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("JavaScript")) != null) {
                    doc!!.documentCatalog.cosObject.removeItem(COSName.getPDFName("JavaScript"))
                    jsPurged++
                }
                if (doc!!.documentCatalog.names?.cosObject?.getDictionaryObject(COSName.getPDFName("JavaScript")) != null) {
                    doc!!.documentCatalog.names?.cosObject?.removeItem(COSName.getPDFName("JavaScript"))
                    jsPurged++
                }
            }

            if (purgeJs || purgeActions) {
                val openAction = doc!!.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction"))
                if (hasMaliciousAction(openAction, purgeJs, purgeActions, mutableSetOf())) {
                    doc!!.documentCatalog.cosObject.removeItem(COSName.getPDFName("OpenAction"))
                    actionsPurged++
                }
                
                if (hasMaliciousAa(doc!!.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AA")), purgeJs, purgeActions)) {
                    doc!!.documentCatalog.cosObject.removeItem(COSName.getPDFName("AA"))
                    actionsPurged++
                }
                
                val acroForm = doc!!.documentCatalog.acroForm
                if (acroForm != null) {
                    if (hasMaliciousAa(acroForm.cosObject.getDictionaryObject(COSName.getPDFName("AA")), purgeJs, purgeActions)) {
                        acroForm.cosObject.removeItem(COSName.getPDFName("AA"))
                        actionsPurged++
                    }
                    for (field in acroForm.fieldTree) {
                        if (hasMaliciousAa(field.cosObject.getDictionaryObject(COSName.getPDFName("AA")), purgeJs, purgeActions)) {
                            field.cosObject.removeItem(COSName.getPDFName("AA"))
                            actionsPurged++
                        }
                    }
                }
            }

            if (purgeAttachments) {
                if (doc!!.documentCatalog.names?.cosObject?.getDictionaryObject(COSName.getPDFName("EmbeddedFiles")) != null) {
                    doc!!.documentCatalog.names?.cosObject?.removeItem(COSName.getPDFName("EmbeddedFiles"))
                    attachmentsPurged++
                }
            }

            if (purgeMetadata) {
                doc!!.documentInformation.title = null
                doc!!.documentInformation.author = null
                doc!!.documentInformation.subject = null
                doc!!.documentInformation.keywords = null
                doc!!.documentInformation.creator = null
                doc!!.documentInformation.producer = "PDFchemy (Scrubbed & Sanitized)"
                doc!!.documentInformation.creationDate = null
                doc!!.documentInformation.modificationDate = null
                doc!!.documentCatalog.metadata = null
                doc!!.document.trailer.removeItem(COSName.ID)
                doc!!.documentCatalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            }

            for (page in doc!!.pages) {
                page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))

                if (purgeActions || purgeJs) {
                    if (hasMaliciousAa(page.cosObject.getDictionaryObject(COSName.getPDFName("AA")), purgeJs, purgeActions)) {
                        page.cosObject.removeItem(COSName.getPDFName("AA"))
                        actionsPurged++
                    }
                    
                    for (annot in page.annotations) {
                        if (hasMaliciousAa(annot.cosObject.getDictionaryObject(COSName.getPDFName("AA")), purgeJs, purgeActions)) {
                            annot.cosObject.removeItem(COSName.getPDFName("AA"))
                            actionsPurged++
                        }
                        
                        val action = annot.cosObject.getDictionaryObject(COSName.A)
                        if (hasMaliciousAction(action, purgeJs, purgeActions, mutableSetOf())) {
                            annot.cosObject.removeItem(COSName.A)
                            actionsPurged++
                        }
                    }
                }
            }

            FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                doc!!.save(outStream)
            }

            return JSONObject().apply {
                put("isSuccess", true)
                put("threatsRemoved", jsPurged + actionsPurged + attachmentsPurged + (if (purgeMetadata) 1 else 0))
                put("jsRemoved", jsPurged)
                put("actionsRemoved", actionsPurged)
                put("metadataRemoved", purgeMetadata)
                put("attachmentsRemoved", attachmentsPurged)
            }.toString()
        } catch (e: Exception) {
            return JSONObject().apply {
                put("isSuccess", false)
                put("threatsRemoved", 0)
                put("jsRemoved", 0)
                put("actionsRemoved", 0)
                put("metadataRemoved", false)
                put("attachmentsRemoved", 0)
            }.toString()
        } finally {
            try { doc?.close() } catch (e: Exception) {}
        }
    }

    private fun walkActions(
        actionObj: com.tom_roush.pdfbox.cos.COSBase?,
        visited: MutableSet<com.tom_roush.pdfbox.cos.COSBase> = mutableSetOf(),
        onAction: (COSDictionary) -> Unit
    ) {
        if (actionObj == null) return
        if (!visited.add(actionObj)) return

        when (actionObj) {
            is COSDictionary -> {
                onAction(actionObj)
                walkActions(actionObj.getDictionaryObject(COSName.getPDFName("Next")), visited, onAction)
            }
            is com.tom_roush.pdfbox.cos.COSArray -> {
                for (i in 0 until actionObj.size()) {
                    walkActions(actionObj.getObject(i), visited, onAction)
                }
            }
        }
    }

    private fun scanAaDictionary(
        aaObj: com.tom_roush.pdfbox.cos.COSBase?,
        onAction: (COSDictionary) -> Unit
    ) {
        if (aaObj is COSDictionary) {
            for (key in aaObj.keySet()) {
                walkActions(aaObj.getDictionaryObject(key), mutableSetOf(), onAction)
            }
        }
    }

    private fun hasMaliciousAction(
        actionObj: com.tom_roush.pdfbox.cos.COSBase?,
        checkJs: Boolean,
        checkActions: Boolean,
        visited: MutableSet<com.tom_roush.pdfbox.cos.COSBase> = mutableSetOf()
    ): Boolean {
        if (actionObj == null) return false
        if (!visited.add(actionObj)) return false

        when (actionObj) {
            is COSDictionary -> {
                val s = actionObj.getNameAsString(COSName.S)
                if (checkJs && s == "JavaScript") return true
                if (checkActions && s in listOf("Launch", "SubmitForm", "ImportData", "GoToE", "GoToR", "URI", "Sound", "Movie")) return true
                return hasMaliciousAction(actionObj.getDictionaryObject(COSName.getPDFName("Next")), checkJs, checkActions, visited)
            }
            is com.tom_roush.pdfbox.cos.COSArray -> {
                for (i in 0 until actionObj.size()) {
                    if (hasMaliciousAction(actionObj.getObject(i), checkJs, checkActions, visited)) return true
                }
            }
        }
        return false
    }

    private fun hasMaliciousAa(
        aaObj: com.tom_roush.pdfbox.cos.COSBase?,
        checkJs: Boolean,
        checkActions: Boolean
    ): Boolean {
        if (aaObj !is COSDictionary) return false
        for (key in aaObj.keySet()) {
            if (hasMaliciousAction(aaObj.getDictionaryObject(key), checkJs, checkActions, mutableSetOf())) return true
        }
        return false
    }
}
