package com.aman.browser.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aman.browser.AmanApplication
import com.aman.browser.data.PreferencesManager
import com.aman.browser.ml.DetectionResult
import com.aman.browser.ml.InferenceEngine
import com.aman.browser.ml.primaryCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebExtensionManager
 *
 * Responsibilities:
 *   • Load the bundled WebExtension from assets
 *   • Register a PortDelegate to receive image-URL messages from content.js
 *   • Fetch image → C++ preprocess → TFLite classify
 *   • Send blur/no-blur decision back to content.js
 *   • Apply settings updates to all active tabs instantly
 */
class WebExtensionManager(
    private val context: Context,
    private val runtime: GeckoRuntime,
    private val prefs:   PreferencesManager,
) {
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine get() = InferenceEngine.get(context)

    // Shared OkHttp client tuned for the bursty image-fetching workload of the
    // content filter. A bounded dispatcher and connection pool stop us from
    // saturating the network thread pool when a page like Reddit/Pinterest
    // dumps hundreds of images into the viewport at once.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 6
        })
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(false)
        .build()

    private var extension: WebExtension? = null

    // Active native ports (one per background.js connection) so we can push setting changes
    private val activePorts = CopyOnWriteArraySet<WebExtension.Port>()

    /**
     * LRU cache of URL → blur decision. Identical images that appear multiple
     * times across page loads in this session get a consistent answer (and we
     * skip the network + inference cost).
     *
     * Capped at 256 entries; entries flushed when settings change.
     */
    private val resultCache = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?) = size > 256
    }
    private val cacheLock = Any()
    private val cacheGeneration = AtomicInteger(0)
    private val inFlightClassifications = ConcurrentHashMap<ClassificationKey, Deferred<DetectionResult>>()
    private fun cachedResult(url: String): Boolean? = synchronized(cacheLock) { resultCache[url] }
    private fun cacheResult(url: String, blur: Boolean) {
        synchronized(cacheLock) { resultCache[url] = blur }
    }
    private fun flushCache() {
        synchronized(cacheLock) { resultCache.clear() }
        cacheGeneration.incrementAndGet()
    }

    /** Build the current snapshot of settings to push to content scripts. */
    private suspend fun buildSettingsMessage(): JSONObject = JSONObject()
        .put("type", "settings_update")
        .put("enabled", true)
        .put("gender_filter", prefs.genderFilter.first())
        .put("blur_px", PreferencesManager.blurIntensityToPx(prefs.blurIntensity.first()))
        .put("tap_to_unblur", prefs.tapToUnblur.first())
        .put("blur_on_load", prefs.blurOnLoad.first())

    private fun broadcastSettings() {
        scope.launch {
            val msg = buildSettingsMessage()
            activePorts.forEach { port ->
                try { port.postMessage(msg) } catch (_: Exception) {}
            }
        }
    }

    init {
        // One conflated collector across all settings. Each underlying flow is
        // wrapped in distinctUntilChanged so DataStore re-emissions for an
        // unchanged value (which happen on every edit) don't trigger a cache
        // flush + broadcast storm. Only the gender filter and sensitivity
        // change ML decisions, so only those flush the result cache.
        scope.launch {
            var lastGender = -1
            var lastSensitivity = -1
            combine(
                prefs.genderFilter.distinctUntilChanged(),
                prefs.blurIntensity.distinctUntilChanged(),
                prefs.tapToUnblur.distinctUntilChanged(),
                prefs.blurOnLoad.distinctUntilChanged(),
                prefs.sensitivity.distinctUntilChanged(),
            ) { g, _, _, _, s -> g to s }
                .distinctUntilChanged()
                .collect { (gender, sens) ->
                    val genderChanged = lastGender != -1 && gender != lastGender
                    val sensitivityChanged = lastSensitivity != -1 && sens != lastSensitivity
                    if (genderChanged || sensitivityChanged) flushCache()
                    lastGender = gender
                    lastSensitivity = sens
                    broadcastSettings()
                }
        }
    }

    // ── Extension extension loading ───────────────────────────────────────────
    fun install(onReady: (WebExtension) -> Unit) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/extensions/",
                "aman@filter"
            )
            .accept(
                { ext ->
                    if (ext == null) {
                        Log.e(TAG, "ensureBuiltIn returned null")
                        return@accept
                    }
                    extension = ext
                    Log.i(TAG, "WebExtension installed: ${ext.id}")
                    onReady(ext)
                },
                { throwable ->
                    Log.e(TAG, "WebExtension install failed", throwable)
                }
            )
    }

    // ── Per-session port wiring ───────────────────────────────────────────────
    /**
     * Called once per GeckoSession.  Registers a PortDelegate so that the
     * background script can open a native port and relay content-script messages.
     */
    @Suppress("UNUSED_PARAMETER")
    fun wireSession(session: GeckoSession, ext: WebExtension) {
        ext.setMessageDelegate(buildMessageDelegate(), "aman_native")
    }

    /** Convenience for callers that don't track install state. No-op if the
     *  extension hasn't finished installing yet — they will be wired by
     *  [install]'s onReady when it completes. */
    fun wireSessionIfReady(session: GeckoSession) {
        extension?.let { wireSession(session, it) }
    }

    // ── Message delegate ──────────────────────────────────────────────────────
    private fun buildMessageDelegate() =
        object : WebExtension.MessageDelegate {

            override fun onConnect(port: WebExtension.Port) {
                port.setDelegate(buildPortDelegate())
                activePorts.add(port)
                // Push current settings so background.js knows them immediately
                scope.launch {
                    port.postMessage(buildSettingsMessage())
                }
            }

            // Fallback for sendMessage() (without a port)
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender,
            ): GeckoResult<Any?>? {
                val obj = message as? JSONObject ?: return null
                if (obj.optString("type") != "classify_url") return null

                val result = GeckoResult<Any?>()
                scope.launch {
                    val response = handleClassifyUrl(obj)
                    result.complete(response)
                }
                return result
            }
        }

    private fun buildPortDelegate() = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val obj = message as? JSONObject ?: return
            when (obj.optString("type")) {
                "classify_url" -> {
                    scope.launch {
                        val response = handleClassifyUrl(obj)
                        port.postMessage(response)
                    }
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            Log.d(TAG, "Port disconnected")
            activePorts.remove(port)
        }
    }

    // ── Classification pipeline ───────────────────────────────────────────────
    private suspend fun handleClassifyUrl(msg: JSONObject): JSONObject {
        val id         = msg.optInt("id", -1)
        val url        = msg.optString("url", "")
        val pageOrigin = msg.optString("origin", "")

        if (url.isBlank() || id == -1) {
            return JSONObject().put("type", "blur_result").put("id", id).put("should_blur", false)
        }

        val settings = currentFilterSettings()
        val generationAtStart = cacheGeneration.get()

        // Cache hit — same URL was already classified during this session
        cachedResult(url)?.let { cached ->
            return blurResult(id, cached)
        }

        val key = ClassificationKey(
            url = url,
            checkNsfw = settings.checkNsfw,
            checkFace = settings.checkFace,
            checkSkin = settings.checkSkin,
            genderFilter = settings.genderFilter,
            threshold = settings.threshold,
        )
        val result = try {
            classifyWithInFlight(key) {
                when {
                    url.startsWith("data:image/") -> classifyDataUrl(
                        url, settings.checkNsfw, settings.checkFace, settings.checkSkin, settings.genderFilter, settings.threshold
                    )
                    url.startsWith("http://") || url.startsWith("https://") -> classifyRemoteUrl(
                        url, settings.checkNsfw, settings.checkFace, settings.checkSkin, settings.genderFilter, settings.threshold
                    )
                    else -> DetectionResult.SAFE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification error for $url", e)
            DetectionResult.SAFE
        }

        // Record stats
        if (result.shouldBlur) {
            val domain = try {
                Uri.parse(pageOrigin).host ?: pageOrigin
            } catch (_: Exception) { pageOrigin }
            AmanApplication.statsRepository.recordBlur(
                category  = result.primaryCategory().name,
                domain    = domain,
                elapsedMs = result.elapsedMs,
            )
        }

        // Persist decision so subsequent occurrences of this URL are consistent
        if (generationAtStart == cacheGeneration.get()) {
            cacheResult(url, result.shouldBlur)
        }

        return blurResult(id, result.shouldBlur)
    }

    private suspend fun currentFilterSettings(): FilterSettings {
        val (sensitivity, genderFilter) = combine(
            prefs.sensitivity.distinctUntilChanged(),
            prefs.genderFilter.distinctUntilChanged(),
        ) { sensitivity, genderFilter -> sensitivity to genderFilter }.first()

        return FilterSettings(
            checkNsfw = true,
            checkFace = genderFilter != PreferencesManager.GENDER_NO_PEOPLE,
            checkSkin = true,
            genderFilter = genderFilter,
            threshold = PreferencesManager.sensitivityToThreshold(sensitivity),
        )
    }

    private suspend fun classifyWithInFlight(
        key: ClassificationKey,
        block: suspend () -> DetectionResult,
    ): DetectionResult {
        val candidate = scope.async(start = CoroutineStart.LAZY) { block() }
        val existing = inFlightClassifications.putIfAbsent(key, candidate)
        val active = if (existing == null) {
            candidate.start()
            candidate
        } else {
            candidate.cancel()
            existing
        }

        return try {
            active.await()
        } finally {
            if (active.isCompleted) inFlightClassifications.remove(key, active)
        }
    }

    private suspend fun classifyRemoteUrl(
        url: String,
        checkNsfw: Boolean, checkFace: Boolean, checkSkin: Boolean,
        genderFilter: Int, threshold: Float,
    ): DetectionResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return DetectionResult.SAFE
            val body = response.body ?: return DetectionResult.SAFE
            val contentType = body.contentType()
            if (contentType != null && contentType.type != "image") return DetectionResult.SAFE
            if (contentType?.subtype?.contains("svg", ignoreCase = true) == true) return DetectionResult.SAFE
            val contentLength = body.contentLength()
            if (contentLength > MAX_IMAGE_BYTES) return DetectionResult.SAFE
            engine.classifyStream(
                stream        = body.byteStream(),
                checkNsfw     = checkNsfw,
                checkFace     = checkFace,
                checkSkin     = checkSkin,
                genderFilter  = genderFilter,
                nsfwThreshold = threshold,
            )
        }
    }

    private suspend fun classifyDataUrl(
        dataUrl: String,
        checkNsfw: Boolean, checkFace: Boolean, checkSkin: Boolean,
        genderFilter: Int, threshold: Float,
    ): DetectionResult {
        if (dataUrl.length > MAX_DATA_URL_CHARS) return DetectionResult.SAFE
        // data:image/jpeg;base64,<payload>
        val commaIdx = dataUrl.indexOf(',')
        if (commaIdx < 0) return DetectionResult.SAFE
        val bytes = android.util.Base64.decode(dataUrl.substring(commaIdx + 1), android.util.Base64.DEFAULT)
        return engine.classifyStream(
            stream        = bytes.inputStream(),
            checkNsfw     = checkNsfw,
            checkFace     = checkFace,
            checkSkin     = checkSkin,
            genderFilter  = genderFilter,
            nsfwThreshold = threshold,
        )
    }

    private fun blurResult(id: Int, shouldBlur: Boolean): JSONObject =
        JSONObject()
            .put("type", "blur_result")
            .put("id", id)
            .put("should_blur", shouldBlur)

    fun destroy() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    private data class ClassificationKey(
        val url: String,
        val checkNsfw: Boolean,
        val checkFace: Boolean,
        val checkSkin: Boolean,
        val genderFilter: Int,
        val threshold: Float,
    )

    private data class FilterSettings(
        val checkNsfw: Boolean,
        val checkFace: Boolean,
        val checkSkin: Boolean,
        val genderFilter: Int,
        val threshold: Float,
    )

    companion object {
        private const val TAG = "WebExtMgr"
        private const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
        private const val MAX_DATA_URL_CHARS = 1_500_000
    }
}
