#include "image_processor.h"
#include <cstring>
#include <algorithm>
#include <android/log.h>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  include <arm_neon.h>
#  define AMAN_NEON 1
#else
#  define AMAN_NEON 0
#endif

#define LOG_TAG "AmanImgProc"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace aman {

void ImageProcessor::bilinearResizeRGBAtoRGB(
    const uint8_t* src, int srcW, int srcH,
    float*         dst, int dstW, int dstH)
{
    const float x_ratio = dstW > 1 ? static_cast<float>(srcW - 1) / (dstW - 1) : 0.0f;
    const float y_ratio = dstH > 1 ? static_cast<float>(srcH - 1) / (dstH - 1) : 0.0f;
    const float inv255  = 1.0f / 255.0f;

    for (int y = 0; y < dstH; ++y) {
        const float gy = y * y_ratio;
        const int   y0 = static_cast<int>(gy);
        const int   y1 = std::min(y0 + 1, srcH - 1);
        const float dy = gy - y0;
        const float dy1 = 1.0f - dy;

        for (int x = 0; x < dstW; ++x) {
            const float gx = x * x_ratio;
            const int   x0 = static_cast<int>(gx);
            const int   x1 = std::min(x0 + 1, srcW - 1);
            const float dx = gx - x0;
            const float dx1 = 1.0f - dx;

            const uint8_t* p00 = src + (y0 * srcW + x0) * 4;
            const uint8_t* p01 = src + (y0 * srcW + x1) * 4;
            const uint8_t* p10 = src + (y1 * srcW + x0) * 4;
            const uint8_t* p11 = src + (y1 * srcW + x1) * 4;

            for (int c = 0; c < 3; ++c) {
                float val = p00[c] * dx1 * dy1
                          + p01[c] * dx  * dy1
                          + p10[c] * dx1 * dy
                          + p11[c] * dx  * dy;
                dst[(y * dstW + x) * 3 + c] = val * inv255;
            }
        }
    }
}

#if AMAN_NEON
static void shiftToSignedNEON(float* data, size_t count) {
    const float32x4_t half = vdupq_n_f32(0.5f);
    const float32x4_t two  = vdupq_n_f32(2.0f);
    size_t i = 0;
    for (; i + 4 <= count; i += 4) {
        float32x4_t v = vld1q_f32(data + i);
        v = vmulq_f32(vsubq_f32(v, half), two);
        vst1q_f32(data + i, v);
    }
    for (; i < count; ++i) data[i] = (data[i] - 0.5f) * 2.0f;
}
#endif

PreprocessResult ImageProcessor::process(
    const uint8_t* rgba_data,
    int            src_width,
    int            src_height,
    int            dst_size,
    int            normalize_mode)
{
    PreprocessResult result;
    if (!rgba_data || src_width <= 0 || src_height <= 0 || dst_size <= 0) {
        LOGE("process: invalid input %dx%d -> %d", src_width, src_height, dst_size);
        return result;
    }

    const size_t tensor_size = static_cast<size_t>(dst_size) * dst_size * 3;
    result.tensor_data.resize(tensor_size);
    result.width  = dst_size;
    result.height = dst_size;

    bilinearResizeRGBAtoRGB(
        rgba_data, src_width, src_height,
        result.tensor_data.data(), dst_size, dst_size
    );

    if (normalize_mode == 0) {
#if AMAN_NEON
        shiftToSignedNEON(result.tensor_data.data(), tensor_size);
#else
        for (float& v : result.tensor_data) v = (v - 0.5f) * 2.0f;
#endif
    }
    // mode 1: already in [0,1] from bilinear step.

    result.valid = true;
    return result;
}

PreprocessResult ImageProcessor::processAndroidBitmap(
    const uint32_t* argb_pixels,
    int             src_width,
    int             src_height,
    int             dst_size,
    int             normalize_mode)
{
    return process(
        reinterpret_cast<const uint8_t*>(argb_pixels),
        src_width, src_height, dst_size, normalize_mode
    );
}

} // namespace aman
