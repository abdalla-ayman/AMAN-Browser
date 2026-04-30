package com.aman.browser.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.aman.browser.blocklist.AppCategoryBlocklist
import com.aman.browser.data.browser.BrowserDatabase
import com.aman.browser.data.browser.DownloadEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AmanDownloads"

/**
 * Owns the downloads table and the actual file IO. Public-facing API:
 *   • [intercept] — called from [com.aman.browser.browser.BrowserFragment]'s
 *     ContentDelegate.onExternalResponse. Decides what to do with the
 *     incoming stream (block / save / forward to OS).
 *   • [recent], [delete], [clear], [openFile] — UI-side helpers.
 */
class DownloadsRepository(private val context: Context) {

    private val dao = BrowserDatabase.get(context).downloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recent(limit: Int = 500): Flow<List<DownloadEntry>> = dao.recent(limit)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clear() = dao.clear()

    private val publicDownloadsDir: File by lazy {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).also {
            try { it.mkdirs() } catch (_: Exception) {}
        }
    }
    private val privateDownloadsDir: File by lazy {
        File(context.filesDir, "downloads").also { it.mkdirs() }
    }
    private val quarantineDir: File by lazy {
        File(context.cacheDir, "blocked-downloads").also { it.mkdirs() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun safeFilename(name: String?): String {
        val raw = name?.takeIf { it.isNotBlank() } ?: "download-${timestamp()}"
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)
    }

    /**
     * Inspect a [WebResponse] coming out of GeckoView and decide whether to
     * deliver it to the user or block it.
     *
     * Strategy:
     *   1. Cheap header check (MIME / extension / URL): if the URL is an
     *      app-store / VPN / browser landing page or the MIME is APK,
     *      record as BLOCKED and discard the stream.
     *   2. Otherwise stream to a private cache file.
     *   3. If the file is an APK, parse it; if classification trips,
     *      mark BLOCKED and quarantine.
     *   4. Otherwise move to public Downloads and record COMPLETE.
     */
    fun intercept(response: WebResponse) {
        val url = response.uri
        val filename = safeFilename(extractFilename(response))
        val mime = response.headers["Content-Type"]
            ?: response.headers["content-type"]

        val urlVerdict = AppCategoryBlocklist.classifyUrl(url)
        val headerVerdict = AppCategoryBlocklist.classifyDownloadHeader(filename, mime)
        val preflight = when {
            urlVerdict.blocked -> urlVerdict
            headerVerdict.blocked -> headerVerdict
            else -> AppCategoryBlocklist.BlockDecision.ALLOWED
        }

        scope.launch {
            val createdAt = System.currentTimeMillis()
            val rowId = dao.insert(
                DownloadEntry(
                    url = url,
                    filename = filename,
                    mime = mime,
                    sizeBytes = 0L,
                    status = "PENDING",
                    blockReason = null,
                    category = null,
                    localPath = null,
                    createdAt = createdAt,
                )
            )

            if (preflight.blocked) {
                Log.i(TAG, "Pre-flight block: ${preflight.reason}  url=$url")
                drainAndDiscard(response)
                dao.update(
                    DownloadEntry(
                        id = rowId,
                        url = url,
                        filename = filename,
                        mime = mime,
                        sizeBytes = 0L,
                        status = "BLOCKED",
                        blockReason = preflight.reason,
                        category = preflight.category.name,
                        localPath = null,
                        createdAt = createdAt,
                    )
                )
                return@launch
            }

            // Stream to a private cache file first so we can introspect APKs
            // before exposing them to the user.
            val tmp = File(privateDownloadsDir, "${rowId}-$filename")
            val savedSize = try {
                streamTo(response.body, tmp)
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for $url", e)
                tmp.delete()
                dao.update(
                    DownloadEntry(
                        id = rowId, url = url, filename = filename, mime = mime,
                        sizeBytes = 0L, status = "FAILED",
                        blockReason = e.message, category = null, localPath = null,
                        createdAt = createdAt,
                    )
                )
                return@launch
            }

            // Post-download APK introspection.
            val isApk = filename.lowercase().endsWith(".apk") ||
                mime?.contains("vnd.android.package-archive", ignoreCase = true) == true
            if (isApk) {
                val verdict = AppCategoryBlocklist.classifyApk(context, tmp)
                if (verdict.blocked) {
                    val q = File(quarantineDir, tmp.name)
                    try { tmp.renameTo(q) } catch (_: Exception) { tmp.delete() }
                    dao.update(
                        DownloadEntry(
                            id = rowId, url = url, filename = filename, mime = mime,
                            sizeBytes = savedSize, status = "BLOCKED",
                            blockReason = verdict.reason,
                            category = verdict.category.name,
                            localPath = null,
                            createdAt = createdAt,
                        )
                    )
                    Log.i(TAG, "Post-flight block: ${verdict.reason}")
                    // Best-effort: schedule the quarantined apk for deletion soon.
                    try { q.delete() } catch (_: Exception) {}
                    return@launch
                }
            }

            // Allowed — move to the public Downloads folder if we can.
            val finalFile = try {
                val target = File(publicDownloadsDir, filename)
                if (publicDownloadsDir.canWrite()) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                    target
                } else tmp
            } catch (_: Exception) { tmp }

            dao.update(
                DownloadEntry(
                    id = rowId, url = url, filename = filename, mime = mime,
                    sizeBytes = savedSize, status = "COMPLETE",
                    blockReason = null, category = null,
                    localPath = finalFile.absolutePath,
                    createdAt = createdAt,
                )
            )
        }
    }

    /** Open the saved file with an external viewer. No-op for blocked entries. */
    suspend fun openFile(entry: DownloadEntry) = withContext(Dispatchers.Main) {
        val path = entry.localPath ?: return@withContext
        val file = File(path)
        if (!file.exists()) return@withContext
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: Exception) {
            // FileProvider not configured — fall back to file:// (older devices)
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mime ?: "*/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun extractFilename(response: WebResponse): String? {
        val cd = response.headers["Content-Disposition"]
            ?: response.headers["content-disposition"]
        if (cd != null) {
            val m = Regex("""filename\*?=(?:UTF-8'')?\"?([^\";]+)\"?""", RegexOption.IGNORE_CASE).find(cd)
            m?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return try {
            val path = Uri.parse(response.uri).lastPathSegment.orEmpty()
            path.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun streamTo(input: InputStream?, dest: File): Long {
        if (input == null) {
            dest.createNewFile()
            return 0L
        }
        var total = 0L
        input.use { src ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                    // Safety cap: refuse to fill disk with a runaway response.
                    if (total > 2L * 1024 * 1024 * 1024) {
                        throw IllegalStateException("Download exceeded 2 GiB cap")
                    }
                }
            }
        }
        return total
    }

    private fun drainAndDiscard(response: WebResponse) {
        try {
            response.body?.use { input ->
                val buf = ByteArray(8 * 1024)
                while (input.read(buf) > 0) { /* drain */ }
            }
        } catch (_: Exception) {}
    }
}
