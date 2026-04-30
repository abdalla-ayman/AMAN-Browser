package com.aman.browser.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import com.aman.browser.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * InferenceEngine
 *
 * Orchestrates the full ML pipeline:
 *   1. Image preprocessing → JNI C++ (NEON-optimised bilinear resize + normalise)
 *   2. NSFW / skin detection → TFLite MobileNetV3 or EfficientNet-Lite
 *   3. Face detection        → TFLite MediaPipe Face Detection
 *   4. Gender classification → TFLite MobileNetV3 (separate head or joint model)
 *
 * All inference is on-device. Zero network calls.
 */
class InferenceEngine private constructor(private val context: Context) {

    // ── JNI declarations ─────────────────────────────────────────────────────
    external fun preprocessRgba(rgbaBytes: ByteArray, srcWidth: Int, srcHeight: Int): FloatArray?
    external fun preprocessBitmap(bitmap: Bitmap): FloatArray?
    external fun getModelInputSize(): Int

    // ── TFLite Interpreters ───────────────────────────────────────────────────
    private var nsfwInterpreter:   Interpreter? = null
    private var faceInterpreter:   Interpreter? = null
    private var skinInterpreter:   Interpreter? = null
    private var genderInterpreter: Interpreter? = null

    // ── Delegates ─────────────────────────────────────────────────────────────
    private var gpuDelegate:   GpuDelegate?   = null
    private var nnapiDelegate: NnApiDelegate? = null

    private val isReady = AtomicBoolean(false)
    @Volatile private var faceInputSize: Int = FACE_INPUT_SIZE_FULL
    @Volatile private var faceModelIsFullRange: Boolean = true
    @Volatile private var genderInputSize: Int = GENDER_INPUT_SIZE_LEGACY
    @Volatile private var genderNormalizeToMinusOneToOne: Boolean = false
    @Volatile private var genderMaleIndex: Int = 0
    @Volatile private var genderFemaleIndex: Int = 1
    // Anchors are computed eagerly in initialize() so the (one-time) cost is
    // paid up-front instead of on the first classified image.
    @Volatile private var blazeFaceAnchors: List<BlazeFaceAnchor> = emptyList()

    // ── Concurrency primitives ───────────────────────────────────────────
    // The TensorFlow Lite Java Interpreter is NOT thread-safe: concurrent
    // run() calls on the same Interpreter corrupt state and crash. We allow
    // multiple concurrent classifyBitmap() calls (e.g. several images on the
    // same page) but serialize each individual interpreter behind its own
    // lock so that different interpreters can still execute in parallel
    // (NSFW + skin + face all overlap, which is the whole point of the
    // parallel coroutineScope below).
    private val nsfwLock = Any()
    private val skinLock = Any()
    private val faceLock = Any()
    private val genderLock = Any()

    // Tracks classifications currently in flight so destroy()/initialize()
    // (e.g. when the user toggles the GPU delegate in Settings) can drain
    // before tearing down the interpreters under their feet.
    private val pendingInferences = AtomicInteger(0)

    // ── Allocation pools (per-thread) ────────────────────────────────────
    // bitmapToFloatBuffer and floatArrayToByteBuffer were the two top-N
    // allocators in the hot path: each call allocated a new IntArray + a
    // new direct ByteBuffer. Inference happens on Dispatchers.Default which
    // has a small fixed pool of worker threads, so a ThreadLocal cache is a
    // perfect fit. These maps are keyed by tensor size (224, 192, 128).
    private val tlPixelArrays = ThreadLocal.withInitial { HashMap<Int, IntArray>() }
    private val tlByteBuffers = ThreadLocal.withInitial { HashMap<Int, ByteBuffer>() }
    // Cache by FloatArray length (e.g. 224*224*3 = 150528).
    private val tlFloatBuffers = ThreadLocal.withInitial { HashMap<Int, ByteBuffer>() }

    private fun pixelsFor(inputSize: Int): IntArray {
        val map = tlPixelArrays.get()!!
        return map.getOrPut(inputSize) { IntArray(inputSize * inputSize) }
    }

