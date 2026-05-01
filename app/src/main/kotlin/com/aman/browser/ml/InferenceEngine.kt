package com.aman.browser.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import com.aman.browser.data.PreferencesManager
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * NudeNet 320n inference engine — unified entry point for all call sites.
 *
 * **Primary path (MediaPipe)** — when `models/nudenet_320n_nms.tflite` is
 * present in assets, [NudeNetDetector] is used.  MediaPipe Tasks handles GPU /
 * NNAPI delegate selection, async threading, and tensor buffer lifecycle.
 *
 * **Fallback path (raw TFLite)** — if the converted model is absent, the engine
 * loads `models/nudenet_320n.tflite` directly via the TFLite Interpreter with
 * C++ NMS (decodeAndNms JNI).  This path is identical to the previous engine.
 *
 * No call-site changes are required in [WebExtensionManager], [AmanApplication],
 * or [SettingsFragment] — they all call [InferenceEngine.get] as before.
 */
class InferenceEngine private constructor(private val context: Context) {

    // ── MediaPipe delegate (primary, when converted model is present) ─────────
    @Volatile private var mpDetector: NudeNetDetector? = null

    // ── Native functions (implemented in cpp/src/inference_engine.cpp) ──────
    private external fun preprocessRgba(
        rgba: ByteArray, srcW: Int, srcH: Int, dstSize: Int, mode: Int
    ): FloatArray?
    private external fun preprocessBitmap(
        bitmap: Bitmap, dstSize: Int, mode: Int
    ): FloatArray?

    /**
     * Decode raw YOLOv8 float output and run per-class greedy NMS in C++.
     *
     * Returns [count, cls0, score0, left0, top0, right0, bottom0, …] with
     * box coordinates normalised to [0, 1].  Returns a single-element array
     * [0f] when no detections survive the thresholds.
     */
    private external fun decodeAndNms(
        rawOutput:       FloatArray,
        numAnchors:      Int,
        numAttrs:        Int,
        numClasses:      Int,
        attrsFirst:      Boolean,
        scoreThreshold:  Float,
        iouThreshold:    Float,
        inputW:          Int,
        inputH:          Int,
    ): FloatArray?

    private val lock = ReentrantLock()
    @Volatile private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    // Resolved at load time from the actual TFLite tensors.
    private var inputW = 320
    private var inputH = 320
    private var inputChannelsFirst = false  // [1,3,H,W] vs [1,H,W,3]
    private var outputShape: IntArray = intArrayOf()
    private var outputAttrFirst = true       // [1,22,A] vs [1,A,22]
    private var numClasses = 18
    private var numAnchors = 0

    // Reusable buffers (single-threaded path; lock guards interpreter use).
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null

    fun initialize(useGpu: Boolean, useNnapi: Boolean) {
        // ── Try MediaPipe path first ──────────────────────────────────────────
        if (mpDetector == null && NudeNetDetector.isAvailable(context)) {
            try {
                mpDetector = NudeNetDetector.create(context, useGpu)
                Log.i(TAG, "MediaPipe NudeNetDetector active (primary path)")
            } catch (e: Exception) {
                Log.w(TAG, "MediaPipe init failed — falling back to raw TFLite", e)
                mpDetector = null
            }
        }
        if (mpDetector != null) return  // MediaPipe is up; no need to load TFLite interpreter

        // ── Fallback: raw TFLite interpreter + C++ NMS ────────────────────────
        lock.withLock {
            if (interpreter != null) return
            try {
                val model = loadModel(MODEL_NUDENET)
                val opts = Interpreter.Options().apply {
                    numThreads = 4
                    val gpuSupported = runCatching {
                        useGpu && CompatibilityList().isDelegateSupportedOnThisDevice
                    }.getOrDefault(false)
                    if (gpuSupported) {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                    } else if (useNnapi) {
                        nnApiDelegate = NnApiDelegate()
                        addDelegate(nnApiDelegate)
                    }
                }
                val itp = Interpreter(model, opts)
                interpreter = itp

                val inShape = itp.getInputTensor(0).shape() // [1,H,W,3] or [1,3,H,W]
                if (inShape.size == 4) {
                    if (inShape[1] == 3) {
                        inputChannelsFirst = true
                        inputH = inShape[2]; inputW = inShape[3]
                    } else {
                        inputChannelsFirst = false
                        inputH = inShape[1]; inputW = inShape[2]
                    }
                }

                val outShape = itp.getOutputTensor(0).shape() // [1,22,A] or [1,A,22]
                outputShape = outShape
                if (outShape.size == 3) {
                    val classesPlusBox = 4 + numClasses // 22 for NudeNet 18-class
                    outputAttrFirst = (outShape[1] == classesPlusBox)
                    if (outputAttrFirst) {
                        numAnchors = outShape[2]
                    } else {
                        numAnchors = outShape[1]
                    }
                }

                // allocate persistent IO buffers
                val inElems = inputW * inputH * 3
                inputBuffer = ByteBuffer.allocateDirect(inElems * 4).order(ByteOrder.nativeOrder())
                val outElems = outShape.fold(1) { a, b -> a * b }
                outputBuffer = ByteBuffer.allocateDirect(outElems * 4).order(ByteOrder.nativeOrder())

                Log.i(TAG, "NudeNet TFLite fallback ready: in=${inShape.toList()} " +
                        "(CF=$inputChannelsFirst) out=${outShape.toList()} " +
                        "(attrFirst=$outputAttrFirst, A=$numAnchors)")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load NudeNet model", e)
                destroyLocked()
            }
        }
    }

