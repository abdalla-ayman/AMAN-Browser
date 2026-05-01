#!/usr/bin/env python3
"""
nudenet_to_mediapipe.py
=======================
Converts nudenet_320n.onnx into a MediaPipe-compatible TFLite model with:
  1. TFLite NMS ops baked into the graph via onnx2tf
  2. TFLite Metadata (input normalisation, NudeNet label map, output tensor
     descriptions) packed using tflite-support

Output: app/src/main/assets/models/nudenet_320n_nms.tflite
        (ready for NudeNetDetector.kt / MediaPipe ObjectDetector)

Usage (inside the tensorflow/tensorflow:2.13.0 Docker container):
  pip install onnx onnxruntime onnx2tf tflite-support
  python3 scripts/nudenet_to_mediapipe.py

Or using the project Docker helper:
  docker run --rm \\
    -v $(pwd):/workspace \\
    tensorflow/tensorflow:2.13.0 bash -c \\
      "pip install -q onnx onnxruntime onnx2tf tflite-support && python3 /workspace/scripts/nudenet_to_mediapipe.py"
"""

import os
import sys
import shutil
import subprocess
import tempfile

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT    = os.path.dirname(SCRIPT_DIR)
ONNX_SRC     = os.path.join(REPO_ROOT, "tools", "model_convert", "320n_sim.onnx")
OUTPUT_DIR   = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "models")
OUTPUT_TFLITE = os.path.join(OUTPUT_DIR, "nudenet_320n_nms.tflite")

# NudeNet 320n class names (index 0..17)
NUDENET_LABELS = [
    "FEMALE_GENITALIA_COVERED",   # 0
    "FACE_FEMALE",                # 1
    "BUTTOCKS_EXPOSED",           # 2
    "FEMALE_BREAST_EXPOSED",      # 3
    "FEMALE_GENITALIA_EXPOSED",   # 4
    "MALE_BREAST_EXPOSED",        # 5
    "ANUS_EXPOSED",               # 6
    "FEET_EXPOSED",               # 7
    "BELLY_COVERED",              # 8
    "FEET_COVERED",               # 9
    "ARMPITS_COVERED",            # 10
    "ARMPITS_EXPOSED",            # 11
    "FACE_MALE",                  # 12
    "BELLY_EXPOSED",              # 13
    "MALE_GENITALIA_EXPOSED",     # 14
    "ANUS_COVERED",               # 15
    "FEMALE_BREAST_COVERED",      # 16
    "BUTTOCKS_COVERED",           # 17
]

NMS_CONFIG = {
    "score_threshold":     0.25,
    "iou_threshold":       0.45,
    "max_detections":      50,
    "num_classes":         18,
}


def log(msg: str) -> None:
    print(f"\n==> {msg}", flush=True)


def check_deps() -> None:
    """Ensure required Python packages are available (install all together if any missing)."""
    PKGS = [
        "onnx",
        "onnxruntime",
        "tensorflow-probability",
        "onnx-tf",
        "tflite-support",
    ]
    IMPORT_NAMES = {
        "onnx-tf":               "onnx_tf",
        "tflite-support":        "tflite_support",
        "tensorflow-probability": "tensorflow_probability",
    }
    missing = []
    for pkg in PKGS:
        import_name = IMPORT_NAMES.get(pkg, pkg.replace("-", "_"))
        try:
            __import__(import_name)
        except ImportError:
            missing.append(pkg)
    if missing:
        log(f"Installing: {missing}")
        # Install all together so pip resolves transitive deps in one pass
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q"] + PKGS)


