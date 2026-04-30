/**
 * inference_engine.cpp — JNI bridge for image preprocessing.
 *
 * C++ does only resize + normalisation. TFLite inference runs on the Java
 * side using the GPU delegate / NNAPI.
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

static std::unique_ptr<aman::ThreadPool> g_thread_pool;
static constexpr int kPoolSize = 3;

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
 * Java signature: preprocessRgba(byte[] rgba, int srcW, int srcH, int dstSize, int mode) : float[]
 *   mode 0 = [-1,1]   mode 1 = [0,1]
 */
JNIEXPORT jfloatArray JNICALL
Java_com_aman_browser_ml_InferenceEngine_preprocessRgba(
    JNIEnv*    env,
    jobject    /*thiz*/,
    jbyteArray rgba_bytes,
    jint       src_width,
    jint       src_height,
    jint       dst_size,
    jint       mode)
{
    if (!rgba_bytes || src_width <= 0 || src_height <= 0 || dst_size <= 0) {
        LOGE("preprocessRgba: invalid params %dx%d -> %d", src_width, src_height, dst_size);
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
        src_width, src_height, dst_size, mode
    );

    env->ReleaseByteArrayElements(rgba_bytes, raw, JNI_ABORT);
    if (!result.valid) return nullptr;

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(result.tensor_data.size()));
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0,
        static_cast<jsize>(result.tensor_data.size()),
        result.tensor_data.data());
    return out;
}

/**
 * Java signature: preprocessBitmap(Bitmap bm, int dstSize, int mode) : float[]
 */
JNIEXPORT jfloatArray JNICALL
Java_com_aman_browser_ml_InferenceEngine_preprocessBitmap(
    JNIEnv*  env,
    jobject  /*thiz*/,
    jobject  bitmap,
    jint     dst_size,
    jint     mode)
{
    if (!bitmap || dst_size <= 0) return nullptr;

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
        dst_size, mode
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    if (!result.valid) return nullptr;

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(result.tensor_data.size()));
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0,
        static_cast<jsize>(result.tensor_data.size()),
        result.tensor_data.data());
    return out;
}

} // extern "C"