    fun destroy() {
        try { mpDetector?.close() } catch (_: Exception) {}
        mpDetector = null
        lock.withLock { destroyLocked() }
    }

    private fun destroyLocked() {
        try { interpreter?.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        try { nnApiDelegate?.close() } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
        nnApiDelegate = null
        inputBuffer = null
        outputBuffer = null
    }

    /**
     * Classify an image stream and return the merged decision.
     * Public signature kept identical to the previous engine so existing
     * callers in WebExtensionManager keep compiling unchanged.
     */
    fun classifyStream(
        stream: InputStream,
        checkNsfw: Boolean,
        checkFace: Boolean,
        checkSkin: Boolean,
        genderFilter: Int,
        nsfwThreshold: Float,
    ): DetectionResult {
        val bytes = readAllBytes(stream) ?: return DetectionResult.SAFE
        val bitmap = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                     catch (_: Throwable) { null } ?: return DetectionResult.SAFE
        return try {
            classifyBitmap(bitmap, checkNsfw, checkFace, checkSkin, genderFilter, nsfwThreshold)
        } finally {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    fun classifyBitmap(
        bitmap: Bitmap,
        checkNsfw: Boolean,
        checkFace: Boolean,
        checkSkin: Boolean,
        genderFilter: Int,
        nsfwThreshold: Float,
    ): DetectionResult {
        // ── Primary: delegate to MediaPipe if the converted model is loaded ──
        mpDetector?.let { mp ->
            return mp.classify(bitmap, checkNsfw, checkFace, checkSkin,
                               genderFilter, nsfwThreshold)
        }

        // ── Fallback: raw TFLite interpreter + C++ NMS ──────────────────────
        val itp = interpreter ?: return DetectionResult.SAFE
        val t0 = System.currentTimeMillis()

        // Convert to ARGB_8888 if needed for JNI
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                  else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // Mode 1 = [0,1] normalisation expected by YOLO models.
        val tensor = preprocessBitmap(src, inputW, /*mode=*/ 1) ?: run {
            if (src !== bitmap) try { src.recycle() } catch (_: Exception) {}
            return DetectionResult.SAFE
        }
        if (src !== bitmap) try { src.recycle() } catch (_: Exception) {}

        // ── Run TFLite inference (lock guards shared buffers + interpreter) ──
        val rawOutput: FloatArray = lock.withLock {
            val ib = inputBuffer  ?: return DetectionResult.SAFE
            val ob = outputBuffer ?: return DetectionResult.SAFE
            ib.rewind()
            if (inputChannelsFirst) {
                // tensor is HWC; re-pack to CHW
                val hw = inputH * inputW
                for (c in 0 until 3) {
                    var i = c
                    var n = 0
                    while (n < hw) { ib.putFloat(tensor[i]); i += 3; n++ }
                }
            } else {
                for (v in tensor) ib.putFloat(v)
            }
            ib.rewind(); ob.rewind()
            try {
                itp.run(ib, ob)
            } catch (e: Exception) {
                Log.e(TAG, "Interpreter run failed", e)
                return DetectionResult.SAFE
            }
            // Copy raw floats out before releasing the lock — NMS runs outside.
            ob.rewind()
            FloatArray(ob.capacity() / 4).also { ob.asFloatBuffer().get(it) }
        }

        // ── Decode + NMS entirely in C++ (zero JVM allocation for filtering) ──
        val attrs  = 4 + numClasses
        val nmsOut = decodeAndNms(
            rawOutput, numAnchors, attrs, numClasses,
            outputAttrFirst, SCORE_THRESHOLD, IOU_THRESHOLD, inputW, inputH,
        ) ?: return DetectionResult.SAFE

        val elapsed = System.currentTimeMillis() - t0
        val count   = nmsOut[0].toInt()
        val detections = ArrayList<Detection>(count)
        for (i in 0 until count) {
            val base   = 1 + i * 6
            val clsIdx = nmsOut[base].toInt()
            detections.add(Detection(
                classIndex = clsIdx,
                className  = NudeNetClasses.NAMES.getOrElse(clsIdx) { "C$clsIdx" },
                score      = nmsOut[base + 1],
                box        = RectF(nmsOut[base + 2], nmsOut[base + 3],
                                   nmsOut[base + 4], nmsOut[base + 5]),
            ))
        }
        return aggregate(detections, checkNsfw, checkFace, checkSkin, genderFilter, nsfwThreshold, elapsed)
    }

    // decodeYolov8, nmsPerClass, and iouRect have been removed.
    // All YOLOv8 post-processing is now handled by the native decodeAndNms() JNI
    // function above, which runs entirely in C++ and returns only the final
    // filtered detections — zero JVM allocation for the intermediate candidates.
    @Suppress("unused") private fun _nmsMovedToNative() = Unit  // marker for git blame

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
                in NudeNetClasses.EXPLICIT.toList() -> if (d.score > explicit) explicit = d.score
                in NudeNetClasses.SKIN_EXPOSURE.toList() -> if (d.score > skinExp) skinExp = d.score
                NudeNetClasses.IDX_FACE_FEMALE -> {
                    if (d.score > female) female = d.score
                    faceCnt++
                }
                NudeNetClasses.IDX_FACE_MALE -> {
                    if (d.score > male) male = d.score
                    faceCnt++
                }
            }
        }
        val faceScore = max(female, male).coerceAtLeast(0f)

        val nsfwTrigger = checkNsfw && explicit >= nsfwThreshold
        val skinTrigger = checkSkin && skinExp  >= SKIN_THRESHOLD
        val faceTrigger = checkFace && when (genderFilter) {
            PreferencesManager.GENDER_NO_PEOPLE   -> false
            PreferencesManager.GENDER_FEMALES_ONLY -> female >= FACE_THRESHOLD
            PreferencesManager.GENDER_MALES_ONLY   -> male   >= FACE_THRESHOLD
            else /* EVERYONE */ -> female >= FACE_THRESHOLD || male >= FACE_THRESHOLD
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

    private fun loadModel(name: String): MappedByteBuffer {
        val afd = context.assets.openFd(name)
        afd.use {
            val fis = java.io.FileInputStream(it.fileDescriptor)
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
        }
    }

    private fun readAllBytes(stream: InputStream): ByteArray? = try {
        stream.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var read: Int
            var total = 0
            while (s.read(buf).also { read = it } != -1) {
                total += read
                if (total > MAX_IMAGE_BYTES) return null
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    companion object {
        private const val TAG = "InferenceEngine"
        private const val MODEL_NUDENET = "models/nudenet_320n.tflite"
        private const val SCORE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD   = 0.45f
        private const val SKIN_THRESHOLD  = 0.5f
        private const val FACE_THRESHOLD  = 0.5f
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

        @Volatile private var instance: InferenceEngine? = null
        fun get(context: Context): InferenceEngine =
            instance ?: synchronized(this) {
                instance ?: InferenceEngine(context.applicationContext).also { instance = it }
            }

        init { System.loadLibrary("aman_inference") }
    }
}
