/**
 * background.js — Aman Content Filter
 *
 * Acts as a relay between content scripts (per-tab) and the native Android
 * layer (Kotlin/InferenceEngine) via a persistent native messaging port.
 *
 * Message flow:
 *   content.js  →  background (port)  →  native (Kotlin)
 *   Kotlin result → background → content.js port
 */

"use strict";

// ── Adult / explicit site blocklist ──────────────────────────────────────────
const BLOCKED_DOMAINS = new Set([
  // Major tube sites
  "pornhub.com",
  "xvideos.com",
  "xnxx.com",
  "youporn.com",
  "redtube.com",
  "tube8.com",
  "xhamster.com",
  "xhamster2.com",
  "spankbang.com",
  "eporner.com",
  "tnaflix.com",
  "beeg.com",
  "brazzers.com",
  "naughtyamerica.com",
  "bangbros.com",
  "mofos.com",
  "realitykings.com",
  "digitalplayground.com",
  "penthouse.com",
  "playvids.com",
  "txxx.com",
  "vporn.com",
  "porntrex.com",
  "porn300.com",
  "4tube.com",
  "xtube.com",
  "drtuber.com",
  "hqporner.com",
  "hotmovs.com",
  "analdin.com",
  "sunporno.com",
  "jizzbunker.com",
  "tubxporn.com",
  "anysex.com",
  "porndig.com",
  "porndoe.com",
  "sexu.com",
  "pornhd.com",
  "fapster.xxx",
  "clips4sale.com",
  "nudevista.com",
  "slutload.com",
  "wetplace.com",
  "xbabe.com",
  "fuqer.com",
  "sexvid.xxx",
  "camwhores.tv",
  "pornoxo.com",
  "txxxcom.com",
  "momvids.com",
  "pornerbros.com",
  "ashemaletube.com",
  "shemale.xxx",
  "trannytube.tv",
  "freeones.com",
  "nudestat.com",
  "sexhd.pics",
  "porn.com",
  "sex.com",
  "xxx.com",
  "xxxbunker.com",
  // Cam / live sites
  "chaturbate.com",
  "livejasmin.com",
  "stripchat.com",
  "myfreecams.com",
  "bongacams.com",
  "camsoda.com",
  "cam4.com",
  "flirt4free.com",
  "imlive.com",
  "streamate.com",
  "jasmin.com",
  "jerkmate.com",
  "cams.com",
  "amateur.tv",
  "camonster.com",
  "xcams.com",
  "slurpix.com",
  "dirtyroulette.com",
  // Hookup / adult dating
  "adultfriendfinder.com",
  "ashleymadison.com",
  "fling.com",
  "xmatch.com",
  "benaughty.com",
  "together2night.com",
  "instabang.com",
  "onlyfans.com",
  "fansly.com",
  "manyvids.com",
  "modelhub.com",
  "iwantclips.com",
  // Image boards / misc
  "thehun.com",
  "motherless.com",
  "thisvid.com",
  "88r.com",
  "femjoy.com",
  "metart.com",
  "sexart.com",
  "hegre.com",
  "x-art.com",
  "zishy.com",
  "domai.com",
  "eroprofile.com",
  "imagefap.com",
  "redgifs.com",
  "scrolller.com",
  "nhentai.net",
  "hentai2read.com",
  "hentaihaven.xxx",
  "hentaifox.com",
  "luscious.net",
  "rule34.xxx",
  "rule34.paheal.net",
  "gelbooru.com",
  "danbooru.donmai.us",
  "sankakucomplex.com",
  // Escort
  "eros.com",
  "slixa.com",
  "skipthegames.com",
  "tryst.link",
  "preferred411.com",
]);

const PROXY_DOMAINS = new Set([
  "proxysite.com",
  "croxyproxy.com",
  "kproxy.com",
  "hide.me",
  "hidester.com",
  "hidemyass.com",
  "hma.com",
  "whoer.net",
  "anonymouse.org",
  "4everproxy.com",
  "filterbypass.me",
  "vpnbook.com",
  "webproxy.to",
  "proxyium.com",
  "proxyscrape.com",
  "proxy-list.download",
  "proxybay.github.io",
  "unblock-websites.com",
  "dontfilter.us",
  "zend2.com",
  "megaproxy.com",
  "newipnow.com",
  "hideproxy.me",
  "free-proxy.com",
]);

const PROXY_HOST_KEYWORDS = [
  "webproxy",
  "proxy-site",
  "proxylist",
  "freeproxy",
  "unblocker",
  "siteunblock",
  "anonymizer",
  "anonymouse",
];

