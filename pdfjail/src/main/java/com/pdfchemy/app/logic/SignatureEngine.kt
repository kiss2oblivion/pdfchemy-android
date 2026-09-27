package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri

import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.util.Base64

data class PlacedSignature(
    val id: String = java.util.UUID.randomUUID().toString(),
    val pageIndex: Int,
    val xRatio: Float,
    val yRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val bitmapBytes: ByteArray,
    val dateStamp: String? = null
)

object SignatureEngine {

    private const val SIGNATURES_DIR = "user_signatures"

    fun getSignaturesDir(context: Context): File {
        val dir = File(context.filesDir, SIGNATURES_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun saveSignature(context: Context, name: String, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val dir = getSignaturesDir(context)
            val file = File(dir, "${sanitizedName}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (e: Exception) {
            AppLogger.e("Failed to save signature: ${e.message}", e)
            false
        }
    }

    suspend fun loadSignatures(context: Context): List<Pair<String, Bitmap>> = withContext(Dispatchers.IO) {
        val dir = getSignaturesDir(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".png") }?.toList() ?: emptyList()
        files.mapNotNull { file ->
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) Pair(file.nameWithoutExtension, bitmap) else null
            } catch (e: Exception) {
                null
            }
        }
    }
    suspend fun listSavedSignatures(context: Context): List<File> = withContext(Dispatchers.IO) {
        val dir = getSignaturesDir(context)
        dir.listFiles { file -> file.isFile && file.name.endsWith(".png") }?.toList() ?: emptyList()
    }

    suspend fun deleteSignature(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val dir = getSignaturesDir(context)
        val file = File(dir, "${sanitizedName}.png")
        if (file.exists()) {
            file.delete()
        } else false
    }

    suspend fun applySignatures(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        signatures: List<PlacedSignature>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sigsArray = JSONArray()
            for (sig in signatures) {
                val sigObj = JSONObject()
                sigObj.put("pageIndex", sig.pageIndex)
                sigObj.put("xRatio", sig.xRatio.toDouble())
                sigObj.put("yRatio", sig.yRatio.toDouble())
                sigObj.put("widthRatio", sig.widthRatio.toDouble())
                sigObj.put("heightRatio", sig.heightRatio.toDouble())
                sigObj.put("dateStamp", sig.dateStamp)
                sigObj.put("bitmapBase64", Base64.encodeToString(sig.bitmapBytes, Base64.DEFAULT))
                sigsArray.put(sigObj)
            }

            val params = JSONObject().apply {
                put("signatures", sigsArray)
            }

            val resultStr = PdfGateway.executeEngine(
                context,
                "SIGNATURE_APPLY",
                sourceUri,
                destUri,
                params.toString()
            )
            val json = JSONObject(resultStr)
            json.optBoolean("success", false)
        } catch (e: Exception) {
            AppLogger.e("Failed to apply signatures to PDF: ${e.message}", e)
            false
        }
    }

    fun createMarkBitmap(symbol: String, colorHex: String, size: Int = 120): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor(colorHex)
            textSize = size * 0.75f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val yOffset = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(symbol, canvas.width / 2f, yOffset, paint)
        return bmp
    }

    fun createBusinessStampBitmap(
        title: String,
        subtext: String? = null,
        colorHex: String,
        width: Int = 360,
        height: Int = 120
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colorInt = Color.parseColor(colorHex)

        val borderPaint = Paint().apply {
            isAntiAlias = true
            color = colorInt
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val rect = RectF(8f, 8f, width - 8f, height - 8f)
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

        val innerPaint = Paint().apply {
            isAntiAlias = true
            color = colorInt
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val innerRect = RectF(14f, 14f, width - 14f, height - 14f)
        canvas.drawRoundRect(innerRect, 12f, 12f, innerPaint)

        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = colorInt
            textSize = if (subtext.isNullOrBlank()) 26f else 22f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        if (subtext.isNullOrBlank()) {
            val yOffset = (height / 2f) - ((titlePaint.descent() + titlePaint.ascent()) / 2f)
            canvas.drawText(title, width / 2f, yOffset, titlePaint)
        } else {
            canvas.drawText(title, width / 2f, 50f, titlePaint)

            val subPaint = Paint().apply {
                isAntiAlias = true
                color = colorInt
                textSize = 14f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(subtext, width / 2f, 85f, subPaint)
        }

        return bmp
    }

    suspend fun applyDigitalSignature(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        signerName: String,
        reason: String,
        location: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject().apply {
                put("signerName", signerName)
                put("reason", reason)
                put("location", location)
            }

            val resultStr = PdfGateway.executeEngine(
                context,
                "SIGNATURE_DIGITAL",
                sourceUri,
                destUri,
                params.toString()
            )
            val json = JSONObject(resultStr)
            json.optBoolean("success", false)
        } catch (e: Exception) {
            AppLogger.e("Failed to apply digital signature: ${e.message}", e)
            false
        }
    }
}