    private fun directBufferFor(inputSize: Int): ByteBuffer {
        val map = tlByteBuffers.get()!!
        val buf = map.getOrPut(inputSize) {
            ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder())
        }
        buf.clear()
        return buf
    }

    private fun floatBufferFor(floatCount: Int): ByteBuffer {
        val map = tlFloatBuffers.get()!!
        val buf = map.getOrPut(floatCount) {
            ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
        }
        buf.clear()
        return buf
    }

    companion object {
        private const val TAG = "InferenceEngine"

        // Model filenames (place .tflite files in app/src/main/assets/models/)
        private const val MODEL_NSFW   = "models/nsfw_mobilenetv3.tflite"
        // BlazeFace full-range (192x192, 2304 anchors). Falls back to the
        // short-range model (128x128, 896 anchors) if not present.
        private const val MODEL_FACE_FULL  = "models/face_detection_full.tflite"
        private const val MODEL_FACE_SHORT = "models/face_detection_short.tflite"
        private const val MODEL_SKIN   = "models/skin_classifier.tflite"
        private const val MODEL_GENDER_MODERN = "models/gender_mobilenetv3.tflite"
        private const val MODEL_GENDER_LEGACY = "models/model_gender_q.tflite"

        // Updated at load-time based on which face model is actually present.
        private const val FACE_INPUT_SIZE_FULL  = 192
        private const val FACE_INPUT_SIZE_SHORT = 128
        private const val GENDER_INPUT_SIZE_LEGACY = 128
        private const val GENDER_INPUT_SIZE_MODERN = 224
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val MAX_DECODED_IMAGE_SIDE = 1280
        // Absolute safety floor; the user's sensitivity threshold is used
        // directly above this floor so the slider has real effect.
        private const val NSFW_SCORE_FLOOR = 0.50f
        private const val FACE_SCORE_THRESHOLD = 0.5f
        private const val SKIN_SCORE_THRESHOLD = 0.75f
        // Skin alone false-positives heavily on food / sand / wood tones.
        // Only trigger when corroborated by either a face or a moderate
        // NSFW score; otherwise raise the bar.
        private const val SKIN_CORROBORATING_NSFW = 0.35f
        private const val SKIN_SOLO_THRESHOLD = 0.92f
        private const val GENDER_CONFIDENCE_THRESHOLD = 0.70f
        // Reject images smaller than this on either axis (icons, sprites,
        // tracking pixels) — running 4 models on them is wasted work and a
        // common source of false positives.
        private const val MIN_IMAGE_DIMENSION = 64
        // Drop face detections smaller than this fraction of total area;
        // BlazeFace produces unstable boxes on tiny faces and gender
        // classification on them is essentially random.
        private const val MIN_FACE_AREA_FRACTION = 0.02f
        // Padding added around each face bounding box before cropping for
        // gender classification. Gender models are trained on aligned faces
        // with hair/jaw context; tight crops degrade accuracy noticeably.
        private const val FACE_CROP_PADDING = 0.25f

        @Volatile private var instance: InferenceEngine? = null

        fun get(context: Context): InferenceEngine =
            instance ?: synchronized(this) {
                instance ?: InferenceEngine(context.applicationContext).also { instance = it }
            }

        init {
            System.loadLibrary("aman_inference")
        }

        // Expose for testing
        external fun nativeVersion(): String
    }

    // ── Initialisation ────────────────────────────────────────────────────────
    fun initialize(useGpu: Boolean, useNnapi: Boolean) {
        if (isReady.get()) return
        try {
            val options = buildInterpreterOptions(useGpu, useNnapi)
            nsfwInterpreter   = loadModel(MODEL_NSFW,   options)

            // Prefer the higher-accuracy full-range BlazeFace (192px). If the
            // asset isn't bundled, fall back to the short-range model (128px).
            val full = loadModel(MODEL_FACE_FULL, options)
            if (full != null) {
                faceInterpreter = full
                faceInputSize = FACE_INPUT_SIZE_FULL
                faceModelIsFullRange = true
                Log.i(TAG, "Loaded face model: full-range 192px")
            } else {
                faceInterpreter = loadModel(MODEL_FACE_SHORT, options)
                faceInputSize = FACE_INPUT_SIZE_SHORT
                faceModelIsFullRange = false
                Log.i(TAG, "Loaded face model: short-range 128px (fallback)")
            }

            skinInterpreter   = loadModel(MODEL_SKIN,   options)
            genderInterpreter = loadGenderModel(options)
            // Pre-compute anchors now (~2304 entries) so the first frame
            // doesn't pay the cost on the render path.
            blazeFaceAnchors = generateBlazeFaceAnchors(faceInputSize, faceModelIsFullRange)
            isReady.set(true)
            Log.i(TAG, "InferenceEngine ready. GPU=$useGpu NNAPI=$useNnapi")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            // App still usable; classify() will return SAFE
        }
    }

    private fun buildInterpreterOptions(useGpu: Boolean, useNnapi: Boolean): Interpreter.Options {
        // numThreads = 1: with 4 separate interpreters running in parallel
        // through the inference pipeline (NSFW + face + skin + gender), giving
        // each interpreter 2 threads led to up to 8 simultaneous CPU threads
        // contending for cores while the browser is also rendering. One
        // thread per interpreter is faster end-to-end on typical 4-8 core
        // mobile devices, especially when GPU/NNAPI delegates handle the
        // heavy lifting.
        val options = Interpreter.Options().apply {
            numThreads = 1
        }

        if (useGpu) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate!!)
                    Log.i(TAG, "GPU delegate enabled")
                    return options
                } else {
                    Log.w(TAG, "GPU not supported on this device")
                }
            } catch (e: Throwable) {
                // GpuDelegateFactory missing in this TFLite build — fall through to CPU
                Log.w(TAG, "GPU delegate unavailable: ${e.message}")
                gpuDelegate = null
            }
        }

        if (useNnapi) {
            try {
                nnapiDelegate = NnApiDelegate()
                options.addDelegate(nnapiDelegate!!)
                Log.i(TAG, "NNAPI delegate enabled")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI delegate unavailable: ${e.message}")
            }
        }

        return options
    }

    private fun loadModel(assetPath: String, options: Interpreter.Options): Interpreter? {
        return try {
            val buffer = loadModelFile(assetPath)
            Interpreter(buffer, options)
        } catch (e: IOException) {
            Log.w(TAG, "Model not found: $assetPath — blurring for this category disabled")
            null
        }
    }

    private fun loadGenderModel(options: Interpreter.Options): Interpreter? {
        loadModel(MODEL_GENDER_MODERN, options)?.let { interpreter ->
            if (configureGenderModel(
                    interpreter = interpreter,
                    label = MODEL_GENDER_MODERN,
                    defaultInputSize = GENDER_INPUT_SIZE_MODERN,
                    maleIndex = 1,
                    femaleIndex = 0,
                    normalizeToMinusOneToOne = true,
                )
            ) return interpreter
        }

        loadModel(MODEL_GENDER_LEGACY, options)?.let { interpreter ->
            if (configureGenderModel(
                    interpreter = interpreter,
                    label = MODEL_GENDER_LEGACY,
                    defaultInputSize = GENDER_INPUT_SIZE_LEGACY,
                    maleIndex = 0,
                    femaleIndex = 1,
                    normalizeToMinusOneToOne = false,
                )
            ) return interpreter
        }

        return null
    }

    private fun configureGenderModel(
        interpreter: Interpreter,
        label: String,
        defaultInputSize: Int,
        maleIndex: Int,
        femaleIndex: Int,
        normalizeToMinusOneToOne: Boolean,
    ): Boolean {
        return try {
            val inputShape = interpreter.getInputTensor(0).shape()
            val outputShape = interpreter.getOutputTensor(0).shape()
            val channels = inputShape.lastOrNull() ?: 0
            val outputClasses = outputShape.lastOrNull() ?: 0
            val inputSize = inputShape.getOrNull(1)?.takeIf { it > 0 } ?: defaultInputSize
            val requiredClasses = maxOf(maleIndex, femaleIndex) + 1

            if (channels != 3 || outputClasses != 2 || outputClasses < requiredClasses) {
                Log.w(TAG, "Ignoring invalid gender model $label input=${inputShape.contentToString()} output=${outputShape.contentToString()}")
                interpreter.close()
                return false
            }

            genderInputSize = inputSize
            genderMaleIndex = maleIndex
            genderFemaleIndex = femaleIndex
            genderNormalizeToMinusOneToOne = normalizeToMinusOneToOne
            Log.i(TAG, "Loaded gender model: $label (${inputSize}px)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect gender model $label", e)
            try { interpreter.close() } catch (_: Exception) {}
            false
        }
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { fd ->
            FileInputStream(fd.fileDescriptor).use { inputStream ->
                return inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    // ── Main classify API ─────────────────────────────────────────────────────
    /**
     * Classify an image from an InputStream (fetched by Kotlin from OkHttp).
     * Runs on the calling coroutine's dispatcher; caller should use
     * Dispatchers.Default.
     *
     * @param stream         decoded image stream
     * @param checkNsfw      run NSFW model
     * @param checkFace      run face / gender models
     * @param checkSkin      run skin model
     * @param genderFilter   0=all  1=female-only  2=male-only  3=no-gender-filter
     * @param nsfwThreshold  0..1 sensitivity threshold
     * @param blurIntensity  unused here; applied in JS
     */
    suspend fun classifyStream(
        stream:         InputStream,
        checkNsfw:      Boolean,
        checkFace:      Boolean,
        checkSkin:      Boolean,
        genderFilter:   Int,
        nsfwThreshold:  Float,
    ): DetectionResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()

        val bitmap = try {
            decodeSampledBitmap(stream)
        } catch (e: Exception) {
            Log.e(TAG, "decodeStream failed", e)
            return@withContext DetectionResult.ERROR
        }

        if (bitmap == null) return@withContext DetectionResult.ERROR

        try {
            classifyBitmap(
                bitmap        = bitmap,
                checkNsfw     = checkNsfw,
                checkFace     = checkFace,
                checkSkin     = checkSkin,
                genderFilter  = genderFilter,
                nsfwThreshold = nsfwThreshold,
                startMs       = startMs,
            )
        } finally {
            // Decoded bitmaps from the network are unique to this call —
            // recycle eagerly to keep heap pressure flat on infinite-scroll
            // image feeds (Reddit/Pinterest/Twitter).
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun decodeSampledBitmap(stream: InputStream): Bitmap? {
        val bytes = readStreamLimited(stream, MAX_IMAGE_BYTES) ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODED_IMAGE_SIDE)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun readStreamLimited(stream: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val largestSide = maxOf(width, height)
        while (largestSide / sample > maxSide) sample *= 2
        return sample
    }

    suspend fun classifyBitmap(
        bitmap:        Bitmap,
        checkNsfw:     Boolean,
        checkFace:     Boolean,
        checkSkin:     Boolean,
        genderFilter:  Int,
        nsfwThreshold: Float,
        startMs:       Long = System.currentTimeMillis(),
    ): DetectionResult = withContext(Dispatchers.Default) {
        if (!isReady.get()) return@withContext DetectionResult.SAFE

        // ── Skip thumbnails / icons / tracking pixels ───────────────────────
        // Running four CNNs on a 16x16 favicon is pure waste and a common
        // false-positive source (sub-sampled colour blobs trigger skin/NSFW).
        if (bitmap.width < MIN_IMAGE_DIMENSION || bitmap.height < MIN_IMAGE_DIMENSION) {
            return@withContext DetectionResult.SAFE
        }
        pendingInferences.incrementAndGet()
        try {
            classifyBitmapInternal(bitmap, checkNsfw, checkFace, checkSkin, genderFilter, nsfwThreshold, startMs)
        } finally {
            pendingInferences.decrementAndGet()
        }
    }

    private suspend fun classifyBitmapInternal(
        bitmap:        Bitmap,
        checkNsfw:     Boolean,
        checkFace:     Boolean,
        checkSkin:     Boolean,
        genderFilter:  Int,
        nsfwThreshold: Float,
        startMs:       Long,
    ): DetectionResult {
        // ── C++ preprocessing (NEON-optimised) for 224x224 classifiers ─────
        val imageTensor = preprocessBitmap(bitmap)
            ?: return DetectionResult.ERROR
        val imageInput = floatArrayToByteBuffer(imageTensor)

        val peopleEnabled = checkFace && genderFilter != PreferencesManager.GENDER_NO_PEOPLE

        // ── Run independent models concurrently ─────────────────────────────
        // NSFW, skin and face share no state; each interpreter has its own
        // numThreads=1 setting and may dispatch to GPU/NNAPI. Doing them in
        // parallel pipelines CPU + accelerator and cuts wall-time on most
        // images by 30-40%.
        val (nsfwScore, skinScore, faces) = coroutineScope {
            val nsfwAsync = async {
                if (checkNsfw) runNsfwClassifier(nsfwInterpreter, imageInput.duplicate()) else 0f
            }
            val skinAsync = async {
                if (checkSkin) runBinaryClassifier(skinInterpreter, imageInput.duplicate()) else 0f
            }
            val facesAsync = async {
                if (peopleEnabled) runBlazeFaceDetector(faceInterpreter, bitmap) else emptyList()
            }
            Triple(nsfwAsync.await(), skinAsync.await(), facesAsync.await())
        }

        // ── Filter face detections (drop tiny / spurious boxes) ────────────
        val significantFaces = faces.filter {
            val b = it.boundingBox ?: return@filter true
            (b.right - b.left) * (b.bottom - b.top) >= MIN_FACE_AREA_FRACTION
        }
        val faceCount = significantFaces.size
        val faceScore = significantFaces.maxOfOrNull { it.score } ?: 0f

        // ── Gender (only when the active filter actually depends on it) ────
        var femaleScore = -1f
        var maleScore   = -1f
        // Weighted votes: each face contributes (confidence × area). A single
        // large confident face dominates a swarm of tiny borderline ones.
        var femaleWeight = 0f
        var maleWeight   = 0f

        val needsGender = peopleEnabled && significantFaces.isNotEmpty() && (
            genderFilter == PreferencesManager.GENDER_FEMALES_ONLY ||
            genderFilter == PreferencesManager.GENDER_MALES_ONLY
        )
        if (needsGender) {
            var bestFemale = 0f
            var bestMale = 0f
            for (face in significantFaces) {
                val box = face.boundingBox ?: continue
                val area = (box.right - box.left) * (box.bottom - box.top)
                val faceBitmap = cropFace(bitmap, box, padding = FACE_CROP_PADDING)
                val gender = try {
                    runGenderModel(faceBitmap)
                } finally {
                    // The cropped face bitmap was created just for the
                    // gender model; release it immediately to avoid heap
                    // pressure when a page has many faces.
                    if (!faceBitmap.isRecycled) faceBitmap.recycle()
                }
                if (gender.confidence < GENDER_CONFIDENCE_THRESHOLD) continue
                val weight = gender.confidence * area
                if (gender.isFemale) {
                    femaleWeight += weight
                    if (gender.confidence > bestFemale) bestFemale = gender.confidence
                } else {
                    maleWeight += weight
                    if (gender.confidence > bestMale) bestMale = gender.confidence
                }
            }
            femaleScore = bestFemale
            maleScore = bestMale
        }

        // ── Decision logic ─────────────────────────────────────────────────
        // NSFW: respect the user's sensitivity slider (low slider = more
        // sensitive). The previous implementation clamped the threshold at
        // 0.85, which silently disabled the slider for 'Strict' mode. We now
        // use the user value directly and only apply a sanity floor of 0.50.
        val nsfwTrigger = checkNsfw && nsfwInterpreter != null &&
            nsfwScore >= maxOf(nsfwThreshold, NSFW_SCORE_FLOOR)

        // Skin alone is unreliable on food, sand, wood, beige walls. Require
        // either (a) a corroborating face/NSFW signal, or (b) a very high
        // skin score.
        val skinCorroborated = (peopleEnabled && faceCount > 0) ||
            nsfwScore >= SKIN_CORROBORATING_NSFW
        val skinTrigger = checkSkin && skinInterpreter != null && (
            (skinCorroborated && skinScore > SKIN_SCORE_THRESHOLD) ||
            skinScore > SKIN_SOLO_THRESHOLD
        )

        val peopleTrigger = when (genderFilter) {
            PreferencesManager.GENDER_NO_PEOPLE -> false
            PreferencesManager.GENDER_EVERYONE -> faceCount > 0
            PreferencesManager.GENDER_FEMALES_ONLY -> femaleWeight > maleWeight && femaleWeight > 0f
            PreferencesManager.GENDER_MALES_ONLY -> maleWeight > femaleWeight && maleWeight > 0f
            else -> faceCount > 0
        }

        val shouldBlur = nsfwTrigger || skinTrigger || peopleTrigger

        return DetectionResult(
            nsfwScore   = nsfwScore,
            faceScore   = faceScore,
            skinScore   = skinScore,
            femaleScore = femaleScore,
            maleScore   = maleScore,
            shouldBlur  = shouldBlur,
            elapsedMs   = System.currentTimeMillis() - startMs,
            faceCount   = faceCount,
        )
    }

    // ── Inference helpers ─────────────────────────────────────────────────────
    /**
     * GantMan NSFW model: 5-class output [drawings(0), hentai(1), neutral(2), porn(3), sexy(4)].
     * Returns weighted NSFW score: porn + hentai full weight, sexy half
     * weight. "sexy" alone is a notorious false-positive class (swimwear,
     * sportswear, fashion) so we de-rate it; truly explicit content scores
     * high in porn/hentai which dominate the sum.
     */
    private fun runNsfwClassifier(interpreter: Interpreter?, input: ByteBuffer): Float {
        if (interpreter == null) return 0f
        return try {
            val output = Array(1) { FloatArray(5) }
            synchronized(nsfwLock) { interpreter.run(input, output) }
            val scores = output[0]
            (scores[1] + scores[3] + 0.5f * scores[4]).coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "NSFW inference error", e)
            0f
        }
    }

    /** Run a binary classifier (2 outputs: [safe, unsafe]) → returns unsafe score. */
    private fun runBinaryClassifier(interpreter: Interpreter?, input: ByteBuffer): Float {
        if (interpreter == null) return 0f
        return try {
            val output = Array(1) { FloatArray(2) }
            synchronized(skinLock) { interpreter.run(input, output) }
            output[0][1] // index 1 = unsafe / positive class
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            0f
        }
    }

    /**
     * Crop a face for downstream gender classification. Adds [padding]
     * (fraction of the box's larger side) on every side so the crop
     * includes hair/jawline context — gender models are trained on aligned
     * faces with that context and degrade markedly on tight crops.
     */
    fun cropFace(bitmap: Bitmap, boundingBox: RectF, padding: Float = 0f): Bitmap {
        val w = (boundingBox.right - boundingBox.left).coerceAtLeast(0f)
        val h = (boundingBox.bottom - boundingBox.top).coerceAtLeast(0f)
        val pad = maxOf(w, h) * padding
        val padLeft = (boundingBox.left - pad).coerceIn(0f, 1f)
        val padTop = (boundingBox.top - pad).coerceIn(0f, 1f)
        val padRight = (boundingBox.right + pad).coerceIn(0f, 1f)
        val padBottom = (boundingBox.bottom + pad).coerceIn(0f, 1f)

        val left = (padLeft * bitmap.width).roundToInt()
            .coerceIn(0, bitmap.width - 1)
        val top = (padTop * bitmap.height).roundToInt()
            .coerceIn(0, bitmap.height - 1)
        val right = (padRight * bitmap.width).roundToInt()
            .coerceIn(left + 1, bitmap.width)
        val bottom = (padBottom * bitmap.height).roundToInt()
            .coerceIn(top + 1, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val targetSize = genderInputSize
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        // createScaledBitmap may return the same instance when dimensions
        // already match. Only recycle the intermediate when it's distinct.
        if (scaled !== cropped && !cropped.isRecycled) cropped.recycle()
        return scaled
    }

    fun runGenderModel(faceBitmap: Bitmap): GenderResult {
        val interpreter = genderInterpreter ?: return GenderResult(isFemale = false, confidence = 0f)
        return try {
            val input = bitmapToFloatBuffer(
                bitmap = faceBitmap,
                inputSize = genderInputSize,
                normalizeToMinusOneToOne = genderNormalizeToMinusOneToOne,
            )
            val output = Array(1) { FloatArray(2) }
            synchronized(genderLock) { interpreter.run(input, output) }
            val maleProbability = output[0][genderMaleIndex].coerceIn(0f, 1f)
            val femaleProbability = output[0][genderFemaleIndex].coerceIn(0f, 1f)
            if (femaleProbability >= maleProbability) {
                GenderResult(isFemale = true, confidence = femaleProbability)
            } else {
                GenderResult(isFemale = false, confidence = maleProbability)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gender inference error", e)
            GenderResult(isFemale = false, confidence = 0f)
        }
    }

    private fun runBlazeFaceDetector(interpreter: Interpreter?, bitmap: Bitmap): List<FaceDetectionCandidate> {
        if (interpreter == null) return emptyList()
        return try {
            val input = bitmapToFloatBuffer(
                bitmap = bitmap,
                inputSize = faceInputSize,
                normalizeToMinusOneToOne = true,
            )

            if (interpreter.outputTensorCount < 2) {
                val legacy = runLegacyFaceClassifier(interpreter, input)
                return if (legacy.score >= FACE_SCORE_THRESHOLD) listOf(legacy) else emptyList()
            }

            val firstShape = interpreter.getOutputTensor(0).shape()
            val secondShape = interpreter.getOutputTensor(1).shape()
            val boxIndex = when {
                firstShape.lastOrNull() == 16 -> 0
                secondShape.lastOrNull() == 16 -> 1
                else -> {
                    val legacy = runLegacyFaceClassifier(interpreter, input)
                    return if (legacy.score >= FACE_SCORE_THRESHOLD) listOf(legacy) else emptyList()
                }
            }
            val scoreIndex = if (boxIndex == 0) 1 else 0
            val boxShape = interpreter.getOutputTensor(boxIndex).shape()
            val scoreShape = interpreter.getOutputTensor(scoreIndex).shape()
            val boxCount = boxShape.getOrNull(1) ?: return emptyList()
            val boxDepth = boxShape.getOrNull(2) ?: return emptyList()
            val scoreCount = scoreShape.getOrNull(1) ?: return emptyList()
            val scoreDepth = scoreShape.getOrNull(2) ?: 1

            val boxes = Array(1) { Array(boxCount) { FloatArray(boxDepth) } }
            val scores = Array(1) { Array(scoreCount) { FloatArray(scoreDepth) } }
            val outputs = mutableMapOf<Int, Any>(
                boxIndex to boxes,
                scoreIndex to scores,
            )
            synchronized(faceLock) {
                interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
            }
            decodeBlazeFace(boxes[0], scores[0])
        } catch (e: Exception) {
            Log.e(TAG, "BlazeFace inference error", e)
            emptyList()
        }
    }

    private fun runLegacyFaceClassifier(interpreter: Interpreter, input: ByteBuffer): FaceDetectionCandidate {
        return try {
            input.rewind()
            val output = Array(1) { FloatArray(2) }
            synchronized(faceLock) { interpreter.run(input, output) }
            FaceDetectionCandidate(score = output[0][1].coerceIn(0f, 1f), boundingBox = null)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy face inference error", e)
            FaceDetectionCandidate(score = 0f, boundingBox = null)
        }
    }

    /**
     * Decodes BlazeFace raw outputs into a list of face candidates above
     * [FACE_SCORE_THRESHOLD], then applies non-maximum suppression so
     * overlapping detections of the same face collapse into one — but
     * distinct people are kept. This is what makes "Blur Everyone" actually
     * blur everyone.
     */
    private fun decodeBlazeFace(
        rawBoxes: Array<FloatArray>,
        rawScores: Array<FloatArray>,
    ): List<FaceDetectionCandidate> {
        val count = min(min(rawBoxes.size, rawScores.size), blazeFaceAnchors.size)
        val candidates = ArrayList<FaceDetectionCandidate>(16)

        for (index in 0 until count) {
            val score = sigmoid(rawScores[index].firstOrNull() ?: continue)
            if (score < FACE_SCORE_THRESHOLD) continue

            val box = rawBoxes[index]
            if (box.size < 4) continue
            val anchor = blazeFaceAnchors[index]

            // BlazeFace box layout: [yCenter, xCenter, height, width] in
            // input-pixel units relative to the anchor center.
            val yCenter = box[0] / faceInputSize * anchor.height + anchor.yCenter
            val xCenter = box[1] / faceInputSize * anchor.width + anchor.xCenter
            val height = box[2] / faceInputSize * anchor.height
            val width = box[3] / faceInputSize * anchor.width
            val left = (xCenter - width / 2f).coerceIn(0f, 1f)
            val top = (yCenter - height / 2f).coerceIn(0f, 1f)
            val right = (xCenter + width / 2f).coerceIn(0f, 1f)
            val bottom = (yCenter + height / 2f).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) continue

            candidates += FaceDetectionCandidate(
                score = score,
                boundingBox = RectF(left, top, right, bottom),
            )
        }

        return nonMaxSuppress(candidates, iouThreshold = 0.3f)
    }

    /**
     * Greedy IoU-based non-maximum suppression. Operates on
     * [FaceDetectionCandidate.boundingBox] in normalized [0,1] coordinates.
     * Candidates without a bounding box are passed through unchanged.
     */
    private fun nonMaxSuppress(
        candidates: List<FaceDetectionCandidate>,
        iouThreshold: Float,
    ): List<FaceDetectionCandidate> {
        if (candidates.isEmpty()) return candidates
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<FaceDetectionCandidate>(sorted.size)
        for (candidate in sorted) {
            val box = candidate.boundingBox
            if (box == null) {
                kept += candidate
                continue
            }
            var suppressed = false
            for (other in kept) {
                val otherBox = other.boundingBox ?: continue
                if (iou(box, otherBox) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) kept += candidate
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = interRight - interLeft
        val interH = interBottom - interTop
        if (interW <= 0f || interH <= 0f) return 0f
        val interArea = interW * interH
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun bitmapToFloatBuffer(
        bitmap: Bitmap,
        inputSize: Int,
        normalizeToMinusOneToOne: Boolean,
    ): ByteBuffer {
        val resized = if (bitmap.width == inputSize && bitmap.height == inputSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        }
        // Pooled IntArray + DirectByteBuffer keyed by inputSize. The hot
        // path (face detect + N gender crops per image) avoids a fresh
        // ~150-300KB direct allocation per call.
        val pixels = pixelsFor(inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val buffer = directBufferFor(inputSize)
        // Indexed loop avoids the boxing iterator created by IntArray.forEach.
        if (normalizeToMinusOneToOne) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                buffer.putFloat(((pixel shr 16 and 0xFF) / 127.5f) - 1f)
                buffer.putFloat(((pixel shr 8 and 0xFF) / 127.5f) - 1f)
                buffer.putFloat(((pixel and 0xFF) / 127.5f) - 1f)
            }
        } else {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                buffer.putFloat((pixel shr 16 and 0xFF) / 255f)
                buffer.putFloat((pixel shr 8 and 0xFF) / 255f)
                buffer.putFloat((pixel and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        // Recycle the temporary scaled bitmap; the caller owns the original.
        if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        return buffer
    }

    /**
     * MediaPipe BlazeFace SSD anchor generator.
     *
     * Short-range (128 px): 4 layers, strides=[8,16,16,16], 2 anchors per
     *   location (interpolated_scale_aspect_ratio=1.0) → 896 anchors.
     * Full-range  (192 px): 1 layer,  strides=[4],          1 anchor  per
     *   location (interpolated_scale_aspect_ratio=0.0) → 2304 anchors.
     */
    private fun generateBlazeFaceAnchors(
        inputSize: Int,
        fullRange: Boolean,
    ): List<BlazeFaceAnchor> {
        val strides = if (fullRange) intArrayOf(4) else intArrayOf(8, 16, 16, 16)
        val anchorsPerLocation = if (fullRange) 1 else 2 // interpolated => 2
        val anchors = mutableListOf<BlazeFaceAnchor>()
        var layerId = 0

        while (layerId < strides.size) {
            var lastSameStrideLayer = layerId
            while (lastSameStrideLayer < strides.size &&
                strides[lastSameStrideLayer] == strides[layerId]
            ) {
                lastSameStrideLayer++
            }
            val layersInThisStride = lastSameStrideLayer - layerId
            val totalAnchorsPerLoc = layersInThisStride * anchorsPerLocation

            val stride = strides[layerId]
            val featureMapHeight = ceil(inputSize.toFloat() / stride).toInt()
            val featureMapWidth = ceil(inputSize.toFloat() / stride).toInt()
            for (y in 0 until featureMapHeight) {
                for (x in 0 until featureMapWidth) {
                    repeat(totalAnchorsPerLoc) {
                        anchors += BlazeFaceAnchor(
                            xCenter = (x + 0.5f) / featureMapWidth,
                            yCenter = (y + 0.5f) / featureMapHeight,
                            width = 1f,
                            height = 1f,
                        )
                    }
                }
            }
            layerId = lastSameStrideLayer
        }
        return anchors
    }

    private fun sigmoid(value: Float): Float {
        val clipped = value.coerceIn(-100f, 100f)
        return (1f / (1f + exp(-clipped))).coerceIn(0f, 1f)
    }

    private data class FaceDetectionCandidate(
        val score: Float,
        val boundingBox: RectF?,
    )

    private data class BlazeFaceAnchor(
        val xCenter: Float,
        val yCenter: Float,
        val width: Float,
        val height: Float,
    )

    private fun floatArrayToByteBuffer(floats: FloatArray): ByteBuffer {
        // Allocated per-call (NOT pooled): the resulting buffer is shared
        // across the parallel NSFW/skin asyncs via duplicate(), so pooling
        // would race when a second classifyBitmap on the same dispatcher
        // thread overwrote it while the first call's interpreters were
        // still reading. The hot win here is the bulk asFloatBuffer().put
        // which moves ~600 KB in a single memcpy instead of N×putFloat.
        val buf = ByteBuffer.allocateDirect(floats.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        buf.asFloatBuffer().put(floats)
        // asFloatBuffer is a view, so the parent's position is unchanged.
        // Rewind for safety; TFLite reads from the current position.
        buf.rewind()
        return buf
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    fun destroy() {
        // Mark not-ready first so any classifyBitmap call that hasn't
        // started yet bails immediately.
        isReady.set(false)
        // Wait (briefly) for in-flight inferences to drain. Without this
        // we'd close interpreters from under live run() calls → native
        // crash. Cap the wait so a stuck inference cannot block app exit
        // or settings reconfiguration.
        val deadline = System.currentTimeMillis() + 1_500L
        while (pendingInferences.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        // Guard the actual close with each interpreter's lock so the
        // synchronized blocks in run* helpers see a consistent state.
        synchronized(nsfwLock)   { nsfwInterpreter?.close();   nsfwInterpreter   = null }
        synchronized(faceLock)   { faceInterpreter?.close();   faceInterpreter   = null }
        synchronized(skinLock)   { skinInterpreter?.close();   skinInterpreter   = null }
        synchronized(genderLock) { genderInterpreter?.close(); genderInterpreter = null }
        gpuDelegate?.close();   gpuDelegate   = null
        nnapiDelegate?.close(); nnapiDelegate = null
        // Note: we deliberately do NOT clear `instance` here. The Settings
        // screen calls destroy() then initialize() to swap delegates (CPU
        // ↔ GPU); nulling instance would orphan this engine and create a
        // second one on the next get(), leaking interpreters.
    }
}
