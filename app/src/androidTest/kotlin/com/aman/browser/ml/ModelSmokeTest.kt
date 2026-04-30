package com.aman.browser.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aman.browser.data.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine: InferenceEngine = InferenceEngine.get(context)

    @After
    fun tearDown() {
        engine.destroy()
    }

    @Test
    fun requiredModelAssetsArePresent() {
        val requiredAssets = listOf(
            "models/nsfw_mobilenetv3.tflite",
            "models/face_detection_short.tflite",
            "models/skin_classifier.tflite",
            "models/model_gender_q.tflite",
        )

        requiredAssets.forEach { assetPath ->
            context.assets.open(assetPath).use { stream ->
                val header = ByteArray(4)
                assertEquals("$assetPath is empty", header.size, stream.read(header))
                assertFalse("$assetPath has an all-zero header", header.all { it == 0.toByte() })
            }
        }
    }

    @Test
    fun inferenceEngineLoadsNativeLibraryAndModels() {
        assertTrue(InferenceEngine.nativeVersion().startsWith("aman-inference/"))
        assertEquals(224, engine.getModelInputSize())

        engine.destroy()
        engine.initialize(useGpu = false, useNnapi = false)

        assertEngineReady()
        assertModelShape("nsfwInterpreter", intArrayOf(1, 224, 224, 3), intArrayOf(1, 5))
        assertModelShape("skinInterpreter", intArrayOf(1, 224, 224, 3), intArrayOf(1, 2))
        assertModelShape("genderInterpreter", intArrayOf(1, 128, 128, 3), intArrayOf(1, 2))
        assertFaceModelLoaded()
    }

    @Test
    fun classifyBitmapRunsCpuInference() = runBlocking {
        engine.destroy()
        engine.initialize(useGpu = false, useNnapi = false)
        assertEngineReady()

        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
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
        assertProbability("nsfwScore", result.nsfwScore)
        assertProbability("faceScore", result.faceScore)
        assertProbability("skinScore", result.skinScore)

        val faceBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(160, 128, 112))
        }
        val genderResult = engine.runGenderModel(faceBitmap)
        assertProbability("gender confidence", genderResult.confidence)
    }

    private fun assertModelShape(fieldName: String, inputShape: IntArray, outputShape: IntArray) {
        val interpreter = interpreter(fieldName)
        assertArrayEquals("$fieldName input shape", inputShape, interpreter.getInputTensor(0).shape())
        assertArrayEquals("$fieldName output shape", outputShape, interpreter.getOutputTensor(0).shape())
    }

    private fun assertFaceModelLoaded() {
        val interpreter = interpreter("faceInterpreter")
        assertArrayEquals(
            "faceInterpreter input shape",
            intArrayOf(1, 128, 128, 3),
            interpreter.getInputTensor(0).shape(),
        )
        assertTrue("faceInterpreter should expose BlazeFace output tensors", interpreter.outputTensorCount >= 2)
    }

    private fun assertEngineReady() {
        val isReady = privateField("isReady") as AtomicBoolean
        assertTrue("InferenceEngine did not become ready", isReady.get())
    }

    private fun interpreter(fieldName: String): Interpreter {
        val value = privateField(fieldName)
        assertNotNull("$fieldName was not loaded", value)
        return value as Interpreter
    }

    private fun privateField(fieldName: String): Any? {
        val field = InferenceEngine::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(engine)
    }

    private fun assertProbability(label: String, value: Float) {
        assertTrue("$label was $value", value in 0f..1f)
    }
}