def convert_onnx_to_tflite_with_nms(onnx_path: str, work_dir: str) -> str:
    """
    Use onnx2tf to convert ONNX → TFLite and inject TFLite NMS custom ops.
    onnx2tf produces a saved_model directory; we then convert to TFLite with
    tf.lite.TFLiteConverter + TFLite_Detection_PostProcess NMS op.
    """
    import onnx
    from onnx import shape_inference as _si
    import re as _re
    import tensorflow as tf

    # Pre-bake static shapes into the ONNX protobuf so every downstream op
    # (including MaxPool's pad calculation) sees concrete integer dimensions.
    # overwrite_input_shape in onnx2tf's Python API doesn't propagate to the
    # onnxruntime validation pass; writing directly to the protobuf does.
    model_proto = onnx.load(onnx_path)
    inp         = model_proto.graph.input[0]
    input_name  = inp.name
    raw_dims    = [d.dim_value for d in inp.type.tensor_type.shape.dim]
    _m          = _re.search(r'(\d+)n', os.path.basename(onnx_path))
    spatial     = int(_m.group(1)) if _m else 320
    _defaults   = [1, 3, spatial, spatial]   # NCHW: batch, channels, H, W
    static_dims = [d if (d and d > 0) else _defaults[i] for i, d in enumerate(raw_dims)]
    # Overwrite each input dimension directly in the protobuf
    for i, dim in enumerate(inp.type.tensor_type.shape.dim):
        dim.ClearField('dim_param')
        dim.dim_value = static_dims[i]
    # Propagate static shapes through all intermediate nodes.
    model_proto.ir_version = 8  # pin to IR8 for broad converter compatibility
    model_proto = _si.infer_shapes(model_proto)
    static_onnx = os.path.join(work_dir, "320n_static.onnx")
    onnx.save(model_proto, static_onnx)
    log(f"Static ONNX saved: {static_onnx}  (input {static_dims})")

    # Manually downgrade to opset 12 so onnx-tf's handlers can process every node.
    # onnx.version_converter fails on Shape opset-15 (it added start/end attrs).
    # We handle the two breaking changes ourselves:
    #   1. Unsqueeze opset-13: axes moved from attribute to a second input tensor
    #      → move it back to an attribute and drop the initializer input.
    #   2. Shape opset-15: added optional start/end attributes (both default to
    #      full-range) → just remove them; onnx-tf handles Shape without them.
    import onnx.numpy_helper as _nph
    _init_map = {i.name: i for i in model_proto.graph.initializer}

    for _node in model_proto.graph.node:
        if _node.op_type == 'Unsqueeze' and len(_node.input) == 2:
            # opset-13: axes moved to second input → put back as attribute
            _axes_name = _node.input[1]
            if _axes_name in _init_map:
                _axes_arr = _nph.to_array(_init_map[_axes_name]).flatten().astype(int).tolist()
                del _node.input[1]
                _node.attribute.append(onnx.helper.make_attribute('axes', _axes_arr))
        elif _node.op_type == 'Split' and len(_node.input) == 2:
            # opset-13: split sizes moved to second input → put back as attribute
            _split_name = _node.input[1]
            if _split_name in _init_map:
                _split_arr = _nph.to_array(_init_map[_split_name]).flatten().astype(int).tolist()
                del _node.input[1]
                # only add the attribute if it's non-uniform (uniform split has no attr)
                _node.attribute.append(onnx.helper.make_attribute('split', _split_arr))
        elif _node.op_type == 'Shape':
            # opset-15: added optional start/end attrs → remove them
            for _a in list(_node.attribute):
                if _a.name in ('start', 'end'):
                    _node.attribute.remove(_a)

    # Downgrade opset_import from 15 → 12
    for _op in model_proto.opset_import:
        if _op.domain in ('', 'ai.onnx'):
            _op.version = 12

    model_proto = _si.infer_shapes(model_proto)
    static_onnx_v12 = os.path.join(work_dir, "320n_static_v12.onnx")
    onnx.save(model_proto, static_onnx_v12)
    log(f"Opset-12 ONNX saved: {static_onnx_v12}")

    from onnx_tf.backend import prepare as _onnx_tf_prepare
    saved_model_dir = os.path.join(work_dir, "saved_model")
    log(f"onnx_tf: {static_onnx_v12} → {saved_model_dir}")

    # onnx-tf calls onnx.checker.check_model() before converting; newer onnx
    # versions (≥1.14) reject Resize-11 nodes that have an empty roi input
    # (input[1]="") as "marked single but has empty string", even though the
    # Resize-11 spec says roi is optional.  The model is structurally correct
    # (onnxruntime runs it fine), so we bypass the upfront validation.
    import onnx.checker as _onnx_checker_mod
    _orig_check_model = _onnx_checker_mod.check_model
    _onnx_checker_mod.check_model = lambda *a, **kw: None
    try:
        _tf_rep = _onnx_tf_prepare(onnx.load(static_onnx_v12))
        _tf_rep.export_graph(saved_model_dir)
    finally:
        _onnx_checker_mod.check_model = _orig_check_model
    log("onnx_tf SavedModel written")

    # ── Append TFLite_Detection_PostProcess (NMS) node ───────────────────────
    log("Appending TFLite NMS node to SavedModel …")

    # Load the SavedModel and discover actual input/output key names —
    # onnx2tf may use the original ONNX tensor names, not plain 'input'/'output'.
    _loaded_for_sig = tf.saved_model.load(saved_model_dir)
    _infer_fn       = _loaded_for_sig.signatures["serving_default"]
    _sig_inputs     = _infer_fn.structured_input_signature[1]
    _sig_outputs    = _infer_fn.structured_outputs
    input_key       = next(iter(_sig_inputs.keys()))
    output_key      = next(iter(_sig_outputs.keys()))
    log(f"SavedModel input_key={input_key!r}  output_key={output_key!r}")

    @tf.function(input_signature=[tf.TensorSpec([1, spatial, spatial, 3], tf.float32)])
    def model_with_nms(image):
        # onnx-tf preserves NCHW layout, so transpose NHWC→NCHW before inference
        image_nchw = tf.transpose(image, [0, 3, 1, 2])   # [1,H,W,3] → [1,3,H,W]
        result = _infer_fn(**{input_key: image_nchw})
        # Raw YOLOv8 output: [1, 22, 2100]  (4 box coords + 18 class scores)
        raw = result[output_key]

        # Handle both NCW [1,22,2100] (onnx-tf keeps channels-first for 3D)
        # and NWC [1,2100,22] output formats — detect by shape.
        if raw.shape[2] is not None and raw.shape[1] is not None \
                and raw.shape[2] > raw.shape[1]:
            # NCW → NWC
            raw_t = tf.transpose(raw, [0, 2, 1])      # [1, 2100, 22]
        else:
            raw_t = raw                               # already [1, 2100, 22]

        boxes_cxcywh = raw_t[:, :, :4]               # [1, 2100, 4]  cx,cy,w,h  (pixel)
        scores_all   = raw_t[:, :, 4:]               # [1, 2100, 18]

        # Convert cx,cy,w,h pixel → y1,x1,y2,x2 normalised (MediaPipe expects this)
        inv = 1.0 / 320.0
        cx  = boxes_cxcywh[:, :, 0] * inv
        cy  = boxes_cxcywh[:, :, 1] * inv
        w   = boxes_cxcywh[:, :, 2] * inv
        h   = boxes_cxcywh[:, :, 3] * inv
        y1  = tf.clip_by_value(cy - h / 2.0, 0.0, 1.0)
        x1  = tf.clip_by_value(cx - w / 2.0, 0.0, 1.0)
        y2  = tf.clip_by_value(cy + h / 2.0, 0.0, 1.0)
        x2  = tf.clip_by_value(cx + w / 2.0, 0.0, 1.0)
        # boxes: [batch, num_anchors, 1, 4]  (class-agnostic boxes for combined NMS)
        boxes = tf.stack([y1, x1, y2, x2], axis=-1)[:, :, tf.newaxis, :]

        # combined_non_max_suppression returns (boxes, scores, classes, valid_count)
        boxes_nms, scores_nms, classes_nms, valid_count = (
            tf.image.combined_non_max_suppression(
                boxes,
                scores_all,
                max_output_size_per_class = NMS_CONFIG["max_detections"],
                max_total_size            = NMS_CONFIG["max_detections"],
                iou_threshold             = NMS_CONFIG["iou_threshold"],
                score_threshold           = NMS_CONFIG["score_threshold"],
                pad_per_class             = False,
                clip_boxes                = False,
            )
        )
        return boxes_nms, scores_nms, classes_nms, valid_count

    nms_saved_model_dir = os.path.join(work_dir, "saved_model_nms")
    tf.saved_model.save(
        tf.Module(),
        nms_saved_model_dir,
        signatures={"serving_default": model_with_nms},
    )

    # ── TFLiteConverter ───────────────────────────────────────────────────────
    log("Converting NMS SavedModel → TFLite …")
    conv = tf.lite.TFLiteConverter.from_saved_model(nms_saved_model_dir)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    # Allow TFLite's built-in NMS delegate op (used by GPU delegate)
    conv.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    tflite_bytes = conv.convert()

    raw_tflite_path = os.path.join(work_dir, "nudenet_raw_nms.tflite")
    with open(raw_tflite_path, "wb") as f:
        f.write(tflite_bytes)
    log(f"Raw NMS TFLite written ({len(tflite_bytes) // 1024} KB)")
    return raw_tflite_path


