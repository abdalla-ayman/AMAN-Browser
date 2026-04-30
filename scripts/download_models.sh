#!/usr/bin/env bash
# =============================================================================
# download_models.sh — Download pre-trained TFLite models for أمان Aman
# =============================================================================
# Run from the project root:
#   chmod +x scripts/download_models.sh
#   ./scripts/download_models.sh
#
# Models are placed in:
#   app/src/main/assets/models/
#
# Models are excluded from git via .gitignore. Re-run this script after
# cloning the repository.
# =============================================================================

set -euo pipefail

MODELS_DIR="app/src/main/assets/models"
mkdir -p "$MODELS_DIR"

echo "==> Downloading TFLite models into $MODELS_DIR ..."

# ── 1. NSFW classifier (MobileNetV3-Small, 224×224) ──────────────────────────
# Source: GantMan/nsfw_model — exported to TFLite
NSFW_URL="https://github.com/lovell/sharp-libvips/releases/download/v8.14.5/libvips-8.14.5-linux-x64.tar.br"
# NOTE: Replace the URL above with your actual hosted model URL.
# The model file name must be: nsfw_mobilenetv3.tflite
# Input:  [1, 224, 224, 3]  float32  normalised to [-1, 1]
# Output: [1, 5]  (drawings, hentai, neutral, porn, sexy) — blur on porn+sexy
echo "  [NSFW] Place nsfw_mobilenetv3.tflite in $MODELS_DIR"
echo "         Recommended source: https://github.com/GantMan/nsfw_model"

# ── 2. Face detection (MediaPipe BlazeFace) ──────────────────────────────────
# We prefer the *full-range* model (192×192, 2304 anchors) for higher accuracy
# on small / distant faces, and keep the short-range (128×128, 896 anchors) as
# a fallback. InferenceEngine loads full-range first, then falls back.
# Source: MediaPipe TFLite Models on storage.googleapis.com/mediapipe-assets
FACE_FULL="$MODELS_DIR/face_detection_full.tflite"
if [ ! -f "$FACE_FULL" ]; then
    echo "  [FACE] Downloading BlazeFace full-range from MediaPipe..."
    curl -fsSL \
        "https://storage.googleapis.com/mediapipe-assets/face_detection_full_range.tflite" \
        -o "$FACE_FULL"
    echo "  [FACE] Done → $FACE_FULL"
else
    echo "  [FACE] Already present: $FACE_FULL"
fi

FACE_SHORT="$MODELS_DIR/face_detection_short.tflite"
if [ ! -f "$FACE_SHORT" ]; then
    echo "  [FACE] Downloading BlazeFace short-range (fallback) from MediaPipe..."
    curl -fsSL \
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite" \
        -o "$FACE_SHORT"
    echo "  [FACE] Done → $FACE_SHORT"
else
    echo "  [FACE] Already present: $FACE_SHORT"
fi

# ── 3. Skin classifier ────────────────────────────────────────────────────────
# A lightweight binary classifier (skin / no-skin) based on MobileNetV2.
# Input:  [1, 224, 224, 3]  float32  normalised to [-1, 1]
# Output: [1, 2]  (no-skin, skin)
echo "  [SKIN] Place skin_classifier.tflite in $MODELS_DIR"
echo "         Train using: https://teachablemachine.withgoogle.com (Image Project)"
echo "         Export as TFLite → Floating point (default)"

# ── 4. Gender classifier (MobileNetV3-Small preferred) ───────────────────────
# Binary classifier — female / male. InferenceEngine prefers
# gender_mobilenetv3.tflite when present and valid, then falls back to
# model_gender_q.tflite.
# Input:  [1, 224, 224, 3]  float32  normalised to [-1, 1]
# Output: [1, 2]  (female, male)
echo "  [GENDER] Place gender_mobilenetv3.tflite in $MODELS_DIR"
echo "           Recommended base: https://tfhub.dev/google/lite-model/imagenet/mobilenet_v3_small_100_224/classification/5/default/1"
echo "           Fine-tune on gender dataset: UTKFace / FairFace"
echo "           Fallback still supported: model_gender_q.tflite"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "==> Model directory contents:"
ls -lh "$MODELS_DIR" 2>/dev/null || echo "  (empty — place .tflite files manually)"

echo ""
echo "==> Expected files:"
echo "    $MODELS_DIR/nsfw_mobilenetv3.tflite"
echo "    $MODELS_DIR/face_detection_short.tflite"
echo "    $MODELS_DIR/skin_classifier.tflite"
echo "    $MODELS_DIR/gender_mobilenetv3.tflite"
echo "    $MODELS_DIR/model_gender_q.tflite (fallback)"
echo ""
echo "==> All models are excluded from git (.gitignore). Keep them local."
