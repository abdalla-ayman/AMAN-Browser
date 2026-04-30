package com.aman.browser.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aman.browser.data.PreferencesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the single NudeNet 320n detector.
 * Verifies the asset is present and the engine can run a forward pass.
 */
@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine: InferenceEngine = InferenceEngine.get(context)

    @After
    fun tearDown() { engine.destroy() }

    @Test
    fun nudeNetAssetIsPresent() {
        context.assets.open("models/nudenet_320n.tflite").use { stream ->
            val header = ByteArray(4)
            assertEquals("nudenet_320n.tflite is empty", header.size, stream.read(header))
            assertFalse(
                "nudenet_320n.tflite has an all-zero header",
                header.all { it == 0.toByte() },
            )
        }
    }

    @Test
    fun classifyBitmapRunsCpuInference() {
        engine.destroy()
        engine.initialize(useGpu = false, useNnapi = false)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }
        val result = engine.classifyBitmap(
            bitmap = bitmap,
            checkNsfw = true,
            checkFace = true,
            checkSkin = true,
            genderFilter = PreferencesManager.GENDER_EVERYONE,
            nsfwThreshold = 0.5f,
        )
        assertTrue("classification returned an error", result.elapsedMs >= 0L)
        assertTrue("nsfwScore in [0,1]", result.nsfwScore in 0f..1f)
        assertTrue("faceScore in [0,1]", result.faceScore in 0f..1f)
        assertTrue("skinScore in [0,1]", result.skinScore in 0f..1f)
    }
}