// Build a polished blocked page that shows which domain was blocked.
function makeBlockedPage(domain) {
  const d = domain.replace(/[^a-zA-Z0-9.\-]/g, "");
  const html =
    "<!DOCTYPE html><html><head><meta charset=utf-8>" +
    "<meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1'>" +
    "<title>\u0645\u062d\u0638\u0648\u0631 | Blocked</title><style>" +
    "*{margin:0;padding:0;box-sizing:border-box}" +
    "body{display:flex;align-items:center;justify-content:center;" +
    "min-height:100vh;background:#040806;" +
    "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;" +
    "color:#fff;padding:20px}" +
    ".c{background:#07140f;border:1px solid #1f6f4f;border-radius:20px;" +
    "padding:40px 28px;max-width:340px;width:100%;text-align:center;" +
    "box-shadow:0 24px 80px rgba(0,0,0,.65)}" +
    ".s{width:72px;height:72px;" +
    "background:linear-gradient(135deg,#0f8f61,#0b5c42);" +
    "border-radius:50%;display:flex;align-items:center;justify-content:center;" +
    "font-size:2rem;margin:0 auto 20px;" +
    "box-shadow:0 0 30px rgba(16,185,129,.35)}" +
    ".ar{font-size:1.35rem;font-weight:700;margin-bottom:4px;direction:rtl}" +
    ".en{font-size:.95rem;color:#9ca3af;margin-bottom:18px;font-weight:500}" +
    "hr{border:none;border-top:1px solid #163829;margin:0 0 16px}" +
    ".dm{background:#03100b;border:1px solid #1f6f4f;border-radius:8px;" +
    "padding:9px 14px;font-size:.8rem;color:#8df4c1;" +
    "word-break:break-all;margin-bottom:18px;font-family:monospace;letter-spacing:.02em}" +
    ".row{display:flex;align-items:flex-start;gap:8px;text-align:right;direction:rtl;" +
    "margin-bottom:8px}" +
    ".row .dot{color:#10b981;font-size:.7rem;padding-top:3px;flex-shrink:0}" +
    ".row p{color:#9ca3af;font-size:.82rem;line-height:1.55}" +
    ".badge{display:inline-flex;align-items:center;gap:6px;background:#062016;" +
    "border:1px solid #1f6f4f;border-radius:20px;padding:7px 18px;" +
    "font-size:.75rem;color:#8df4c1;margin-top:18px;font-weight:600}" +
    "</style></head><body><div class=c>" +
    "<div class=s>&#x1F6E1;&#xFE0F;</div>" +
    "<div class=ar>\u0645\u062d\u062a\u0648\u0649 \u0645\u062d\u0638\u0648\u0631</div>" +
    "<div class=en>Site Blocked</div>" +
    "<hr>" +
    "<div class=dm>" +
    d +
    "</div>" +
    "<div class=row><span class=dot>&#x25CF;</span>" +
    "<p>\u062a\u0645 \u062d\u0638\u0631 \u0647\u0630\u0627 \u0627\u0644\u0645\u0648\u0642\u0639 \u0628\u0648\u0627\u0633\u0637\u0629 \u0641\u0644\u062a\u0631 \u0623\u0645\u0627\u0646 \u0644\u062d\u0645\u0627\u064a\u062a\u0643</p></div>" +
    "<div class=row><span class=dot>&#x25CF;</span>" +
    "<p>This site was blocked by Aman\u2019s content filter to keep you safe.</p></div>" +
    "<div class=badge>&#x1F512; \u0623\u0645\u0627\u0646 Browser</div>" +
    "</div></body></html>";
  return "data:text/html;charset=utf-8," + encodeURIComponent(html);
}

function isBlocked(hostname) {
  const h = hostname.replace(/^(www\.|m\.|en\.)/, "");
  if (BLOCKED_DOMAINS.has(h)) return true;
  for (const d of BLOCKED_DOMAINS) {
    if (h.endsWith("." + d)) return true;
  }
  return false;
}

function isProxy(hostname) {
  const h = hostname.replace(/^(www\.|m\.|en\.)/, "");
  if (PROXY_DOMAINS.has(h)) return true;
  for (const d of PROXY_DOMAINS) {
    if (h.endsWith("." + d)) return true;
  }
  return PROXY_HOST_KEYWORDS.some((keyword) => h.includes(keyword));
}

