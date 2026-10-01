package com.pdfchemy.app.jail.engines

import android.content.ComponentCallbacks2
import android.content.Context
import com.google.android.gms.common.api.internal.BackgroundDetector
import com.google.mlkit.common.MlKit

internal object IsolatedOcrRuntime {
    private var processContext: OcrProcessContext? = null
    @Synchronized
    fun initialize(context: Context) {
        // This service process never hosts an Activity. Seed its known background
        // state before ML Kit starts GoogleApiManager: the fallback state query
        // calls ActivityManager.getMyMemoryState, which isolated UIDs cannot use.
        // The resolved GMS implementation sets its state-known flag here. The
        // device regression checks OCR and the same Binder after async callbacks.
        BackgroundDetector.getInstance().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        // Android does not initialize the default ML Kit ContentProvider in an
        // isolated service process, so initialize its bundled offline model here.
        val sdkContext = processContext ?: OcrProcessContext(context).also { processContext = it }
        MlKit.initialize(sdkContext)
    }
}
