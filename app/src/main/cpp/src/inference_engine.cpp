/**
 * inference_engine.cpp
 *
 * Architecture:
 *   C++ handles ONLY image preprocessing (bilinear resize + normalisation).
 *   TFLite inference is executed in Kotlin/Java using the TFLite Interpreter
 *   with GPU Delegate / NNAPI.  The JNI bridge (jni_bridge.cpp) routes data
 *   between the two layers.
 *
 *   This approach gives the best performance because:
 *     • Image preprocessing in C++ with NEON SIMD is faster than Kotlin
 *     • TFLite Java API uses the same underlying C++ TFLite engine
 *     • No dependency on TFLite C++ headers in the NDK build
 *     • GPU Delegate fully supported through Java API
 */

#include "image_processor.h"
#include "thread_pool.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <jni.h>
#include <memory>

#define LOG_TAG "AmanEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global thread pool (created once at JNI_OnLoad) ──────────────────────────
static std::unique_ptr<aman::ThreadPool> g_thread_pool;
static constexpr int kPoolSize = 3; // 3 preprocessing threads
static constexpr int kModelInputSize = 224; // MobileNetV3 / EfficientNet-Lite input

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    g_thread_pool = std::make_unique<aman::ThreadPool>(kPoolSize);
    LOGI("ThreadPool initialised with %d threads", kPoolSize);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    g_thread_pool.reset();
}

/**
 * Preprocess an image from raw RGBA bytes into a float32 tensor.
 *
 * Called from Kotlin: InferenceEngine.preprocessRgba()
 *
 * @param env        JNI env
 * @param thiz       InferenceEngine instance
 * @param rgba_bytes jbyteArray of RGBA pixels (width * height * 4)
 * @param src_width  source image width
 * @param src_height source image height
 * @return           jfloatArray of size (224 * 224 * 3) in [−1, 1], or null on error
 */
JNIEXPORT jfloatArray JNICALL
Java_com_aman_browser_ml_InferenceEngine_preprocessRgba(
    JNIEnv*   env,
    jobject   /*thiz*/,
    jbyteArray rgba_bytes,
    jint       src_width,
    jint       src_height)
{
    if (!rgba_bytes || src_width <= 0 || src_height <= 0) {
        LOGE("preprocessRgba: invalid params %dx%d", src_width, src_height);
        return nullptr;
    }

    jsize len = env->GetArrayLength(rgba_bytes);
    if (len < src_width * src_height * 4) {
        LOGE("preprocessRgba: buffer too small (%d < %d)", len, src_width * src_height * 4);
        return nullptr;
    }

    jbyte* raw = env->GetByteArrayElements(rgba_bytes, nullptr);
    if (!raw) return nullptr;

    auto result = aman::ImageProcessor::process(
        reinterpret_cast<const uint8_t*>(raw),
        src_width,
        src_height,
        kModelInputSize
    );

    env->ReleaseByteArrayElements(rgba_bytes, raw, JNI_ABORT);

    if (!result.valid) return nullptr;

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(result.tensor_data.size()));
    if (!out) return nullptr;

    env->SetFloatArrayRegion(
        out, 0,
        static_cast<jsize>(result.tensor_data.size()),
        result.tensor_data.data()
    );
    return out;
}

/**
 * Preprocess an Android Bitmap directly (avoids Java-side pixel copy).
 *
 * Called from Kotlin: InferenceEngine.preprocessBitmap()
 *
 * @param env    JNI env
 * @param thiz   InferenceEngine instance
 * @param bitmap android.graphics.Bitmap (ARGB_8888 format required)
 * @return       jfloatArray of size (224 * 224 * 3) in [−1, 1], or null on error
 */
JNIEXPORT jfloatArray JNICALL
Java_com_aman_browser_ml_InferenceEngine_preprocessBitmap(
    JNIEnv*  env,
    jobject  /*thiz*/,
    jobject  bitmap)
{
    if (!bitmap) return nullptr;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("preprocessBitmap: getInfo failed");
        return nullptr;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("preprocessBitmap: bitmap must be RGBA_8888, got %d", info.format);
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("preprocessBitmap: lockPixels failed");
        return nullptr;
    }

    auto result = aman::ImageProcessor::processAndroidBitmap(
        static_cast<const uint32_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        kModelInputSize
    );

    AndroidBitmap_unlockPixels(env, bitmap);

    if (!result.valid) return nullptr;

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(result.tensor_data.size()));
    if (!out) return nullptr;

    env->SetFloatArrayRegion(
        out, 0,
        static_cast<jsize>(result.tensor_data.size()),
        result.tensor_data.data()
    );
    return out;
}

/**
 * Returns the target input size expected by the models (e.g. 224).
 */
JNIEXPORT jint JNICALL
Java_com_aman_browser_ml_InferenceEngine_getModelInputSize(
    JNIEnv* /*env*/,
    jclass  /*clazz*/)
{
    return kModelInputSize;
}

} // extern "C"