// Belt-and-suspenders safe search — enforced in the extension on top of DNS-level filtering.
// CleanBrowsing Family DNS already enforces safe search at DNS, but this catches
// any edge cases where DNS is bypassed or a custom search URL is used.
function enforceSafeSearch(url) {
  try {
    const u = new URL(url);
    const h = u.hostname.replace(/^(www\.|m\.)/, "");

    // Google (all country TLDs: google.com, google.co.uk, google.com.sa …)
    if (/^google(\.[a-z]{2,3}){1,2}$/.test(h)) {
      if (u.searchParams.has("q") && u.searchParams.get("safe") !== "strict") {
        u.searchParams.set("safe", "strict");
        return u.href;
      }
      return null;
    }
    // Bing
    if (
      h === "bing.com" &&
      u.searchParams.has("q") &&
      u.searchParams.get("adlt") !== "strict"
    ) {
      u.searchParams.set("adlt", "strict");
      return u.href;
    }
    // DuckDuckGo
    if (
      h === "duckduckgo.com" &&
      u.searchParams.has("q") &&
      u.searchParams.get("kp") !== "1"
    ) {
      u.searchParams.set("kp", "1");
      return u.href;
    }
    // Yahoo Search
    if (
      h === "search.yahoo.com" &&
      u.searchParams.has("p") &&
      u.searchParams.get("vm") !== "r"
    ) {
      u.searchParams.set("vm", "r");
      return u.href;
    }
  } catch (_) {}
  return null;
}

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    try {
      const urlObj = new URL(details.url);
      const hostname = urlObj.hostname;

      // 1. Block adult domains and known proxy/anonymizer services
      if (isBlocked(hostname) || isProxy(hostname)) {
        if (details.type === "main_frame") {
          return { redirectUrl: makeBlockedPage(hostname) };
        }
        return { cancel: true };
      }

      // 2. Enforce safe search on major search engines
      if (details.type === "main_frame") {
        const safeUrl = enforceSafeSearch(details.url);
        if (safeUrl) return { redirectUrl: safeUrl };
      }
    } catch (_) {}
    return {};
  },
  { urls: ["http://*/*", "https://*/*"] },
  ["blocking"],
);

// ──────────────────────────────────────────────────────────────────────────────

// Map: requestId → content port waiting for this result
const pendingRequests = new Map();
let nativeRequestIdCounter = 0;
const NATIVE_REQUEST_TIMEOUT_MS = 12000;

function settleNativeRequest(nativeId, shouldBlur) {
  const pending = pendingRequests.get(nativeId);
  if (!pending) return;
  pendingRequests.delete(nativeId);
  clearTimeout(pending.timeoutId);
  pending.resolve(!!shouldBlur);
}

function settleAllNativeRequests(shouldBlur) {
  for (const nativeId of Array.from(pendingRequests.keys())) {
    settleNativeRequest(nativeId, shouldBlur);
  }
}

// Current gender filter (0=everyone 1=females 2=males 3=no people blur).
// Updated by settings_update messages from native side.
let currentGenderFilter = 0;
let currentEnabled = true;
let currentBlurPx = 20;
let currentTapToUnblur = true;
let currentBlurOnLoad = true;

// All active content-script ports — used to broadcast settings updates.
const activeContentPorts = new Set();

function broadcastSettingsToContent() {
  const msg = {
    type: "settings_update",
    enabled: currentEnabled,
    gender_filter: currentGenderFilter,
    blur_px: currentBlurPx,
    tap_to_unblur: currentTapToUnblur,
    blur_on_load: currentBlurOnLoad,
  };
  for (const port of activeContentPorts) {
    try {
      port.postMessage(msg);
    } catch (_) {}
  }
}

// ── Native port to Kotlin (InferenceEngine via WebExtensionManager) ───────────
let nativePort = null;

function connectNative() {
  nativePort = browser.runtime.connectNative("aman_native");

  nativePort.onMessage.addListener((message) => {
    if (!message) return;
    // Settings pushed by native side on connect or when prefs change
    if (message.type === "settings_update") {
      if (typeof message.gender_filter === "number") {
        currentGenderFilter = message.gender_filter;
      }
      if (typeof message.enabled === "boolean") {
        currentEnabled = message.enabled;
      }
      if (typeof message.blur_px === "number") {
        currentBlurPx = message.blur_px;
      }
      if (typeof message.tap_to_unblur === "boolean") {
        currentTapToUnblur = message.tap_to_unblur;
      }
      if (typeof message.blur_on_load === "boolean") {
        currentBlurOnLoad = message.blur_on_load;
      }
      broadcastSettingsToContent();
      return;
    }
    if (typeof message.id !== "number") return;
    settleNativeRequest(message.id, !!message.should_blur);
  });

  nativePort.onDisconnect.addListener((port) => {
    console.warn(
      "[Aman] Native port disconnected:",
      port.error?.message ?? "unknown reason",
    );
    nativePort = null;
    settleAllNativeRequests(false);
    // Reconnect after a short delay
    setTimeout(connectNative, 1000);
  });
}

