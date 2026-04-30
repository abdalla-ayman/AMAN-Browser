package com.aman.browser.ml

import android.graphics.RectF

/**
 * Single object detected by NudeNet 320n.
 *
 * @param classIndex 0..17 NudeNet class index (see [NudeNetClasses])
 * @param className  human-readable class name
 * @param score      0..1 confidence
 * @param box        bounding box in input-image normalised coordinates [0,1]
 */
data class Detection(
    val classIndex: Int,
    val className: String,
    val score: Float,
    val box: RectF,
)

/**
 * Result of classifying a single image with NudeNet.
 *
 * Legacy aggregate fields ([nsfwScore], [skinScore], etc.) are populated from
 * the per-class detection list so older call sites keep compiling.
 */
data class DetectionResult(
    val detections: List<Detection> = emptyList(),
    val nsfwScore:   Float = 0f,
    val faceScore:   Float = 0f,
    val skinScore:   Float = 0f,
    val femaleScore: Float = -1f,
    val maleScore:   Float = -1f,
    val shouldBlur:  Boolean = false,
    val elapsedMs:   Long = 0L,
    val faceCount:   Int = 0,
) {
    companion object {
        val SAFE  = DetectionResult(shouldBlur = false)
        val ERROR = DetectionResult(shouldBlur = false, elapsedMs = -1L)
    }
}

enum class BlurCategory { NSFW, FACE, SKIN, NONE }

fun DetectionResult.primaryCategory(): BlurCategory = when {
    !shouldBlur        -> BlurCategory.NONE
    nsfwScore  > 0.5f  -> BlurCategory.NSFW
    skinScore  > 0.5f  -> BlurCategory.SKIN
    faceScore  > 0.5f  -> BlurCategory.FACE
    else               -> BlurCategory.NSFW
}

/** NudeNet 320n class names, ordered by class index. */
object NudeNetClasses {
    val NAMES = arrayOf(
        "FEMALE_GENITALIA_COVERED", // 0
        "FACE_FEMALE",              // 1
        "BUTTOCKS_EXPOSED",         // 2
        "FEMALE_BREAST_EXPOSED",    // 3
        "FEMALE_GENITALIA_EXPOSED", // 4
        "MALE_BREAST_EXPOSED",      // 5
        "ANUS_EXPOSED",             // 6
        "FEET_EXPOSED",             // 7
        "BELLY_COVERED",            // 8
        "FEET_COVERED",             // 9
        "ARMPITS_COVERED",          // 10
        "ARMPITS_EXPOSED",          // 11
        "FACE_MALE",                // 12
        "BELLY_EXPOSED",            // 13
        "MALE_GENITALIA_EXPOSED",   // 14
        "ANUS_COVERED",             // 15
        "FEMALE_BREAST_COVERED",    // 16
        "BUTTOCKS_COVERED",         // 17
    )

    /** Sexual / explicit nudity classes — high confidence required. */
    val EXPLICIT = intArrayOf(2, 3, 4, 5, 6, 14)
    /** Soft skin-exposure classes — lower threshold. */
    val SKIN_EXPOSURE = intArrayOf(7, 11, 13)
    const val IDX_FACE_FEMALE = 1
    const val IDX_FACE_MALE   = 12
}

/** Legacy data class kept so other modules referencing it still compile. */
data class GenderResult(val isFemale: Boolean, val confidence: Float)
