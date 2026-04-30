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
import kotlin.math.min

/**
 * NudeNet 320n single-model inference engine.
 *
 * Loads `models/nudenet_320n.tflite` (YOLOv8n, 18 classes including
 * FACE_FEMALE / FACE_MALE) and replaces the previous 4-model stack
 * (NSFW, BlazeFace, skin, gender).
 *
 * Public API is preserved: [classifyStream], [initialize], [destroy].
 */
class InferenceEngine private constructor(private val context: Context) {

    // Native preprocessing — implemented in cpp/src/inference_engine.cpp
    private external fun preprocessRgba(
        rgba: ByteArray, srcW: Int, srcH: Int, dstSize: Int, mode: Int
    ): FloatArray?
    private external fun preprocessBitmap(
        bitmap: Bitmap, dstSize: Int, mode: Int
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
        lock.withLock {
            if (interpreter != null) return
            try {
                val model = loadModel(MODEL_NUDENET)
                val opts = Interpreter.Options().apply {
                    numThreads = 4
                    if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
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

                Log.i(TAG, "NudeNet ready: in=${inShape.toList()} (CF=$inputChannelsFirst) " +
                        "out=${outShape.toList()} (attrFirst=$outputAttrFirst, A=$numAnchors)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load NudeNet model", e)
                destroyLocked()
            }
        }
    }

    fun destroy() = lock.withLock { destroyLocked() }

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

        val detections = lock.withLock {
            val ib = inputBuffer ?: return DetectionResult.SAFE
            val ob = outputBuffer ?: return DetectionResult.SAFE
            ib.rewind()
            if (inputChannelsFirst) {
                // tensor is HWC; convert to CHW
                val hw = inputH * inputW
                for (c in 0 until 3) {
                    var i = c
                    var n = 0
                    while (n < hw) {
                        ib.putFloat(tensor[i])
                        i += 3; n++
                    }
                }
            } else {
                for (v in tensor) ib.putFloat(v)
            }
            ib.rewind()
            ob.rewind()
            try {
                itp.run(ib, ob)
            } catch (e: Exception) {
                Log.e(TAG, "Interpreter run failed", e)
                return DetectionResult.SAFE
            }
            ob.rewind()
            decodeYolov8(ob)
        }

        val elapsed = System.currentTimeMillis() - t0
        return aggregate(detections, checkNsfw, checkFace, checkSkin, genderFilter, nsfwThreshold, elapsed)
    }

    /** Decode YOLOv8 raw output → list of detections. */
    private fun decodeYolov8(out: ByteBuffer): List<Detection> {
        val anchors = numAnchors
        if (anchors <= 0) return emptyList()
        val nc = numClasses
        val attrs = 4 + nc
        // Threshold & NMS
        val perClassDet = ArrayList<Detection>(32)

        // Read all floats once
        val floats = FloatArray(anchors * attrs)
        out.asFloatBuffer().get(floats)

        // Index helpers based on layout
        // outputAttrFirst (true): floats are laid out [attr][anchor] (row-major)
        //   value(attr, a) = floats[attr * anchors + a]
        // else                     : floats are [anchor][attr]
        //   value(attr, a) = floats[a * attrs + attr]
        fun v(attr: Int, a: Int): Float =
            if (outputAttrFirst) floats[attr * anchors + a]
            else floats[a * attrs + attr]

        for (a in 0 until anchors) {
            // Find best class
            var bestC = 0
            var bestScore = v(4, a)
            for (c in 1 until nc) {
                val s = v(4 + c, a)
                if (s > bestScore) { bestScore = s; bestC = c }
            }
            if (bestScore < SCORE_THRESHOLD) continue

            val cx = v(0, a)
            val cy = v(1, a)
            val w  = v(2, a)
            val h  = v(3, a)

            // Coordinates are in input-pixel space (0..inputW). Normalise to [0,1].
            val nx = cx / inputW.toFloat()
            val ny = cy / inputH.toFloat()
            val nw = w  / inputW.toFloat()
            val nh = h  / inputH.toFloat()

            val left   = (nx - nw / 2f).coerceIn(0f, 1f)
            val top    = (ny - nh / 2f).coerceIn(0f, 1f)
            val right  = (nx + nw / 2f).coerceIn(0f, 1f)
            val bottom = (ny + nh / 2f).coerceIn(0f, 1f)

            perClassDet.add(
                Detection(
                    classIndex = bestC,
                    className  = NudeNetClasses.NAMES.getOrElse(bestC) { "C$bestC" },
                    score      = bestScore,
                    box        = RectF(left, top, right, bottom),
                )
            )
        }

        // Per-class NMS
        return nmsPerClass(perClassDet, IOU_THRESHOLD)
    }

    private fun nmsPerClass(input: List<Detection>, iou: Float): List<Detection> {
        if (input.isEmpty()) return input
        val byCls = input.groupBy { it.classIndex }
        val kept = ArrayList<Detection>(input.size)
        for ((_, group) in byCls) {
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                val it = sorted.iterator()
                while (it.hasNext()) {
                    if (iouRect(best.box, it.next().box) > iou) it.remove()
                }
            }
        }
        return kept
    }

    private fun iouRect(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val w = (x2 - x1).coerceAtLeast(0f)
        val h = (y2 - y1).coerceAtLeast(0f)
        val inter = w * h
        val ua = (a.right - a.left) * (a.bottom - a.top)
        val ub = (b.right - b.left) * (b.bottom - b.top)
        val u = ua + ub - inter
        return if (u <= 0f) 0f else inter / u
    }

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