connectNative();

// ── Canvas-based skin detection (fallback when native ML is unavailable) ───────
// Extensions bypass CORS, so fetch() can read any image cross-origin.
async function analyseImageClientSide(url, width, height) {
  if (!url || width < 80 || height < 80) return false;
  try {
    const resp = await fetch(url, { credentials: "omit" });
    if (!resp.ok) return false;
    const blob = await resp.blob();
    if (!blob.type.startsWith("image/")) return false;
    const bmp = await createImageBitmap(blob);
    const SIZE = 64;
    const canvas = new OffscreenCanvas(SIZE, SIZE);
    const ctx = canvas.getContext("2d");
    ctx.drawImage(bmp, 0, 0, SIZE, SIZE);
    bmp.close();
    const { data } = ctx.getImageData(0, 0, SIZE, SIZE);
    let skin = 0;
    for (let i = 0; i < data.length; i += 4) {
      const r = data[i],
        g = data[i + 1],
        b = data[i + 2],
        a = data[i + 3];
      if (a < 30) continue; // skip transparent pixels
      // Skin tone heuristic covering light to dark skin tones
      if (
        r > 60 &&
        g > 30 &&
        b > 15 &&
        r > b &&
        r >= g &&
        r - b > 15 &&
        Math.abs(r - g) < 60
      )
        skin++;
    }
    return skin / (SIZE * SIZE) > 0.18; // blur if >18% skin-tone pixels
  } catch (_) {
    return false;
  }
}

// ── Content-script connections ─────────────────────────────────────────────────
browser.runtime.onConnect.addListener((contentPort) => {
  if (contentPort.name !== "aman-content") return;

  activeContentPorts.add(contentPort);
  // Send current settings on connect so the page knows immediately whether
  // domain-based or any blurring should happen.
  try {
    contentPort.postMessage({
      type: "settings_update",
      enabled: currentEnabled,
      gender_filter: currentGenderFilter,
      blur_px: currentBlurPx,
      tap_to_unblur: currentTapToUnblur,
      blur_on_load: currentBlurOnLoad,
    });
  } catch (_) {}

  contentPort.onMessage.addListener((message) => {
    if (message.type !== "classify_url") return;

    if (!currentEnabled) {
      try {
        contentPort.postMessage({
          type: "blur_result",
          id: message.id,
          should_blur: false,
        });
      } catch (_) {}
      return;
    }

    if (!nativePort) {
      // Native ML unavailable — use client-side skin detection only
      analyseImageClientSide(
        message.url,
        message.width || 0,
        message.height || 0,
      ).then((shouldBlur) => {
        try {
          contentPort.postMessage({
            type: "blur_result",
            id: message.id,
            should_blur: shouldBlur,
          });
        } catch (_) {}
      });
      return;
    }

    // Native ML available — use it as the single source of truth. Running the
    // canvas fallback here doubled image network traffic and competed with
    // Gecko's renderer on image-heavy pages.
    const nativeId = ++nativeRequestIdCounter;
    const nativeMessage = { ...message, id: nativeId };
    const mlPromise = new Promise((resolve) => {
      const timeoutId = setTimeout(
        () => settleNativeRequest(nativeId, false),
        NATIVE_REQUEST_TIMEOUT_MS,
      );
      pendingRequests.set(nativeId, { port: contentPort, resolve, timeoutId });
      try {
        nativePort.postMessage(nativeMessage);
      } catch (_) {
        settleNativeRequest(nativeId, false);
      }
    });

    mlPromise.then((mlBlur) => {
      try {
        contentPort.postMessage({
          type: "blur_result",
          id: message.id,
          should_blur: mlBlur,
        });
      } catch (_) {}
    });
  });

  contentPort.onDisconnect.addListener(() => {
    activeContentPorts.delete(contentPort);
    // Clean up any pending requests from this port
    for (const [id, pending] of Array.from(pendingRequests.entries())) {
      if (pending.port === contentPort) {
        settleNativeRequest(id, false);
      }
    }
  });
});
