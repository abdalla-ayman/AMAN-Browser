#!/usr/bin/env bash
# =============================================================================
# download_models.sh — Fetch the NudeNet 320n detector for أمان Aman
# =============================================================================
# Single ML model used by the app:
#
#   nudenet_320n.tflite   YOLOv8n, 18-class detector (NSFW + face + skin)
#                         Input  : float32 [1, 3, 320, 320]   range [0, 1]
#                         Output : float32 [1, 22, 2100]      (4 box + 18 cls)
#
# This script:
#   1. Downloads the official NudeNet 320n ONNX from notAI-tech/NudeNet release.
#   2. Converts ONNX → TFLite (float32) inside a Python 3.10 Docker image
#      using onnx2tf (requires Docker on the host).
#   3. Copies the resulting .tflite into app/src/main/assets/models/.
# =============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT_DIR/app/src/main/assets/models"
WORK_DIR="$ROOT_DIR/tools/model_convert"
mkdir -p "$MODELS_DIR" "$WORK_DIR"

# Asset id for "320n.onnx" attached to the v3.4-weights release of
# notAI-tech/NudeNet. Using the API endpoint avoids the HTML login redirect
# returned by the regular /releases/download/... URL when the asset is large.
NUDENET_ASSET_API="https://api.github.com/repos/notAI-tech/NudeNet/releases/assets/176831997"
ONNX_PATH="$WORK_DIR/320n.onnx"

if [ ! -f "$ONNX_PATH" ]; then
    echo "==> Downloading NudeNet 320n ONNX..."
    curl -fL -H "Accept: application/octet-stream" \
        "$NUDENET_ASSET_API" -o "$ONNX_PATH"
fi
echo "    ONNX size: $(du -h "$ONNX_PATH" | cut -f1)"

if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker is required for the ONNX → TFLite conversion." >&2
    exit 1
fi

echo "==> Converting to TFLite via onnx2tf (Docker)..."
docker run --rm -v "$WORK_DIR":/work python:3.10-slim bash /work/convert.sh

# onnx2tf emits several .tflite variants; the float32 one is what we ship.
SRC_TFLITE="$(ls -1 "$WORK_DIR/saved/"*float32*.tflite 2>/dev/null | head -n1 || true)"
if [ -z "$SRC_TFLITE" ]; then
    SRC_TFLITE="$(ls -1 "$WORK_DIR/saved/"*.tflite 2>/dev/null | head -n1 || true)"
fi
if [ -z "$SRC_TFLITE" ]; then
    echo "ERROR: conversion produced no .tflite file in $WORK_DIR/saved/" >&2
    exit 1
fi

cp "$SRC_TFLITE" "$MODELS_DIR/nudenet_320n.tflite"
echo "==> Wrote $MODELS_DIR/nudenet_320n.tflite ($(du -h "$MODELS_DIR/nudenet_320n.tflite" | cut -f1))"
echo
ls -lh "$MODELS_DIR"
