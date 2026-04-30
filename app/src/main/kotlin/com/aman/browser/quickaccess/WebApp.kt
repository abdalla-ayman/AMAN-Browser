package com.aman.browser.quickaccess

import com.aman.browser.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "Quick Access" entry – a website that the user has chosen to install as a
 * standalone, app-like experience.
 *
 *  • [id]              stable identifier (also used as GeckoSession contextId so
 *                      cookies / login state are partitioned per-app and persist
 *                      across launches)
 *  • [name]            label shown to the user
 *  • [url]             home URL the app opens to
 *  • [iconRes]         optional bundled vector drawable; 0 if a letter avatar
 *                      should be generated
 *  • [letter]          letter to render when [iconRes] == 0
 *  • [colorHex]        accent colour for the letter avatar / status bar
 *  • [builtIn]         true for the catalogue defaults, false for user added
 */
data class WebApp(
    val id: String,
    val name: String,
    val url: String,
    val iconRes: Int = 0,
    val letter: String = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
    val colorHex: String = "#1B5E20",
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("iconRes", iconRes)
        put("letter", letter)
        put("colorHex", colorHex)
        put("builtIn", builtIn)
    }

    companion object {
        fun fromJson(o: JSONObject) = WebApp(
            id       = o.getString("id"),
            name     = o.getString("name"),
            url      = o.getString("url"),
            iconRes  = o.optInt("iconRes", 0),
            letter   = o.optString("letter", "?"),
            colorHex = o.optString("colorHex", "#1B5E20"),
            builtIn  = o.optBoolean("builtIn", false),
        )

        fun listToJson(list: List<WebApp>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(s: String?): List<WebApp> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(s)
                List(arr.length()) { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * Default catalogue offered on first run. The user picks which to keep,
         * may remove any of them later, and may add custom entries.
         */
        val CATALOG: List<WebApp> = listOf(
            WebApp("youtube",   "YouTube",   "https://m.youtube.com",      R.drawable.ic_youtube,   colorHex = "#FF0000", builtIn = true),
            WebApp("instagram", "Instagram", "https://www.instagram.com",  R.drawable.ic_instagram, colorHex = "#E1306C", builtIn = true),
            WebApp("facebook",  "Facebook",  "https://m.facebook.com",     R.drawable.ic_facebook,  colorHex = "#1877F2", builtIn = true),
            WebApp("twitter",   "X",         "https://x.com",              R.drawable.ic_twitter,   colorHex = "#000000", builtIn = true),
            WebApp("linkedin",  "LinkedIn",  "https://www.linkedin.com",   R.drawable.ic_linkedin,  colorHex = "#0A66C2", builtIn = true),
            WebApp("tiktok",    "TikTok",    "https://www.tiktok.com",     R.drawable.ic_tiktok,    colorHex = "#010101", builtIn = true),
            WebApp("reddit",    "Reddit",    "https://www.reddit.com",     R.drawable.ic_reddit,    colorHex = "#FF4500", builtIn = true),
        )
    }
}
