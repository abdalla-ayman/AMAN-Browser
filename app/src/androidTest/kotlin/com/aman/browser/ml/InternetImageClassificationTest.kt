package com.aman.browser.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aman.browser.data.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20-image battery for the ML pipeline. Images are sourced from the public
 * internet (opencv samples, ageitgey/face_recognition, facenet-pytorch) and
 * bundled as androidTest assets so the test is deterministic and not
 * subject to upstream rate-limits or UA filtering.
 *
 * Exercises every gender-filter mode against the same set of bitmaps to
 * verify:
 *
 *   1. **Blur Everyone** triggers `shouldBlur=true` on every image where the
 *      face detector found at least one face.
 *   2. **Don't Blur (NO_PEOPLE)** never sets faceCount>0 and never relies on
 *      the people path for its decision.
 *   3. The decision is **deterministic** — classifying the same bitmap twice
 *      yields identical scores and the same shouldBlur flag.
 *   4. The face detector recognises **multiple people** in group photos
 *      (regression test for the previous single-best-box bug).
 *   5. Face detector overall agreement with curated labels >= 70%.
 */
@RunWith(AndroidJUnit4::class)
class InternetImageClassificationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val targetContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val engine: InferenceEngine = InferenceEngine.get(targetContext)

    @Before
    fun setUp() {
        engine.destroy()
        engine.initialize(useGpu = false, useNnapi = false)
    }

    @After
    fun tearDown() {
        engine.destroy()
    }

    private data class TestImage(
        val label: String,
        val asset: String,
        val expectsPeople: Boolean,
        val minFaces: Int = if (expectsPeople) 1 else 0,
    )

    /**
     * Curated assets. expectsPeople=true means the image contains at least
     * one human face. minFaces>=2 marks group photos used for the
     * multi-face regression check.
     */
    private val testImages: List<TestImage> = listOf(
        // ── Single portraits ────────────────────────────────────────────────
        TestImage("portrait_lena",   "test_images/portrait_lena.jpg",   true),
        TestImage("portrait_messi",  "test_images/portrait_messi.jpg",  true),
        TestImage("portrait_biden",  "test_images/portrait_biden.jpg",  true),
        TestImage("portrait_obama",  "test_images/portrait_obama.jpg",  true),
        TestImage("portrait_obama2", "test_images/portrait_obama2.jpg", true),
        TestImage("portrait_alex",   "test_images/portrait_alex.png",   true),

        // ── Group photos (multi-face) ───────────────────────────────────────
        TestImage("group_two_people", "test_images/group_two_people.jpg",
            expectsPeople = true, minFaces = 2),
        TestImage("group_multiface",  "test_images/group_multiface.jpg",
            expectsPeople = true, minFaces = 2),

        // ── No people: nature / objects / architecture ──────────────────────
        TestImage("no_people_fruits",       "test_images/no_people_fruits.jpg",       false),
        TestImage("no_people_aero",         "test_images/no_people_aero.jpg",         false),
        TestImage("no_people_building",     "test_images/no_people_building.jpg",     false),
        TestImage("no_people_apple",        "test_images/no_people_apple.jpg",        false),
        TestImage("no_people_orange",       "test_images/no_people_orange.jpg",       false),
        TestImage("no_people_butterfly",    "test_images/no_people_butterfly.jpg",    false),
        TestImage("no_people_starry_night", "test_images/no_people_starry_night.jpg", false),
        TestImage("no_people_notes",        "test_images/no_people_notes.png",        false),
        TestImage("no_people_fish",         "test_images/no_people_fish.jpg",         false),
        TestImage("no_people_home",         "test_images/no_people_home.jpg",         false),
        TestImage("no_people_graf1",        "test_images/no_people_graf1.png",        false),
        TestImage("no_people_graf3",        "test_images/no_people_graf3.png",        false),
    )

    @Test
    fun internetBatteryAcrossAllModes() = runBlocking {
        // ── 1. Load every image once from androidTest assets ────────────────
        val downloaded = testImages.mapNotNull { spec ->
            val bitmap = loadAsset(spec.asset) ?: run {
                Log.w(TAG, "[${spec.label}] asset load failed — skipping")
                return@mapNotNull null
            }
            spec to bitmap
        }
        assertTrue(
            "Expected all ${testImages.size} bundled assets to load, got ${downloaded.size}",
            downloaded.size == testImages.size,
        )
        Log.i(TAG, "Loaded ${downloaded.size} test images")

        // ── 2. Per-image results across all four gender modes ───────────────
        val nsfwThreshold = PreferencesManager.sensitivityToThreshold(2)

        val modes = listOf(
            "EVERYONE"     to PreferencesManager.GENDER_EVERYONE,
            "FEMALES_ONLY" to PreferencesManager.GENDER_FEMALES_ONLY,
            "MALES_ONLY"   to PreferencesManager.GENDER_MALES_ONLY,
            "NO_PEOPLE"    to PreferencesManager.GENDER_NO_PEOPLE,
        )

        val results = HashMap<Pair<String, String>, DetectionResult>()

        for ((spec, bitmap) in downloaded) {
            for ((modeName, modeValue) in modes) {
                val result = engine.classifyBitmap(
                    bitmap = bitmap,
                    checkNsfw = true,
                    checkFace = modeValue != PreferencesManager.GENDER_NO_PEOPLE,
                    checkSkin = true,
                    genderFilter = modeValue,
                    nsfwThreshold = nsfwThreshold,
                )
                results[spec.label to modeName] = result
                Log.i(
                    TAG,
                    "[${spec.label}] mode=$modeName " +
                        "shouldBlur=${result.shouldBlur} faceCount=${result.faceCount} " +
                        "nsfw=${"%.2f".format(result.nsfwScore)} " +
                        "skin=${"%.2f".format(result.skinScore)} " +
                        "face=${"%.2f".format(result.faceScore)} " +
                        "elapsed=${result.elapsedMs}ms",
                )
            }
        }

        // ── 3. Assertions ───────────────────────────────────────────────────

        // 3a) NO_PEOPLE must never report a face count.
        for ((spec, _) in downloaded) {
            val noPeople = results[spec.label to "NO_PEOPLE"]!!
            assertEquals(
                "NO_PEOPLE leaked face detection for ${spec.label}",
                0, noPeople.faceCount,
            )
        }

        // 3b) EVERYONE must blur every image where a face was found.
        for ((spec, _) in downloaded) {
            val everyone = results[spec.label to "EVERYONE"]!!
            if (everyone.faceCount > 0) {
                assertTrue(
                    "EVERYONE failed to blur ${spec.label} despite faceCount=${everyone.faceCount}",
                    everyone.shouldBlur,
                )
            }
        }

        // 3c) Determinism — re-classify each image and expect (near-)identical
        //     scores and the same shouldBlur.
        for ((spec, bitmap) in downloaded) {
            val first = results[spec.label to "EVERYONE"]!!
            val second = engine.classifyBitmap(
                bitmap = bitmap,
                checkNsfw = true,
                checkFace = true,
                checkSkin = true,
                genderFilter = PreferencesManager.GENDER_EVERYONE,
                nsfwThreshold = nsfwThreshold,
            )
            assertEquals(
                "Non-deterministic shouldBlur for ${spec.label}",
                first.shouldBlur, second.shouldBlur,
            )
            assertEquals(
                "Non-deterministic faceCount for ${spec.label}",
                first.faceCount, second.faceCount,
            )
            assertEquals(
                "Non-deterministic nsfwScore for ${spec.label}",
                first.nsfwScore, second.nsfwScore, 1e-4f,
            )
            assertEquals(
                "Non-deterministic faceScore for ${spec.label}",
                first.faceScore, second.faceScore, 1e-4f,
            )
            assertEquals(
                "Non-deterministic skinScore for ${spec.label}",
                first.skinScore, second.skinScore, 1e-4f,
            )
        }

        // 3d) Multi-face regression — at least one group photo must yield
        //     faceCount>=2 (the prior single-best-box bug always produced 1).
        val groupResults = downloaded
            .map { (spec, _) -> spec to results[spec.label to "EVERYONE"]!! }
            .filter { (spec, _) -> spec.minFaces >= 2 }
        if (groupResults.isNotEmpty()) {
            val multiFaceHits = groupResults.count { (_, r) -> r.faceCount >= 2 }
            assertTrue(
                "Multi-face regression: no group photo produced faceCount>=2. " +
                    "Group results: " + groupResults.joinToString { (s, r) ->
                        "${s.label}=${r.faceCount}"
                    },
                multiFaceHits >= 1,
            )
        }

        // 3e) Face-detector accuracy: agreement with expectsPeople >= 70%.
        val agreed = downloaded.count { (spec, _) ->
            val r = results[spec.label to "EVERYONE"]!!
            (r.faceCount > 0) == spec.expectsPeople
        }
        val accuracy = agreed.toFloat() / downloaded.size
        Log.i(TAG, "Face-detector agreement: $agreed/${downloaded.size} = $accuracy")
        assertTrue(
            "Face-detector agreement only $agreed/${downloaded.size} (=$accuracy) — expected >= 0.70",
            accuracy >= 0.70f,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load asset $path: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "InternetImageTest"
    }
}
