package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfBookmarkEngineWorker {

    fun readBookmarks(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val outline = document.documentCatalog.documentOutline
            val arr = JSONArray()
            if (outline != null) {
                var current = outline.firstChild
                while (current != null) {
                    if (arr.length() >= JailQuotas.MAX_BOOKMARKS) {
                        break // Enforce item limit
                    }
                    val title = current.title ?: "Untitled Bookmark"
                    var pageIndex = 0
                    try {
                        val dest = current.destination
                        if (dest is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination) {
                            val destPage = dest.page
                            if (destPage != null) {
                                pageIndex = document.pages.indexOf(destPage).coerceAtLeast(0)
                            }
                        }
                    } catch (_: Exception) {}

                    val obj = JSONObject()
                    obj.put("title", title)
                    obj.put("pageIndex", pageIndex)
                    arr.put(obj)
                    
                    current = current.nextSibling
                }
            }
            return arr.toString()
        } finally {
            try { document?.close() } catch(_: Exception){}
        }
    }

    fun writeBookmarks(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor, paramsJson: String): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val totalPages = document.numberOfPages
            if (totalPages == 0) throw IllegalStateException("PDF contains no pages")
            
            val arr = JSONArray(paramsJson)
            JailQuotas.enforceItemCount(arr.length(), JailQuotas.MAX_BOOKMARKS, "Write Bookmarks")

            if (arr.length() == 0) {
                document.documentCatalog.documentOutline = null
            } else {
                val outline = PDDocumentOutline()
                document.documentCatalog.documentOutline = outline

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val safePageIdx = item.getInt("pageIndex").coerceIn(0, totalPages - 1)
                    val page = document.getPage(safePageIdx)

                    val outlineItem = PDOutlineItem().apply {
                        title = item.getString("title")
                        val dest = PDPageFitWidthDestination().apply {
                            this.page = page
                            top = 0
                        }
                        destination = dest
                    }
                    outline.addLast(outlineItem)
                }
                outline.openNode()
            }
            
            document.save(FileOutputStream(destFd.fileDescriptor))
            return "{}"
        } finally {
            try { document?.close() } catch(_: Exception){}
        }
    }
}
