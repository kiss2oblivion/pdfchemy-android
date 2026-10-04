#include <jni.h>
#include <dlfcn.h>
#include <vector>
#include <string>
#include <android/log.h>

#define TAG "TessMemoryBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// In upstream Tesseract 5.5.1:
// mangled symbol for:
// tesseract::TessBaseAPI::Init(const char *data, int data_size, const char *language,
//                              OcrEngineMode oem, char **configs, int configs_size,
//                              const std::vector<std::string> *vars_vec,
//                              const std::vector<std::string> *vars_values,
//                              bool set_only_non_debug_params, FileReader reader)
#define TESS_INIT_FROM_MEM_SYM "_ZN9tesseract11TessBaseAPI4InitEPKciS2_NS_13OcrEngineModeEPPciPKNSt6__ndk16vectorINS6_12basic_stringIcNS6_11char_traitsIcEENS6_9allocatorIcEEEENSB_ISD_EEEESH_bPFbS2_PNS7_IcSC_EEE"

typedef int (*TessInitMemFn)(
    void *this_ptr,
    const char *data,
    int data_size,
    const char *language,
    int oem,
    char **configs,
    int configs_size,
    const void *vars_vec,
    const void *vars_values,
    bool set_only_non_debug_params,
    void *reader
);

static TessInitMemFn g_init_fn = nullptr;

static bool resolve_init_fn() {
    if (g_init_fn != nullptr) {
        return true;
    }
    void *tess_handle = dlopen("libtesseract.so", RTLD_NOLOAD);
    if (!tess_handle) {
        tess_handle = dlopen("libtesseract.so", RTLD_NOW);
    }
    if (!tess_handle) {
        LOGE("Failed to open libtesseract.so: %s", dlerror());
        return false;
    }
    g_init_fn = reinterpret_cast<TessInitMemFn>(dlsym(tess_handle, TESS_INIT_FROM_MEM_SYM));
    if (!g_init_fn) {
        LOGE("Failed to resolve %s in libtesseract.so: %s", TESS_INIT_FROM_MEM_SYM, dlerror());
        return false;
    }
    LOGI("Resolved in-memory Init symbol from libtesseract.so successfully");
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_googlecode_tesseract_android_TesseractMemoryBridge_nativeInitFromMemory(
    JNIEnv *env,
    jobject /* thiz */,
    jlong nativeData,
    jbyteArray modelData,
    jstring language,
    jint ocrEngineMode
) {
    if (nativeData == 0) {
        LOGE("nativeData pointer is null");
        return JNI_FALSE;
    }
    if (!resolve_init_fn()) {
        return JNI_FALSE;
    }
    if (!modelData) {
        LOGE("modelData is null");
        return JNI_FALSE;
    }

    jsize modelSize = env->GetArrayLength(modelData);
    if (modelSize <= 0) {
        LOGE("modelData is empty");
        return JNI_FALSE;
    }

    jbyte *bytes = env->GetByteArrayElements(modelData, nullptr);
    if (!bytes) {
        LOGE("Failed to pin modelData byte array");
        return JNI_FALSE;
    }

    const char *langStr = nullptr;
    if (language) {
        langStr = env->GetStringUTFChars(language, nullptr);
    }

    void *api = reinterpret_cast<void*>(nativeData);
    int res = g_init_fn(
        api,
        reinterpret_cast<const char*>(bytes),
        static_cast<int>(modelSize),
        langStr ? langStr : "eng",
        static_cast<int>(ocrEngineMode),
        nullptr, 0,
        nullptr, nullptr,
        false,
        nullptr
    );

    if (langStr) {
        env->ReleaseStringUTFChars(language, langStr);
    }
    env->ReleaseByteArrayElements(modelData, bytes, JNI_ABORT);

    if (res != 0) {
        LOGE("TessBaseAPI::Init returned error: %d", res);
        return JNI_FALSE;
    }

    LOGI("TessBaseAPI::Init from memory succeeded with %d bytes", modelSize);
    return JNI_TRUE;
}
