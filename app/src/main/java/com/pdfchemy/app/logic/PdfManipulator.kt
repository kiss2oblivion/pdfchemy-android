// =================================================================================================
// [FEATURE: Page Studio & PDF Manipulation Engine] (FEATURES_REGISTRY Android §2 & §5)
// Merge, split, delete, rotate, reorder, blank-page split, bookmark split, protect, and unlock.
// =================================================================================================

package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object PdfManipulator {

    suspend fun mergePdfs(context: Context, sourceUris: List<Uri>, outputUri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                PdfGateway.executeEngineBatch(
                    context,
                    "MERGE",
                    sourceUris,
                    listOf(outputUri),
                    "{}"
                )
            } catch (e: Exception) {
                com.pdfchemy.app.utils.AppLogger.e("Failed to merge PDFs via Gateway", e)
                throw e
            }
        }
    }

    suspend fun splitPdf(context: Context, sourceUri: Uri, outputDirectory: DocumentFile, baseName: String, pageRange: String? = null) {
        withContext(Dispatchers.IO) {
            var pagesToKeep = emptySet<Int>()
            // Need a fast way to get total pages. If we don't know it, we have a problem.
            var totalPages = getPageCountFromGateway(context, sourceUri)
            if (totalPages == 0) return@withContext
            
            pagesToKeep = parsePageRange(pageRange, totalPages)
            if (pagesToKeep.isEmpty()) return@withContext

            val destUris = mutableListOf<Uri>()
            val pagesList = pagesToKeep.toList()
            for (pageNumber in pagesList) {
                val fileName = "${baseName}_page_${pageNumber}.pdf"
                val newFile = outputDirectory.createFile("application/pdf", fileName)
                if (newFile != null) {
                    destUris.add(newFile.uri)
                }
            }

            try {
                val paramsJson = org.json.JSONObject().apply {
                    put("pagesToKeep", pagesList.joinToString(","))
                }.toString()

                PdfGateway.executeEngineBatch(
                    context,
                    "SPLIT",
                    listOf(sourceUri),
                    destUris,
                    paramsJson
                )
            } catch (e: Exception) {
                com.pdfchemy.app.utils.AppLogger.e("Failed to split PDF via Gateway", e)
                throw e
            }
        }
    }

    suspend fun splitByBlankPages(
        context: Context,
        sourceUri: Uri,
        outputDirectory: DocumentFile,
        baseName: String,
        whiteThreshold: Float = 0.999f
    ): List<Uri> = withContext(Dispatchers.IO) {
        val outputUris = mutableListOf<Uri>()
        try {
            val paramsJson = org.json.JSONObject().apply {
                put("whiteThreshold", whiteThreshold.toDouble())
            }.toString()
            val planJson = PdfGateway.executeEngine(context, "PLAN_SPLIT_BLANK", sourceUri, null, paramsJson)
            
            val plan = org.json.JSONObject(planJson)
            if (!plan.optBoolean("success")) {
                throw Exception(plan.optString("error", "Unknown error in PLAN_SPLIT_BLANK"))
            }

            val groupsArray = plan.optJSONArray("groups") ?: return@withContext emptyList()

            for (i in 0 until groupsArray.length()) {
                val fileName = "${baseName}_part_${i + 1}.pdf"
                val newFile = outputDirectory.createFile("application/pdf", fileName)
                if (newFile != null) {
                    outputUris.add(newFile.uri)
                }
            }

            if (outputUris.isNotEmpty()) {
                val splitParamsJson = org.json.JSONObject().apply {
                    put("multiGroups", groupsArray.toString())
                }.toString()
                PdfGateway.executeEngineBatch(context, "SPLIT", listOf(sourceUri), outputUris, splitParamsJson)
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Error splitting by blank pages via Gateway", e)
        }
        outputUris
    }

    suspend fun splitByBookmarks(
        context: Context,
        sourceUri: Uri,
        outputDirectory: DocumentFile,
        baseName: String
    ): List<Uri> = withContext(Dispatchers.IO) {
        val outputUris = mutableListOf<Uri>()
        try {
            val planJson = PdfGateway.executeEngine(context, "PLAN_SPLIT_BOOKMARKS", sourceUri, null, "{}")
            
            val plan = org.json.JSONObject(planJson)
            if (!plan.optBoolean("success")) {
                throw Exception(plan.optString("error", "Unknown error in PLAN_SPLIT_BOOKMARKS"))
            }

            val groupsArray = plan.optJSONArray("groups") ?: return@withContext emptyList()

            for (i in 0 until groupsArray.length()) {
                val fileName = "${baseName}_part_${i + 1}.pdf"
                val newFile = outputDirectory.createFile("application/pdf", fileName)
                if (newFile != null) {
                    outputUris.add(newFile.uri)
                }
            }

            if (outputUris.isNotEmpty()) {
                val splitParamsJson = org.json.JSONObject().apply {
                    put("multiGroups", groupsArray.toString())
                }.toString()
                PdfGateway.executeEngineBatch(context, "SPLIT", listOf(sourceUri), outputUris, splitParamsJson)
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Error splitting by bookmarks via Gateway", e)
        }
        outputUris
    }

    suspend fun deletePages(context: Context, sourceUri: Uri, destUri: Uri, pageRange: String) {
        withContext(Dispatchers.IO) {
            try {
                // We need to parse pageRange here or in the worker. The worker can do it if we pass it, but wait, worker doesn't have totalPages yet.
                // We'll pass total pages to the worker? Wait, the worker has the PDDocument so it knows total pages.
                // But worker's deletePages doesn't support complex range parsing yet.
                // Actually, I can use parsePageRange here and pass the explicit list to the worker!
                
                val totalPages = getPageCountFromGateway(context, sourceUri)
                
                val pagesToDelete = parsePageRange(pageRange, totalPages)
                if (pagesToDelete.size >= totalPages) {
                    throw IllegalArgumentException("Cannot delete all pages. A PDF must contain at least one page.")
                }

                val paramsJson = org.json.JSONObject().apply {
                    put("pagesToDelete", pagesToDelete.joinToString(","))
                }.toString()
                
                PdfGateway.executeEngine(context, "DELETE_PAGES", sourceUri, destUri, paramsJson)
            } catch (e: Exception) {
                com.pdfchemy.app.utils.AppLogger.e("Failed to delete pages via Gateway", e)
                throw e
            }
        }
    }

    suspend fun rotatePdf(context: Context, sourceUri: Uri, destUri: Uri, degrees: Int, pageRange: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val totalPages = getPageCountFromGateway(context, sourceUri)

                val pagesToRotate = parsePageRange(pageRange, totalPages)
                val paramsJson = org.json.JSONObject().apply {
                    put("degrees", degrees)
                    put("pagesToRotate", pagesToRotate.joinToString(","))
                }.toString()
                
                PdfGateway.executeEngine(context, "ROTATE", sourceUri, destUri, paramsJson)
            } catch (e: Exception) {
                com.pdfchemy.app.utils.AppLogger.e("Failed to rotate PDF via Gateway", e)
                throw e
            }
        }
    }

    suspend fun protectPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        userPassword: String,
        ownerPassword: String = userPassword,
        keyLength: Int = 256
    ) = withContext(Dispatchers.IO) {
        try {
            val paramsJson = org.json.JSONObject().apply {
                put("userPassword", userPassword)
                put("ownerPassword", ownerPassword)
                put("keyLength", keyLength)
            }.toString()
            PdfGateway.executeEngine(context, "PROTECT", sourceUri, destUri, paramsJson)
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Failed to protect PDF via Gateway", e)
            throw e
        }
    }

    suspend fun unlockPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        password: String
    ) = withContext(Dispatchers.IO) {
        try {
            val paramsJson = org.json.JSONObject().apply {
                put("password", password)
            }.toString()
            PdfGateway.executeEngine(context, "UNLOCK", sourceUri, destUri, paramsJson)
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Failed to unlock PDF via Gateway", e)
            throw e
        }
    }

    suspend fun isPdfPasswordProtected(context: Context, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val resultJson = PdfGateway.executeEngine(context, "CHECK_ENCRYPTION", sourceUri, null, "{}")
            val json = org.json.JSONObject(resultJson)
            json.optBoolean("isEncrypted", false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun convertPdfToImages(
        context: Context,
        sourceUri: Uri,
        outputDirectory: DocumentFile,
        baseName: String,
        formatName: String = "JPEG",
        quality: Int = 90,
        targetWidth: Int = 1440
    ): List<Uri> = withContext(Dispatchers.IO) {
        val outputUris = mutableListOf<Uri>()
        try {
            var totalPages = getPageCountFromGateway(context, sourceUri)
            if (totalPages == 0) return@withContext emptyList()
            
            val isPng = formatName.equals("PNG", ignoreCase = true)
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val extension = if (isPng) "png" else "jpg"

            for (i in 0 until totalPages) {
                val fileName = "${baseName}_page_${i + 1}.$extension"
                val newFile = outputDirectory.createFile(mimeType, fileName)
                if (newFile != null) {
                    outputUris.add(newFile.uri)
                }
            }

            if (outputUris.isNotEmpty()) {
                val scale = targetWidth / 720.0f // heuristic
                val paramsJson = org.json.JSONObject().apply {
                    put("format", if (isPng) "png" else "jpeg")
                    put("quality", quality)
                    put("scale", scale.toDouble())
                }.toString()

                PdfGateway.executeEngineBatch(context, "PDF_TO_IMAGES", listOf(sourceUri), outputUris, paramsJson)
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Failed to convert PDF to images via Gateway", e)
        }
        outputUris
    }

    private suspend fun getPageCountFromGateway(context: Context, sourceUri: Uri): Int {
        return try {
            val resultJson = PdfGateway.executeEngine(context, "GET_PAGE_COUNT", sourceUri, null, "{}")
            val json = org.json.JSONObject(resultJson)
            json.optInt("pageCount", 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun parsePageRange(rangeStr: String?, totalPages: Int): Set<Int> {
        if (rangeStr.isNullOrBlank()) {
            return (1..totalPages).toSet()
        }
        val pages = mutableSetOf<Int>()
        val parts = rangeStr.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-")
                if (bounds.size == 2) {
                    val start = bounds[0].trim().toIntOrNull()
                    val end = bounds[1].trim().toIntOrNull()
                    if (start != null && end != null && start <= end) {
                        pages.addAll((start..end).toList())
                    }
                }
            } else {
                val single = trimmed.toIntOrNull()
                if (single != null) {
                    pages.add(single)
                }
            }
        }
        return pages.filter { it in 1..totalPages }.toSet()
    }
}

