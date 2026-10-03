package com.pdfchemy.app.jail.engines

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import java.io.File
import android.util.Base64

object SignatureEngineWorker {

    fun applySignatures(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, imagesFd: ParcelFileDescriptor, paramsJson: String): String {
        var doc: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val signaturesArr = params.optJSONArray("signatures") ?: org.json.JSONArray()
            
            doc = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            val totalPages = doc.numberOfPages

            JailQuotas.enforceItemCount(signaturesArr.length(), com.pdfchemy.app.security.SecurityLimits.MAX_SIGNATURES)
            java.io.DataInputStream(FileInputStream(imagesFd.fileDescriptor)).use { images ->
            for (i in 0 until signaturesArr.length()) {
                val sigObj = signaturesArr.getJSONObject(i)
                val pageIdx = sigObj.getInt("pageIndex")
                val xRatio = sigObj.getDouble("xRatio").toFloat()
                val yRatio = sigObj.getDouble("yRatio").toFloat()
                val widthRatio = sigObj.getDouble("widthRatio").toFloat()
                val heightRatio = sigObj.getDouble("heightRatio").toFloat()
                val dateStamp = sigObj.optString("dateStamp", null)
                val imageSize = images.readInt()
                require(imageSize in 1..com.pdfchemy.app.security.SecurityLimits.MAX_SIGNATURE_BYTES)
                val bitmapBytes = ByteArray(imageSize)
                images.readFully(bitmapBytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.size, bounds)
                com.pdfchemy.app.security.SecurityLimits.requirePixels(bounds.outWidth, bounds.outHeight)

                if (pageIdx in 0 until totalPages) {
                    val page = doc.getPage(pageIdx)
                    val mediaBox = page.cropBox ?: page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height
                    val lowerLeftX = mediaBox.lowerLeftX
                    val lowerLeftY = mediaBox.lowerLeftY
                    val rotation = page.rotation

                    val dispW = if (rotation == 90 || rotation == 270) pageHeight else pageWidth
                    val dispH = if (rotation == 90 || rotation == 270) pageWidth else pageHeight

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        val sigBmp = BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.size)
                        if (sigBmp != null) {
                            val pdImage = LosslessFactory.createFromImage(doc, sigBmp)
                            val stampW = (widthRatio * dispW).coerceAtLeast(40f)
                            val stampH = (heightRatio * dispH).coerceAtLeast(20f)
                            val stampX = xRatio * dispW
                            val stampY = dispH - (yRatio * dispH) - stampH

                            cs.saveGraphicsState()
                            when (rotation) {
                                90 -> cs.transform(Matrix(0f, 1f, -1f, 0f, lowerLeftX + pageWidth, lowerLeftY))
                                180 -> cs.transform(Matrix(-1f, 0f, 0f, -1f, lowerLeftX + pageWidth, lowerLeftY + pageHeight))
                                270 -> cs.transform(Matrix(0f, -1f, 1f, 0f, lowerLeftX, lowerLeftY + pageHeight))
                                else -> cs.transform(Matrix(1f, 0f, 0f, 1f, lowerLeftX, lowerLeftY))
                            }

                            cs.drawImage(pdImage, stampX, stampY, stampW, stampH)

                            if (!dateStamp.isNullOrBlank()) {
                                cs.beginText()
                                cs.setFont(PDType1Font.HELVETICA_BOLD, 9f)
                                cs.setNonStrokingColor(0, 0, 0)
                                cs.newLineAtOffset(stampX, stampY - 12f)
                                cs.showText("Signed: $dateStamp")
                                cs.endText()
                            }

                            cs.restoreGraphicsState()
                            sigBmp.recycle()
                        }
                    }
                }
            }

            require(images.read() == -1) { "Trailing signature image payload" }
            }
            FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                doc.save(outStream)
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { doc?.close() } catch (e: Exception) {}
        }
    }

    fun applyDigitalSignature(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        var sourceFile: File? = null
        var destFile: File? = null
        try {
            val params = JSONObject(paramsJson)
            val signerName = params.getString("signerName")
            val reason = params.getString("reason")
            val location = params.getString("location")

            val cacheDir = File(System.getProperty("java.io.tmpdir"))
            sourceFile = com.pdfchemy.app.jail.JailScratch.createTempFile("temp_sign_in", ".pdf", cacheDir)
            destFile = com.pdfchemy.app.jail.JailScratch.createTempFile("temp_sign_out", ".pdf", cacheDir)
            
            FileInputStream(sourceFd.fileDescriptor).use { ins ->
                com.pdfchemy.app.jail.boundedFileOutput(sourceFile).use { fos ->
                    ins.copyTo(fos)
                }
            }

            val subjectStr = signerName
            val keyPairInfo = AndroidPdfCryptoSigner.generateSelfSignedCertificate(subjectStr)

            AndroidPdfCryptoSigner.signPdf(
                sourceFile = sourceFile,
                destFile = destFile,
                keyPairInfo = keyPairInfo,
                reason = reason,
                location = location
            )

            FileOutputStream(targetFd.fileDescriptor).use { outs ->
                com.pdfchemy.app.jail.CapabilityIo.input(destFile).use { fis ->
                    fis.copyTo(outs)
                }
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            sourceFile?.delete()
            destFile?.delete()
        }
    }
}
