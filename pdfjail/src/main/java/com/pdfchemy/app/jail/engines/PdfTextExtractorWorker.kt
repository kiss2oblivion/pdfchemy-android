package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.runBlocking

object PdfTextExtractorWorker {

    fun extractText(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = org.json.JSONObject(paramsJson)
        val forceOcr = params.optBoolean("forceOcr", false)
        
        var extractedText = if (forceOcr) "" else extractUsingPdfBox(sourceFd)

        if (extractedText.trim().length < 50) {
            extractedText = extractUsingOcr(context, sourceFd)
        
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}

        if (extractedText.isNotBlank()) {
            FileOutputStream(destFd.fileDescriptor).use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(extractedText)
                
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
            
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
        
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
        return "{}"
    
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}

    private fun extractUsingPdfBox(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        return try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            
            var totalBytes = 0
            val sb = java.io.StringWriter()

            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<com.tom_roush.pdfbox.text.TextPosition>?) {
                    val bytes = text.toByteArray(Charsets.UTF_8).size
                    if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                        // Exceeded quota, stop extracting
                        throw SecurityException("Text extraction exceeded limit.")
                    
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
                    totalBytes += bytes
                    super.writeString(text, textPositions)
                
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
            
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}

            try {
                stripper.writeText(document, sb)
            } catch (e: SecurityException) {
                // Return what we have so far
            
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
            sb.toString()
        } catch (e: Exception) {
            ""
        } finally {
            try { document?.close() } catch(_: Exception){
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
        
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
    
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}

    private fun extractUsingOcr(context: Context, sourceFd: ParcelFileDescriptor): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val stringBuilder = java.lang.StringBuilder()
        var pdfRenderer: PdfRenderer? = null
        var totalBytes = 0

        try {
            pdfRenderer = PdfRenderer(sourceFd)
            val pageCount = pdfRenderer.pageCount

            for (i in 0 until pageCount) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = pdfRenderer.openPage(i)
                    val width = context.resources.displayMetrics.densityDpi / 72 * page.width
                    val height = context.resources.displayMetrics.densityDpi / 72 * page.height
                    
                    bitmap = Bitmap.createBitmap(
                        if (width > 0) width else page.width * 2,
                        if (height > 0) height else page.height * 2,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    
                    val image = InputImage.fromBitmap(bitmap, 0)
                    
                    runBlocking {
                        try {
                            val result = recognizer.process(image).await()
                            val text = result.text
                            val bytes = text.toByteArray(Charsets.UTF_8).size
                            if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                                throw SecurityException("OCR text exceeded limit")
                            
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
                            totalBytes += bytes
                            stringBuilder.append(text).append("\n\n")
                        } catch (e: Exception) {
                            if (e is SecurityException) throw e
                        
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
                    
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
                } finally {
                    bitmap?.recycle()
                    page?.close()
                
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
            
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
        } catch (e: Exception) {
            // Stop parsing and return what we have
        } finally {
            try { pdfRenderer?.close() } catch (_: Exception) {
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
            try { recognizer.close() } catch (_: Exception) {
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
        
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
        return stringBuilder.toString()
    
    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}

    fun extractAllPagesText(document: PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()
        var totalBytes = 0

        val stripper = object : PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                val text = currentWriter.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (totalBytes + bytes > JailQuotas.MAX_TEXT_BYTES) {
                    throw SecurityException("Text extraction exceeded limit")
                }
                totalBytes += bytes
                pagesText.add(text)
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        
        return pagesText
    }
}
