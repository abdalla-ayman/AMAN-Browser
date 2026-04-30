package com.aman.browser.quickaccess

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.quickAccessStore: DataStore<Preferences> by preferencesDataStore(name = "aman_quick_access")

/**
 * Persists the user's Quick Access list and onboarding state.
 *
 * The list is stored as a JSON array of [WebApp]s under [KEY_LIST]. We keep it
 * as a single string instead of a Set<String> so we can guarantee insertion
 * order (the user can reorder later).
 */
class QuickAccessRepository(private val context: Context) {

    companion object {
        private val KEY_LIST           = stringPreferencesKey("list_json")
        private val KEY_ONBOARDED      = booleanPreferencesKey("onboarded")
        private val KEY_LAST_URLS      = stringPreferencesKey("last_urls_json")
    }

    /** Live list of installed Quick Access apps. */
    val apps: Flow<List<WebApp>> = context.quickAccessStore.data.map { prefs ->
        WebApp.listFromJson(prefs[KEY_LIST])
    }

    /** True once the user has completed (or skipped) the first-run picker. */
    val isOnboarded: Flow<Boolean> = context.quickAccessStore.data.map { it[KEY_ONBOARDED] ?: false }

    suspend fun getApps(): List<WebApp> = apps.first()

    suspend fun setApps(list: List<WebApp>) {
        context.quickAccessStore.edit { it[KEY_LIST] = WebApp.listToJson(list) }
    }

    suspend fun add(app: WebApp) {
        val current = getApps().toMutableList()
        if (current.none { it.id == app.id }) {
            current += app
            setApps(current)
        }
    }

    suspend fun remove(id: String) {
        context.quickAccessStore.edit { prefs ->
            prefs[KEY_LIST] = WebApp.listToJson(WebApp.listFromJson(prefs[KEY_LIST]).filterNot { it.id == id })
            val lastUrls = lastUrlsFromJson(prefs[KEY_LAST_URLS])
            lastUrls.remove(id)
            prefs[KEY_LAST_URLS] = lastUrlsToJson(lastUrls)
        }
    }

    suspend fun setOnboarded(value: Boolean = true) {
        context.quickAccessStore.edit { it[KEY_ONBOARDED] = value }
    }

    suspend fun findById(id: String): WebApp? = getApps().firstOrNull { it.id == id }

    suspend fun lastUrlFor(id: String): String? = context.quickAccessStore.data
        .map { prefs -> lastUrlsFromJson(prefs[KEY_LAST_URLS])[id] }
        .first()

    suspend fun saveLastUrl(id: String, url: String) {
        context.quickAccessStore.edit { prefs ->
            val lastUrls = lastUrlsFromJson(prefs[KEY_LAST_URLS])
            lastUrls[id] = url
            prefs[KEY_LAST_URLS] = lastUrlsToJson(lastUrls)
        }
    }

    private fun lastUrlsFromJson(value: String?): MutableMap<String, String> {
        if (value.isNullOrBlank()) return mutableMapOf()
        return try {
            val obj = JSONObject(value)
            obj.keys().asSequence().associateWith { key -> obj.optString(key) }.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun lastUrlsToJson(urls: Map<String, String>): String {
        val obj = JSONObject()
        urls.forEach { (id, url) -> obj.put(id, url) }
        return obj.toString()
    }
}
