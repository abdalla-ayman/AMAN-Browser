package com.aman.browser.blocklist

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Curated lists + classifier used to decide whether a navigation/download
 * is for an "app store", a "VPN" app, or an alternative browser, all of
 * which Aman blocks so that the user cannot side-step the safe-browsing
 * filter by installing another browser/VPN through Aman itself.
 *
 * Three layers:
 *   1. URL/host → matches well-known download/landing pages.
 *   2. MIME / extension → catches anything that looks like an installable
 *      package regardless of where it was hosted.
 *   3. APK introspection → after a download finishes (in app-private
 *      cache), parse it with [PackageManager.getPackageArchiveInfo] and
 *      classify by package-name and declared permissions
 *      ([android.permission.BIND_VPN_SERVICE] is the canonical VPN
 *      signal, browsers declare a CATEGORY_APP_BROWSER intent filter).
 *
 * Aman cannot prevent downloads in *other* installed apps — that
 * requires Device Owner/MDM. But it can fully prevent them inside
 * itself.
 */
object AppCategoryBlocklist {

    enum class Category { APP_STORE, VPN, BROWSER, INSTALLABLE_PACKAGE, NONE }

    data class BlockDecision(
        val blocked: Boolean,
        val category: Category,
        val reason: String,
    ) {
        companion object {
            val ALLOWED = BlockDecision(false, Category.NONE, "")
        }
    }

    // ── Hosts ───────────────────────────────────────────────────────────────
    // Matched as suffix on the URL host (case-insensitive). E.g. "play.google.com"
    // matches both "play.google.com" and "m.play.google.com".

    private val APP_STORE_HOSTS = setOf(
        "play.google.com",
        "apkpure.com", "m.apkpure.com", "apkpure.net",
        "apkmirror.com", "www.apkmirror.com",
        "aptoide.com", "en.aptoide.com",
        "apkmonk.com",
        "uptodown.com", "en.uptodown.com",
        "apk.support",
        "f-droid.org",
        "galaxystore.samsung.com", "galaxy.store", "samsungapps.com",
        "appgallery.huawei.com", "appgallery.cloud.huawei.com",
        "amazon.com/appstore", "amzn.to",
        "getjar.com",
        "mobogenie.com",
        "9apps.com",
        "softonic.com",
        "androeed.ru",
        "mob.org",
        "rustore.ru",
    )

    private val VPN_HOSTS = setOf(
        "nordvpn.com", "expressvpn.com", "protonvpn.com", "surfshark.com",
        "windscribe.com", "mullvad.net", "tunnelbear.com", "hide.me",
        "cyberghostvpn.com", "ipvanish.com", "purevpn.com", "atlasvpn.com",
        "privateinternetaccess.com", "privatevpn.com", "fastestvpn.com",
        "hotspotshield.com", "psiphon.ca", "psiphon3.com",
        "1.1.1.1", "one.one.one.one", "cloudflareclient.com",
        "wireguard.com", "openvpn.net",
        "torproject.org", "torproject.com",
        "v2ray.com", "v2fly.org", "shadowsocks.org",
        "ultrasurf.us", "freegate.com",
        "betternet.co", "speedify.com",
        "thunderbird.net", // Mullvad/Mozilla VPN landing
        "vpn.mozilla.org", "mozilla.org/vpn",
        "browsec.com", "zenmate.com", "vpnbook.com",
        "freevpn.org", "freevpnplanet.com",
    )

    private val BROWSER_HOSTS = setOf(
        "mozilla.org/firefox", "mozilla.org/en-us/firefox",
        "google.com/chrome", "chrome.google.com",
        "brave.com",
        "opera.com", "opera-mini.net",
        "microsoft.com/edge", "microsoft.com/en-us/edge",
        "duckduckgo.com/app",
        "vivaldi.com",
        "torproject.org/download",
        "kiwibrowser.com",
        "puffin.com", "puffinbrowser.com",
        "ucweb.com",
        "avast.com/secure-browser",
        "yandex.com/browser", "browser.yandex.com",
        "maxthon.com",
        "samsung.com/global/galaxy/apps/samsung-internet",
        "alohabrowser.com",
        "bromite.org",
    )

