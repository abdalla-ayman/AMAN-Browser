#!/bin/bash
set -e
pip install --quiet --no-cache-dir \
  "onnx==1.16.0" "onnxsim>=0.4.36" "onnx2tf>=1.20" \
  "onnx_graphsurgeon" "sng4onnx" "tflite-support" "psutil" "ai-edge-litert" \
  "tensorflow==2.15.0" "tf_keras>=2.15" 2>&1 | tail -5
cd /work
echo "==> Converting with onnx2tf (input fixed to 1x3x320x320)..."
rm -rf saved
# -b 1                 : fixed batch of 1
# -ois images:1,3,320,320 : pin all input dims (model has dynamic spatial dims)
# -kat images          : keep input tensor in its original NCHW layout (we transpose
#                        on the Java side; this avoids an extra Transpose op being
#                        baked in at the input)
onnx2tf -i 320n.onnx -o saved -b 1 -ois "images:1,3,320,320" -kat images 2>&1 | tail -25
echo "==> Output files:"
ls -lh saved/ | head -30
