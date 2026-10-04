package com.googlecode.tesseract.android

/**
 * JNI bridge invoking upstream TessBaseAPI::Init(const char *data, int data_size, ...) from memory.
 * Located in com.googlecode.tesseract.android to access package-private TessBaseAPI.getNativeData().
 */
object TesseractMemoryBridge {
    init {
        // Ensure standard Tesseract native libraries are loaded first
        System.loadLibrary("jpeg")
        System.loadLibrary("pngx")
        System.loadLibrary("leptonica")
        System.loadLibrary("tesseract")
        System.loadLibrary("tessmemory")
    }

    @JvmStatic
    external fun nativeInitFromMemory(
        nativeData: Long,
        modelData: ByteArray,
        language: String,
        ocrEngineMode: Int
    ): Boolean

    fun initFromMemory(
        api: TessBaseAPI,
        modelData: ByteArray,
        language: String = "eng",
        ocrEngineMode: Int = TessBaseAPI.OEM_DEFAULT
    ): Boolean {
        val nativeData = api.nativeData
        check(nativeData != 0L) { "TessBaseAPI native data pointer is 0" }
        return nativeInitFromMemory(nativeData, modelData, language, ocrEngineMode)
    }
}
