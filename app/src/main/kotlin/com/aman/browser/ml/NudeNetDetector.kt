package com.aman.browser.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * MediaPipe Custom ObjectDetector wrapper for NudeNet 320n.
 *
 * Prerequisites
 * ─────────────
 * This class requires the model file produced by the conversion script:
 *   scripts/nudenet_to_mediapipe.py
 *
 * That script:
 *   1. Converts nudenet_320n.onnx → TFLite with TFLite NMS ops baked into
 *      the graph (via onnx2tf --add_tflite_nms_ops).
 *   2. Packs TFLite Metadata (input normalisation, label map, output tensor
 *      names) using mediapipe.tasks.metadata.metadata_writers.object_detector.
 *   3. Writes the result to app/src/main/assets/models/nudenet_320n_nms.tflite
 *
 * Architecture benefit
 * ────────────────────
 * MediaPipe Tasks handles:
 *   • Hardware-acceleration delegate selection (GPU → NNAPI → CPU)
 *   • Async thread management and tensor buffer lifecycle
 *   • Image format conversion (RGB/YUV/RGBA) via MediaPipe Image
 *
 * The Kotlin caller receives a clean [DetectionResult] with the same public
 * API as [InferenceEngine], so [WebExtensionManager] needs no changes.
 *
 * Fallback
 * ────────
 * If the converted model is not present in assets, [isAvailable] returns false
 * and callers should fall back to [InferenceEngine] which works with the raw
 * nudenet_320n.tflite (no metadata required).
 */
class NudeNetDetector private constructor(private val detector: ObjectDetector) {

    /**
     * Classify [bitmap] and return the aggregated [DetectionResult].
     *
     * The call is synchronous and must be made from a background thread.
     */
    fun classify(
        bitmap: Bitmap,
        checkNsfw: Boolean,
        checkFace: Boolean,
        checkSkin: Boolean,
        genderFilter: Int,
        nsfwThreshold: Float,
    ): DetectionResult {
        val t0 = System.currentTimeMillis()

        // MediaPipe manages buffer lifecycle; we only need ARGB_8888 input.
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                  else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val mpImage = BitmapImageBuilder(src).build()

        val rawResults = try {
            detector.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe ObjectDetector failed", e)
            if (src !== bitmap) try { src.recycle() } catch (_: Exception) {}
            return DetectionResult.ERROR
        }

        if (src !== bitmap) try { src.recycle() } catch (_: Exception) {}

        val elapsed = System.currentTimeMillis() - t0

        // Map MediaPipe Detection results back to NudeNet semantics.
        // MediaPipe returns category labels as strings (from the packed label map);
        // we look them up in NudeNetClasses.NAMES to get the integer class index.
        val detections = rawResults.detections().flatMap { mpDet ->
            mpDet.categories().mapNotNull { cat ->
                val clsIdx = NudeNetClasses.NAMES.indexOfFirst { it == cat.categoryName() }
                if (clsIdx < 0) return@mapNotNull null
                val bbox = mpDet.boundingBox()
                Detection(
                    classIndex = clsIdx,
                    className  = cat.categoryName(),
                    score      = cat.score(),
                    box        = RectF(
                        bbox.left   / 320f,
                        bbox.top    / 320f,
                        bbox.right  / 320f,
                        bbox.bottom / 320f,
                    ),
                )
            }
        }

        return aggregate(detections, checkNsfw, checkFace, checkSkin,
                         genderFilter, nsfwThreshold, elapsed)
    }

    fun close() {
        try { detector.close() } catch (_: Exception) {}
    }

    // ── Aggregation (mirrors InferenceEngine.aggregate) ─────────────────────

    private fun aggregate(
        detections: List<Detection>,
        checkNsfw: Boolean, checkFace: Boolean, checkSkin: Boolean,
        genderFilter: Int, nsfwThreshold: Float, elapsed: Long,
    ): DetectionResult {
        if (detections.isEmpty()) {
            return DetectionResult(detections = detections, elapsedMs = elapsed)
        }
        var explicit = 0f
        var skinExp  = 0f
        var female   = -1f
        var male     = -1f
        var faceCnt  = 0
        for (d in detections) {
            when (d.classIndex) {
                in NudeNetClasses.EXPLICIT.toList()      -> if (d.score > explicit) explicit = d.score
                in NudeNetClasses.SKIN_EXPOSURE.toList() -> if (d.score > skinExp)  skinExp  = d.score
                NudeNetClasses.IDX_FACE_FEMALE           -> { if (d.score > female) female = d.score; faceCnt++ }
                NudeNetClasses.IDX_FACE_MALE             -> { if (d.score > male)   male   = d.score; faceCnt++ }
            }
        }
        val faceScore = maxOf(female, male).coerceAtLeast(0f)

        val nsfwTrigger = checkNsfw && explicit >= nsfwThreshold
        val skinTrigger = checkSkin && skinExp  >= SKIN_THRESHOLD
        val faceTrigger = checkFace && when (genderFilter) {
            com.aman.browser.data.PreferencesManager.GENDER_NO_PEOPLE    -> false
            com.aman.browser.data.PreferencesManager.GENDER_FEMALES_ONLY -> female >= FACE_THRESHOLD
            com.aman.browser.data.PreferencesManager.GENDER_MALES_ONLY   -> male   >= FACE_THRESHOLD
            else                                                          -> female >= FACE_THRESHOLD || male >= FACE_THRESHOLD
        }

        return DetectionResult(
            detections  = detections,
            nsfwScore   = explicit,
            faceScore   = faceScore,
            skinScore   = skinExp,
            femaleScore = female,
            maleScore   = male,
            shouldBlur  = nsfwTrigger || skinTrigger || faceTrigger,
            elapsedMs   = elapsed,
            faceCount   = faceCnt,
        )
    }

    companion object {
        private const val TAG             = "NudeNetDetector"
        /** Asset path for the NMS-baked, metadata-packed model. */
        const val MODEL_ASSET             = "models/nudenet_320n_nms.tflite"
        private const val MAX_RESULTS     = 50
        private const val SCORE_THRESHOLD = 0.25f
        private const val SKIN_THRESHOLD  = 0.5f
        private const val FACE_THRESHOLD  = 0.5f

        /**
         * Returns true when the converted model asset exists.
         * Use this to decide whether to use [NudeNetDetector] or [InferenceEngine].
         */
        fun isAvailable(context: Context): Boolean = try {
            context.assets.open(MODEL_ASSET).use { true }
        } catch (_: Exception) { false }

        /**
         * Create a [NudeNetDetector] backed by MediaPipe GPU → NNAPI → CPU.
         *
         * Throws if the model asset is missing — call [isAvailable] first.
         */
        fun create(context: Context, useGpu: Boolean = true): NudeNetDetector {
            val delegate = if (useGpu) Delegate.GPU else Delegate.CPU
            val baseOpts = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()

            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOpts)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                .build()

            return NudeNetDetector(
                ObjectDetector.createFromOptions(context, opts)
            )
        }
    }
}
