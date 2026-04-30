package com.aman.browser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aman_settings")

/**
 * All user-configurable settings, persisted via DataStore Preferences.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        // Master toggle
        val KEY_ENABLED          = booleanPreferencesKey("enabled")

        // Detection toggles
        val KEY_DETECT_NSFW      = booleanPreferencesKey("detect_nsfw")
        val KEY_DETECT_FACE      = booleanPreferencesKey("detect_face")
        val KEY_DETECT_SKIN      = booleanPreferencesKey("detect_skin")

        // Blur intensity: 0=Low  1=Medium  2=High
        val KEY_BLUR_INTENSITY   = intPreferencesKey("blur_intensity")

        // Sensitivity: 0=Strict  1=Balanced  2=Relaxed
        val KEY_SENSITIVITY      = intPreferencesKey("sensitivity")

        // Tap to unblur
        val KEY_TAP_TO_UNBLUR    = booleanPreferencesKey("tap_to_unblur")

        // Blur while images are being classified
        val KEY_BLUR_ON_LOAD     = booleanPreferencesKey("blur_on_load")

        // Hardware acceleration
        val KEY_USE_GPU          = booleanPreferencesKey("use_gpu")
        val KEY_USE_NNAPI        = booleanPreferencesKey("use_nnapi")

        // Gender filter: 0=Everyone  1=Females  2=Males  3=No people blur
        val KEY_GENDER_FILTER    = intPreferencesKey("gender_filter")

        const val GENDER_EVERYONE = 0
        const val GENDER_FEMALES_ONLY = 1
        const val GENDER_MALES_ONLY = 2
        const val GENDER_NO_PEOPLE = 3

        // NSFW sensitivity threshold (derived from KEY_SENSITIVITY)
        fun sensitivityToThreshold(sensitivity: Int): Float = when (sensitivity) {
            0    -> 0.85f  // Strict, but still high confidence
            1    -> 0.90f  // Balanced
            else -> 0.95f  // Relaxed
        }

        fun blurIntensityToPx(intensity: Int): Int = when (intensity) {
            0    -> 10    // Low
            1    -> 20    // Medium
            else -> 40    // High
        }
    }

    // ── Readers ───────────────────────────────────────────────────────────────
    // distinctUntilChanged stops downstream collectors from re-running on
    // every DataStore edit (DataStore re-emits even when the value did not
    // actually change), which previously caused the WebExtension to flush its
    // result cache + broadcast settings repeatedly on every settings touch.
    val isEnabled:       Flow<Boolean> = context.dataStore.data.map { true }.distinctUntilChanged()
    val detectNsfw:      Flow<Boolean> = context.dataStore.data.map { true }.distinctUntilChanged()
    val detectFace:      Flow<Boolean> = context.dataStore.data.map { it[KEY_DETECT_FACE]   ?: true  }.distinctUntilChanged()
    val detectSkin:      Flow<Boolean> = context.dataStore.data.map { it[KEY_DETECT_SKIN]   ?: true  }.distinctUntilChanged()
    val blurIntensity:   Flow<Int>     = context.dataStore.data.map { it[KEY_BLUR_INTENSITY] ?: 1    }.distinctUntilChanged()
    val sensitivity:     Flow<Int>     = context.dataStore.data.map { it[KEY_SENSITIVITY]   ?: 1    }.distinctUntilChanged()
    val tapToUnblur:     Flow<Boolean> = context.dataStore.data.map { it[KEY_TAP_TO_UNBLUR] ?: true  }.distinctUntilChanged()
    val blurOnLoad:      Flow<Boolean> = context.dataStore.data.map { it[KEY_BLUR_ON_LOAD]  ?: true  }.distinctUntilChanged()
    val useGpu:          Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_GPU]        ?: true  }.distinctUntilChanged()
    val useNnapi:        Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_NNAPI]      ?: true  }.distinctUntilChanged()
    val genderFilter:    Flow<Int>     = context.dataStore.data.map { it[KEY_GENDER_FILTER]  ?: 0    }.distinctUntilChanged()

    // ── Writers ───────────────────────────────────────────────────────────────
    @Suppress("UNUSED_PARAMETER")
    suspend fun setEnabled(value: Boolean)       = context.dataStore.edit { it[KEY_ENABLED]       = true }
    @Suppress("UNUSED_PARAMETER")
    suspend fun setDetectNsfw(value: Boolean)    = context.dataStore.edit { it[KEY_DETECT_NSFW]   = true }
    suspend fun setDetectFace(value: Boolean)    = context.dataStore.edit { it[KEY_DETECT_FACE]   = value }
    suspend fun setDetectSkin(value: Boolean)    = context.dataStore.edit { it[KEY_DETECT_SKIN]   = value }
    suspend fun setBlurIntensity(value: Int)     = context.dataStore.edit { it[KEY_BLUR_INTENSITY] = value }
    suspend fun setSensitivity(value: Int)       = context.dataStore.edit { it[KEY_SENSITIVITY]   = value }
    suspend fun setTapToUnblur(value: Boolean)   = context.dataStore.edit { it[KEY_TAP_TO_UNBLUR] = value }
    suspend fun setBlurOnLoad(value: Boolean)    = context.dataStore.edit { it[KEY_BLUR_ON_LOAD]  = value }
    suspend fun setUseGpu(value: Boolean)        = context.dataStore.edit { it[KEY_USE_GPU]        = value }
    suspend fun setUseNnapi(value: Boolean)      = context.dataStore.edit { it[KEY_USE_NNAPI]      = value }
    suspend fun setGenderFilter(value: Int)      = context.dataStore.edit { it[KEY_GENDER_FILTER]  = value }
}
