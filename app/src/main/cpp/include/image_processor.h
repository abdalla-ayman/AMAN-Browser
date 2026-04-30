#pragma once

#include <cstdint>
#include <vector>

namespace aman {

struct PreprocessResult {
    std::vector<float> tensor_data;   // float32 RGB tensor [H,W,3] (HWC)
    int                width  = 0;
    int                height = 0;
    bool               valid  = false;
};

class ImageProcessor {
public:
    // Resize RGBA bytes → float RGB tensor [dstH, dstW, 3].
    // normalize_mode: 0 = [-1, 1], 1 = [0, 1] (YOLO).
    static PreprocessResult process(
        const uint8_t* rgba_data,
        int            src_width,
        int            src_height,
        int            dst_size,
        int            normalize_mode = 1
    );

    static PreprocessResult processAndroidBitmap(
        const uint32_t* argb_pixels,
        int             src_width,
        int             src_height,
        int             dst_size,
        int             normalize_mode = 1
    );

private:
    static void bilinearResizeRGBAtoRGB(
        const uint8_t* src, int srcW, int srcH,
        float*         dst, int dstW, int dstH
    );
};

} // namespace aman