def pack_metadata(raw_tflite_path: str, output_path: str) -> None:
    """
    Pack TFLite Metadata into the model so MediaPipe ObjectDetector can load it.
    Uses tflite-support (~50 MB) instead of mediapipe-model-maker (~2 GB).

    API (tflite-support 0.4.x):
        MetadataWriter.create_for_inference(model_buffer, norm_mean, norm_std,
                                            label_file_paths, score_calibration_md=None)
    """
    log("Packing TFLite Metadata for MediaPipe ObjectDetector …")
    try:
        from tflite_support.metadata_writers import object_detector as od_writer
    except ImportError:
        log("tflite-support not found — installing …")
        subprocess.check_call([
            sys.executable, "-m", "pip", "install", "-q", "tflite-support"
        ])
        from tflite_support.metadata_writers import object_detector as od_writer

    # Write label file to a temp file
    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as lf:
        lf.write("\n".join(NUDENET_LABELS))
        label_file_path = lf.name

    try:
        writer = od_writer.MetadataWriter.create_for_inference(
            model_buffer      = bytearray(open(raw_tflite_path, "rb").read()),
            input_norm_mean   = [0.0, 0.0, 0.0],   # model expects [0, 1] RGB
            input_norm_std    = [1.0, 1.0, 1.0],
            label_file_paths  = [label_file_path],
            score_calibration_md = None,
        )
        model_with_meta = writer.populate()
        with open(output_path, "wb") as f:
            f.write(model_with_meta)
        log(f"Metadata packed → {output_path}  ({len(model_with_meta) // 1024} KB)")
    finally:
        os.unlink(label_file_path)


def main() -> None:
    log("nudenet_to_mediapipe.py — NudeNet 320n ONNX → MediaPipe TFLite")
    check_deps()

    if not os.path.isfile(ONNX_SRC):
        print(f"ERROR: source ONNX not found at {ONNX_SRC}")
        print("Run  ./scripts/download_models.sh  first to get 320n_sim.onnx")
        sys.exit(1)

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    with tempfile.TemporaryDirectory() as work_dir:
        raw_tflite = convert_onnx_to_tflite_with_nms(ONNX_SRC, work_dir)
        pack_metadata(raw_tflite, OUTPUT_TFLITE)

    log(f"Done → {OUTPUT_TFLITE}")
    log("Enable MediaPipe path in InferenceEngine.kt by calling NudeNetDetector.create()")


if __name__ == "__main__":
    main()
