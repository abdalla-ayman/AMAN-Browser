#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <memory>
#include <android/asset_manager.h>

namespace aman {

// ── Detection result ──────────────────────────────────────────────────────────
struct DetectionResult {
    float nsfw_score    = 0.0f;   // 0..1 probability of NSFW content
    float face_score    = 0.0f;   // 0..1 probability of face present
    float skin_score    = 0.0f;   // 0..1 probability of excessive skin exposure
    float female_score  = 0.0f;   // 0..1 probability of female face
    float male_score    = 0.0f;   // 0..1 probability of male face
    bool  should_blur   = false;
    long  elapsed_ms    = 0;
};

// ── Preprocessing result passed to Kotlin for TFLite inference ───────────────
struct PreprocessResult {
    std::vector<float> tensor_data;   // Normalised float32 RGB tensor [H,W,3]
    int                width  = 0;
    int                height = 0;
    bool               valid  = false;
};

// ── Image preprocessor: runs entirely in C++ (NEON-optimised) ────────────────
class ImageProcessor {
public:
    ImageProcessor()  = default;
    ~ImageProcessor() = default;

    // Resize + normalise RGBA bytes → float RGB tensor [dstH, dstW, 3] in [-1,1]
    static PreprocessResult process(
        const uint8_t* rgba_data,
        int            src_width,
        int            src_height,
        int            dst_size   // square target: e.g. 224 for MobileNet
    );

    // Same but source is ARGB_8888 Android Bitmap
    static PreprocessResult processAndroidBitmap(
        const uint32_t* argb_pixels,
        int             src_width,
        int             src_height,
        int             dst_size
    );

private:
    // Bilinear resize RGBA → RGB float
    static void bilinearResizeRGBAtoRGB(
        const uint8_t* src, int srcW, int srcH,
        float*         dst, int dstW, int dstH
    );
};

} // namespace aman