    // ── Package names ──────────────────────────────────────────────────────
    private val APP_STORE_PACKAGES = setOf(
        "com.android.vending",          // Play Store
        "com.aurora.store",
        "cm.aptoide.pt",
        "com.uptodown",
        "com.apkpure.aegon",
        "com.apkmirror.helper",
        "org.fdroid.fdroid",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.amazon.venezia",
        "com.xiaomi.mipicks",
        "ru.vk.store",
    )

    private val VPN_PACKAGES = setOf(
        "com.nordvpn.android",
        "com.expressvpn.vpn",
        "ch.protonvpn.android",
        "com.surfshark.vpnclient.android",
        "com.windscribe.vpn",
        "net.mullvad.mullvadvpn",
        "com.tunnelbear.android",
        "com.hideman.app",
        "de.mobileconcepts.cyberghost",
        "com.ixolit.ipvanish",
        "com.gaditek.purevpnpro",
        "com.atlasvpn.free",
        "com.privateinternetaccess.android",
        "com.fastestvpn.app",
        "hotspotshield.android.vpn",
        "ca.psiphon.psiphonpro",
        "com.psiphon3",
        "com.cloudflare.onedotonedotonedotone",
        "com.wireguard.android",
        "net.openvpn.openvpn", "net.openvpn.connect",
        "org.torproject.torbrowser",
        "org.torproject.android",
        "com.v2ray.ang",
        "com.github.shadowsocks",
        "com.bitmask.android",
        "free.vpn.unblock.proxy.turbovpn",
        "com.ufovpn.free.unblock.proxy.vpn",
        "com.freevpn.unblock.proxy",
        "co.infinitysoft.vpnetic",
        "com.betternet",
        "com.connectify.speedify",
        "com.browsec.vpn",
        "com.zenmate.android",
        "org.mozilla.firefox.vpn", "org.mozilla.vpn",
    )

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "org.mozilla.focus", "org.mozilla.klar",
        "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
        "com.microsoft.emmx", "com.microsoft.emmx.canary",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "org.torproject.torbrowser", "org.torproject.torbrowser_alpha",
        "com.kiwibrowser.browser",
        "com.cloudmosa.puffinFree", "com.cloudmosa.puffin",
        "com.UCMobile.intl", "com.uc.browser.en",
        "com.avast.android.secure.browser",
        "com.yandex.browser", "com.yandex.browser.alpha",
        "com.mx.browser",
        "com.sec.android.app.sbrowser",
        "com.alohamobile.browser",
        "org.bromite.bromite",
        "com.ecosia.android",
        "acr.browser.lightning", "acr.browser.barebones",
    )

    // ── MIME / extension detection ──────────────────────────────────────────
    private val INSTALLABLE_MIME = setOf(
        "application/vnd.android.package-archive",
        "application/x-authorware-bin",
    )
    private val INSTALLABLE_EXT = setOf("apk", "apks", "xapk", "apkm", "aab", "obb")

    // ──────────────────────────────────────────────────────────────────────

    /** Lower-case a host and strip `www.` so suffix comparisons are stable. */
    private fun normalisedHost(url: String): String {
        return try {
            val u = android.net.Uri.parse(url)
            (u.host ?: u.authority ?: "").lowercase().removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    private fun matchesAny(url: String, host: String, hosts: Set<String>): Boolean {
        if (host.isEmpty() && url.isEmpty()) return false
        // The "host" set actually contains "host" or "host/path-prefix" entries
        // (e.g. amazon.com/appstore). Match either against the host or the
        // host+path portion of the URL.
        val combined = if (host.isNotEmpty()) {
            try {
                val u = android.net.Uri.parse(url)
                host + (u.path ?: "")
            } catch (_: Exception) { host }
        } else ""
        return hosts.any { needle ->
            host == needle ||
                host.endsWith(".$needle") ||
                combined.startsWith(needle, ignoreCase = true) ||
                combined.contains("/$needle", ignoreCase = true)
        }
    }

    /** Should we block this URL pre-flight? */
    fun classifyUrl(url: String): BlockDecision {
        if (url.isBlank()) return BlockDecision.ALLOWED
        val host = normalisedHost(url)
        if (matchesAny(url, host, APP_STORE_HOSTS)) {
            return BlockDecision(true, Category.APP_STORE, "Blocked app-store domain: $host")
        }
        if (matchesAny(url, host, VPN_HOSTS)) {
            return BlockDecision(true, Category.VPN, "Blocked VPN domain: $host")
        }
        if (matchesAny(url, host, BROWSER_HOSTS)) {
            return BlockDecision(true, Category.BROWSER, "Blocked alternative-browser domain: $host")
        }
        // Heuristic: if URL ends in .apk/.xapk/etc. block regardless of source.
        val lower = url.lowercase()
        INSTALLABLE_EXT.forEach { ext ->
            if (lower.endsWith(".$ext") || lower.contains(".$ext?")) {
                return BlockDecision(true, Category.INSTALLABLE_PACKAGE, "Blocked installable package URL (.$ext)")
            }
        }
        return BlockDecision.ALLOWED
    }

    /** Should we block this download stream by MIME / filename? */
    fun classifyDownloadHeader(filename: String?, mime: String?): BlockDecision {
        val mimeLc = mime?.lowercase().orEmpty()
        if (mimeLc in INSTALLABLE_MIME) {
            return BlockDecision(true, Category.INSTALLABLE_PACKAGE, "Installable package MIME: $mimeLc")
        }
        val ext = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in INSTALLABLE_EXT) {
            return BlockDecision(true, Category.INSTALLABLE_PACKAGE, "Installable package extension: .$ext")
        }
        return BlockDecision.ALLOWED
    }

    /**
     * Final, authoritative classification once we have the actual APK on
     * disk. Reads package name + permissions out of the archive without
     * installing it. Returns a [BlockDecision] describing what to do.
     */
    fun classifyApk(context: Context, apk: File): BlockDecision {
        if (!apk.exists() || apk.length() == 0L) return BlockDecision.ALLOWED
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        val info: PackageInfo? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, flags)
            }
        } catch (_: Exception) {
            null
        }
        val pkg = info?.packageName.orEmpty()
        if (pkg.isEmpty()) {
            return BlockDecision(true, Category.INSTALLABLE_PACKAGE, "Unparseable APK")
        }
        if (pkg in APP_STORE_PACKAGES) {
            return BlockDecision(true, Category.APP_STORE, "App-store package: $pkg")
        }
        if (pkg in VPN_PACKAGES) {
            return BlockDecision(true, Category.VPN, "VPN package: $pkg")
        }
        if (pkg in BROWSER_PACKAGES) {
            return BlockDecision(true, Category.BROWSER, "Alternative-browser package: $pkg")
        }
        // Heuristic: any APK requesting BIND_VPN_SERVICE is a VPN, full stop.
        val perms = info?.requestedPermissions.orEmpty()
        if ("android.permission.BIND_VPN_SERVICE" in perms) {
            return BlockDecision(true, Category.VPN, "APK requests BIND_VPN_SERVICE ($pkg)")
        }
        // Heuristic: any APK that declares a VpnService is a VPN.
        val services = info?.services.orEmpty()
        if (services.any { svc -> svc.permission == "android.permission.BIND_VPN_SERVICE" }) {
            return BlockDecision(true, Category.VPN, "APK declares VpnService ($pkg)")
        }
        // Generic: never let *any* installable archive escape Aman.
        return BlockDecision(true, Category.INSTALLABLE_PACKAGE, "Installable APK ($pkg)")
    }
}
