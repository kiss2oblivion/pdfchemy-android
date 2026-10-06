package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.pdfchemy.app.utils.DocumentStager
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IsolatedImageDecoder {
    suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        JailEngineBridge.bitmap(context, "IMAGE_DECODE", uri)
    }
    suspend fun decode(context: Context, bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        require(bytes.size in 1..SecurityLimits.MAX_SIGNATURE_BYTES)
        val staged = DocumentStager.stageStreamCancellable(context, bytes.inputStream())
        try { decode(context, staged.uri) } finally { DocumentStager.release(staged) }
    }
}
