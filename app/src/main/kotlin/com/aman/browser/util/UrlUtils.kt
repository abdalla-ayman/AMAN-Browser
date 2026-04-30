package com.aman.browser.util

import android.net.Uri
import java.util.Locale
import java.util.UUID

object UrlUtils {
    fun normalizeBrowserInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        return when {
            hasHttpScheme(trimmed) -> trimmed
            looksLikeWebAddress(trimmed) -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
    }

    fun normalizeWebsiteUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) return null

        val candidate = if (hasHttpScheme(trimmed)) trimmed else "https://$trimmed"
        return try {
            val uri = Uri.parse(candidate)
            val host = uri.host
            if (host.isNullOrBlank()) null else candidate
        } catch (_: Exception) {
            null
        }
    }

    fun customWebAppId(name: String): String {
        val slug = name.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "site" }
        return "custom_${slug}_${UUID.randomUUID().toString().take(8)}"
    }

    private fun hasHttpScheme(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun looksLikeWebAddress(value: String): Boolean =
        !value.contains(' ') && value.contains('.')
}