package com.pdfchemy.desktop.jail

import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object DesktopJailOperations {
    
    fun compressPdf(
        inputStream: java.io.InputStream,
        outputStream: java.io.OutputStream,
        targetDpi: Float = 140f,
        quality: Float = 0.7f,
        rasterizePages: Boolean = false
    ) {
        if (rasterizePages) {
            compressRasterizedPages(inputStream, outputStream, targetDpi, quality)
        } else {
            PDDocument.load(inputStream, org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val totalPages = document.numberOfPages
                val maxDimension = (11.7f * targetDpi).toInt().coerceAtLeast(600)
                val compressedXObjectCache = mutableMapOf<COSBase, PDImageXObject>()

                for (pageIndex in 0 until totalPages) {
                    System.err.println("PROGRESS:/")
                    val page = document.getPage(pageIndex)
                    val resources = page.resources ?: continue
                    compressResources(resources, document, maxDimension, quality, compressedXObjectCache)
                }

                document.save(outputStream)
            }
        }
    }

    private fun compressResources(
        resources: PDResources,
        document: PDDocument,
        maxDimension: Int,
        quality: Float,
        compressedXObjectCache: MutableMap<COSBase, PDImageXObject>
    ) {
        for (name in resources.xObjectNames) {
            val xObject = try { resources.getXObject(name) } catch (_: Throwable) { null } ?: continue
            val cosObj = xObject.cosObject

            if (cosObj != null && compressedXObjectCache.containsKey(cosObj)) {
                resources.put(name, compressedXObjectCache[cosObj]!!)
                continue
            }

            if (xObject is PDImageXObject) {
                val origImage = try { xObject.image } catch (_: Throwable) { null } ?: continue
                val origW = origImage.width
                val origH = origImage.height
                if (origW <= 0 || origH <= 0) continue

                val scale = if (origW > maxDimension || origH > maxDimension) {
                    minOf(maxDimension.toFloat() / origW, maxDimension.toFloat() / origH)
                } else {
                    1.0f
                }

                val targetW = (origW * scale).toInt().coerceAtLeast(1)
                val targetH = (origH * scale).toInt().coerceAtLeast(1)

                val scaledImage = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
                val g2d = scaledImage.createGraphics()
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2d.drawImage(origImage, 0, 0, targetW, targetH, null)
                g2d.dispose()

                val newImage = JPEGFactory.createFromImage(document, scaledImage, quality, 150)
                resources.put(name, newImage)
                if (cosObj != null) compressedXObjectCache[cosObj] = newImage
            } else if (xObject is PDFormXObject) {
                val formResources = xObject.resources
                if (formResources != null) {
                    compressResources(formResources, document, maxDimension, quality, compressedXObjectCache)
                }
            }
        }
    }

    private fun compressRasterizedPages(
        inputStream: java.io.InputStream,
        outputStream: java.io.OutputStream,
        targetDpi: Float,
        quality: Float
    ) {
        PDDocument.load(inputStream, org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { sourceDoc ->
            val totalPages = sourceDoc.numberOfPages
            PDDocument().use { outDoc ->
                val renderer = PDFRenderer(sourceDoc)
                for (pageIndex in 0 until totalPages) {
                    System.err.println("PROGRESS:/")
                    val origPage = sourceDoc.getPage(pageIndex)
                    val newPage = PDPage(origPage.mediaBox)
                    outDoc.addPage(newPage)
                    
                    val image = renderer.renderImageWithDPI(pageIndex, targetDpi, org.apache.pdfbox.rendering.ImageType.RGB)
                    val pdImage = JPEGFactory.createFromImage(outDoc, image, quality, targetDpi.toInt())
                    
                    org.apache.pdfbox.pdmodel.PDPageContentStream(outDoc, newPage).use { cs ->
                        cs.drawImage(pdImage, 0f, 0f, origPage.mediaBox.width, origPage.mediaBox.height)
                    }
                }
                outDoc.save(outputStream)
            }
        }
    }
}
