package com.aman.browser.history

import com.aman.browser.data.browser.BrowserDatabase
import com.aman.browser.data.browser.HistoryEntry
import kotlinx.coroutines.flow.Flow

class HistoryRepository(context: android.content.Context) {

    private val dao = BrowserDatabase.get(context).historyDao()

    private fun hostOf(url: String): String =
        try { android.net.Uri.parse(url).host?.removePrefix("www.").orEmpty() } catch (_: Exception) { "" }

    /**
     * Record a visit. To avoid duplicating entries for SPA route changes /
     * fragment-only updates, if the same URL was visited within the last 30s
     * we just update the title instead of inserting.
     */
    suspend fun recordVisit(url: String, title: String) {
        if (url.isBlank()) return
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return
        val now = System.currentTimeMillis()
        val last = dao.lastVisitedAt(url) ?: 0L
        if (now - last < 30_000L && last > 0L) {
            if (title.isNotBlank()) dao.updateLatestTitle(url, title)
            return
        }
        dao.insert(
            HistoryEntry(
                url = url,
                title = title.ifBlank { url },
                visitedAt = now,
                host = hostOf(url),
            )
        )
    }

    suspend fun updateTitle(url: String, title: String) {
        if (url.isBlank() || title.isBlank()) return
        dao.updateLatestTitle(url, title)
    }

    fun recent(limit: Int = 500): Flow<List<HistoryEntry>> = dao.recent(limit)
    fun search(query: String): Flow<List<HistoryEntry>> = dao.search(query)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clear() = dao.clear()

    /** Drop entries older than 90 days. Called once per cold start. */
    suspend fun pruneOld() {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        dao.pruneOlderThan(cutoff)
    }
}
