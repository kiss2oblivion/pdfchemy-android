package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream

object PdfPageOrganizerWorker {

    fun reorganizePages(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor, paramsJson: String): String {
        var sourceDoc: PDDocument? = null
        var newDoc: PDDocument? = null
        try {
            sourceDoc = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            newDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())
            val totalOriginalPages = sourceDoc.numberOfPages

            val params = JSONObject(paramsJson)
            val actionsArr = params.getJSONArray("actions")

            for (i in 0 until actionsArr.length()) {
                val actionObj = actionsArr.getJSONObject(i)
                val isBlank = actionObj.optBoolean("isBlank", false)
                val rotationDegrees = actionObj.optInt("rotationDegrees", 0)
                
                if (isBlank) {
                    val blankPage = PDPage(PDRectangle.LETTER)
                    newDoc.addPage(blankPage)
                } else if (actionObj.has("originalPageIndex")) {
                    val originalPageIndex = actionObj.getInt("originalPageIndex")
                    if (originalPageIndex in 0 until totalOriginalPages) {
                        val originalPage = sourceDoc.getPage(originalPageIndex)
                        val importedPage = newDoc.importPage(originalPage)
                        val newRotation = (importedPage.rotation + rotationDegrees) % 360
                        importedPage.rotation = if (newRotation < 0) newRotation + 360 else newRotation
                    }
                }
            }

            if (newDoc.numberOfPages == 0) throw IllegalStateException("No pages in resulting document")

            FileOutputStream(destFd.fileDescriptor).use { newDoc.save(it) }
            return "{}"
        } finally {
            try { sourceDoc?.close() } catch (_: Exception) {}
            try { newDoc?.close() } catch (_: Exception) {}
        }
    }
}
