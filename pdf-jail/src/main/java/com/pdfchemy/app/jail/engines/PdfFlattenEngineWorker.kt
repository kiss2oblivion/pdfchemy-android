package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import java.io.File
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream

object PdfFlattenEngineWorker {

    fun inspect(context: Context, sourceFd: ParcelFileDescriptor): String {
        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null
        try {
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())
                val acroForm = document!!.documentCatalog.acroForm
                val fieldCount = acroForm?.fields?.size ?: 0
                val hasSignatures = document!!.signatureDictionaries.isNotEmpty()

                var annotationCount = 0
                for (page in document!!.pages) {
                    val annots = page.annotations ?: emptyList()
                    annotationCount += annots.count { it !is PDAnnotationWidget }
                }

                val map = mapOf(
                    "fieldCount" to fieldCount,
                    "annotationCount" to annotationCount,
                    "hasSignatures" to hasSignatures
                )
                return Gson().toJson(map)
            }
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    fun flatten(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor, paramsJson: String): String {
        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null
        var intermediateFile: File? = null

        val map = Gson().fromJson(paramsJson, Map::class.java)
        val flattenForms = map["flattenForms"] as? Boolean ?: true
        val flattenAnnotations = map["flattenAnnotations"] as? Boolean ?: true

        try {
            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())
                val acroForm = document!!.documentCatalog.acroForm

                if (flattenForms && acroForm != null) {
                    try {
                        if (acroForm.defaultResources == null) {
                            val dr = com.tom_roush.pdfbox.pdmodel.PDResources()
                            dr.put(COSName.getPDFName("Helv"), com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA)
                            acroForm.defaultResources = dr
                        }
                        acroForm.flatten()
                    } catch (e: Exception) {
                    }
                }

                val pagesToFlatten = mutableSetOf<Int>()
                if (flattenAnnotations) {
                    for ((idx, page) in document!!.pages.withIndex()) {
                        val annots = page.annotations ?: emptyList()
                        val hasVisualAnnotations = annots.any { it !is PDAnnotationWidget }
                        if (hasVisualAnnotations) {
                            pagesToFlatten.add(idx)
                        }
                    }
                }

                if (pagesToFlatten.isNotEmpty()) {
                    intermediateFile = com.pdfchemy.app.jail.JailScratch.createTempFile("intermediate_flatten_", ".pdf", context.cacheDir)
                    com.pdfchemy.app.jail.boundedFileOutput(intermediateFile).use { document!!.save(it) }
                    document!!.close()
                    document = null

                    var pfd: ParcelFileDescriptor? = null
                    var renderer: PdfRenderer? = null
                    try {
                        pfd = com.pdfchemy.app.jail.CapabilityIo.fd(intermediateFile)
                        renderer = PdfRenderer(pfd)
                        val baseDoc = com.pdfchemy.app.jail.CapabilityIo.input(intermediateFile).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
                        val bakedDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())

                        for (i in 0 until renderer.pageCount) {
                            if (pagesToFlatten.contains(i)) {
                                var renderPage: PdfRenderer.Page? = null
                                var bmp: Bitmap? = null
                                try {
                                    renderPage = renderer.openPage(i)
                                    val maxDim = 2048
                                    val scale = minOf(2f, maxDim.toFloat() / maxOf(renderPage.width, renderPage.height).coerceAtLeast(1))
                                    val targetW = (renderPage.width * scale).toInt().coerceAtLeast(1)
                                    val targetH = (renderPage.height * scale).toInt().coerceAtLeast(1)
                                    bmp = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(targetW, targetH); Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565) }
                                    val canvas = android.graphics.Canvas(bmp)
                                    canvas.drawColor(android.graphics.Color.WHITE)

                                    renderPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                    val pdfPage = PDPage(PDRectangle(renderPage.width.toFloat(), renderPage.height.toFloat()))
                                    bakedDoc.addPage(pdfPage)

                                    val pdImage = JPEGFactory.createFromImage(bakedDoc, bmp, 0.95f)
                                    PDPageContentStream(bakedDoc, pdfPage).use { cs ->
                                        cs.drawImage(pdImage, 0f, 0f, renderPage.width.toFloat(), renderPage.height.toFloat())
                                    }
                                } finally {
                                    bmp?.recycle()
                                    renderPage?.close()
                                }
                            } else {
                                if (i < baseDoc.numberOfPages) {
                                    bakedDoc.importPage(baseDoc.getPage(i))
                                }
                            }
                        }
                        baseDoc.close()
                        document = bakedDoc
                    } catch (e: Exception) {
                        document = com.pdfchemy.app.jail.CapabilityIo.input(intermediateFile).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
                    } finally {
                        try { renderer?.close() } catch (_: Exception) {}
                        try { pfd?.close() } catch (_: Exception) {}
                    }
                }

                FileOutputStream(destFd.fileDescriptor).use { outputStream ->
                    document!!.save(outputStream)
                }

                return "{}"
            }
        } finally {
            try { document?.close() } catch (_: Exception) {}
            intermediateFile?.delete()
        }
    }
}
