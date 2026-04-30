package com.aman.browser.ml

/** Result of classifying a cropped face for gender filtering. */
data class GenderResult(
    val isFemale: Boolean,
    val confidence: Float,
)

/**
 * Result of classifying a single image.
 *
 * @param nsfwScore     0–1 probability of NSFW content
 * @param faceScore     0–1 probability of a face being present
 * @param skinScore     0–1 probability of excessive skin exposure
 * @param femaleScore   0–1 probability that detected face is female (−1 = no face)
 * @param maleScore     0–1 probability that detected face is male   (−1 = no face)
 * @param shouldBlur    final decision combining all scores + user settings
 * @param elapsedMs     total end-to-end latency (preprocessing + inference)
 */
data class DetectionResult(
    val nsfwScore:   Float = 0f,
    val faceScore:   Float = 0f,
    val skinScore:   Float = 0f,
    val femaleScore: Float = -1f,
    val maleScore:   Float = -1f,
    val shouldBlur:  Boolean = false,
    val elapsedMs:   Long = 0L,
    /** Total number of faces detected above [score] threshold. */
    val faceCount:   Int = 0,
) {
    companion object {
        val SAFE = DetectionResult(shouldBlur = false)
        val ERROR = DetectionResult(shouldBlur = false, elapsedMs = -1L)
    }
}

/** Categories that triggered the blur decision, used for Stats. */
enum class BlurCategory { NSFW, FACE, SKIN, NONE }

fun DetectionResult.primaryCategory(): BlurCategory = when {
    !shouldBlur        -> BlurCategory.NONE
    nsfwScore  > 0.5f  -> BlurCategory.NSFW
    skinScore  > 0.5f  -> BlurCategory.SKIN
    faceScore  > 0.5f  -> BlurCategory.FACE
    else               -> BlurCategory.NSFW
}
