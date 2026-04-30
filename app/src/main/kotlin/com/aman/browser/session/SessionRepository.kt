package com.aman.browser.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "AmanSession"

/**
 * Persists the list of open tabs (URL + title + selected) across cold
 * starts so that the user finds their browsing exactly where they left
 * it. Storage is a single JSON file in [Context.filesDir]; cookies and
 * logged-in sessions are kept by GeckoView in its own profile dir
 * automatically — we just have to remember which URLs to reopen.
 */
class SessionRepository(context: Context) {

    private val file = File(context.filesDir, "browser-session.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class TabSnapshot(val url: String, val title: String)
    data class Snapshot(val tabs: List<TabSnapshot>, val selectedIndex: Int)

    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("tabs") ?: return null
            val tabs = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                TabSnapshot(
                    url = t.optString("url", ""),
                    title = t.optString("title", ""),
                )
            }.filter { it.url.isNotBlank() }
            if (tabs.isEmpty()) null
            else Snapshot(tabs, obj.optInt("selectedIndex", 0).coerceIn(0, tabs.lastIndex))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session", e)
            null
        }
    }

    /** Fire-and-forget snapshot persist. */
    fun saveAsync(snapshot: Snapshot) {
        scope.launch { saveSync(snapshot) }
    }

    private fun saveSync(snapshot: Snapshot) {
        try {
            val arr = JSONArray()
            snapshot.tabs.forEach { tab ->
                arr.put(JSONObject().put("url", tab.url).put("title", tab.title))
            }
            val obj = JSONObject()
                .put("tabs", arr)
                .put("selectedIndex", snapshot.selectedIndex)
            file.writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save session", e)
        }
    }

    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }
}
