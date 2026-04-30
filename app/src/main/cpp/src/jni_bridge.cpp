/**
 * jni_bridge.cpp
 *
 * Thin JNI bridge exposing C++ image preprocessing to Kotlin.
 * All method signatures here must match the native declarations in
 * com.aman.browser.ml.InferenceEngine.
 *
 * The actual JNI method implementations live in inference_engine.cpp.
 * This file exists as the CMake entry point so that both source files
 * are compiled into the same shared library (aman_inference.so).
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "AmanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ── Verify library loads correctly ───────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_aman_browser_ml_InferenceEngine_nativeVersion(
    JNIEnv* env,
    jclass  /*clazz*/)
{
    LOGI("aman_inference.so loaded successfully");
    return env->NewStringUTF("aman-inference/1.0 (neon-optimised)");
}
