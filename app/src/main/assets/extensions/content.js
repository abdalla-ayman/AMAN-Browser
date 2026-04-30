/**
 * content.js — Aman Content Filter
 *
 * Runs in every web page loaded by GeckoView.
 *
 * Pipeline:
 *   1. IntersectionObserver → detect when <img> / <video poster> enters viewport
 *   2. Extract URL + dimensions
 *   3. Send {type:"classify_url", id, url, origin} to background via port
 *   4. Receive {type:"blur_result", id, should_blur}
 *   5. Apply / remove CSS blur
 *   6. MutationObserver → repeat for dynamically injected images
 */

"use strict";

(function () {
  // Guard against double-injection in the same frame
  if (window.__amanInjected) return;
  window.__amanInjected = true;

  // ── Domain-based blurring fallback ────────────────────────────────────────
  // When ML models are unavailable, blur all images on sites known for
  // adult-adjacent or explicit content that may slip through domain blocking.
  const BLUR_ALL_DOMAINS = new Set([
    "reddit.com",
    "redd.it",
    "redgifs.com",
    "imgur.com",
    "gfycat.com",
    "scrolller.com",
    "fapello.com",
    "thothub.to",
    "erome.com",
    "bunkr.su",
    "instagram.com",
    "twitter.com",
    "x.com",
    "tumblr.com",
    "pinterest.com",
  ]);

  function isDomainBlurAll() {
    const h = window.location.hostname.replace(/^(www\.|m\.)/, "");
    if (BLUR_ALL_DOMAINS.has(h)) return true;
    for (const d of BLUR_ALL_DOMAINS) {
      if (h.endsWith("." + d)) return true;
    }
    return false;
  }

  const domainBlurMode = isDomainBlurAll();

  // ── Settings cache (refreshed from storage on load) ───────────────────────
  let blurPx = 20; // pixels of blur
  let tapToUnblur = true; // let user tap to reveal
  let blurOnLoad = true; // hide images until classification completes
  let enabled = true;
  let genderFilter = 3; // 0=everyone 1=females 2=males 3=no people blur
  let settingsReceived = false; // suppress blurring until we hear from native
  const MIN_CLASSIFY_DIMENSION = 112;
  const MAX_DATA_URL_LENGTH = 1500000;
  const MEDIA_SELECTOR = "img, video";

  function blurDisabledGlobally() {
    return !enabled;
  }

  // ── Port to background.js ─────────────────────────────────────────────────
  let port = null;
  const pendingCallbacks = new Map(); // id → { element, resolve }
  let idCounter = 0;

  function connect() {
    port = browser.runtime.connect({ name: "aman-content" });
    port.onMessage.addListener(onNativeMessage);
    port.onDisconnect.addListener(() => {
      failPendingClassifications();
      port = null;
      setTimeout(() => {
        connect();
        if (settingsReceived && !blurDisabledGlobally()) rescan();
      }, 500); // reconnect
    });
  }

  function onNativeMessage(msg) {
    if (!msg) return;
    if (msg.type === "settings_update") {
      const prevEnabled = enabled;
      const prevGender = genderFilter;
      const prevBlurPx = blurPx;
      const prevTapToUnblur = tapToUnblur;
      if (typeof msg.enabled === "boolean") enabled = msg.enabled;
      if (typeof msg.gender_filter === "number")
        genderFilter = msg.gender_filter;
      if (typeof msg.blur_px === "number") blurPx = msg.blur_px;
      if (typeof msg.tap_to_unblur === "boolean")
        tapToUnblur = msg.tap_to_unblur;
      if (typeof msg.blur_on_load === "boolean") blurOnLoad = msg.blur_on_load;
      const wasFirstSettings = !settingsReceived;
      settingsReceived = true;
      if (blurDisabledGlobally()) {
        unblurAll();
      } else {
        if (prevBlurPx !== blurPx || prevTapToUnblur !== tapToUnblur) {
          refreshBlurredElements();
        }
        if (
          wasFirstSettings ||
          prevEnabled !== enabled ||
          prevGender !== genderFilter
        ) {
          // Only force a full rescan when something that changes the
          // *classification result* actually changed. Blur intensity / tap-to-
          // unblur / blur-on-load do not change which elements are NSFW, so
          // wiping the cache for them is wasted work that re-runs ML on every
          // visible image.
          rescan();
        }
      }
      return;
    }
    const pending = pendingCallbacks.get(msg.id);
    if (!pending) return;
    pendingCallbacks.delete(msg.id);
    pending.resolve(msg.should_blur === true);
  }

  connect();

  // ── Core: classify an element ─────────────────────────────────────────────
  let processed = new WeakMap(); // element → last URL classified
  const blurred = new WeakSet(); // elements currently blurred

  // Skip extreme aspect ratios (banner ads, separators) — almost never NSFW
  function isExtremeAspect(w, h) {
    if (!w || !h) return false;
    const r = Math.max(w, h) / Math.min(w, h);
    return r >= 5;
  }

  function classifyElement(el) {
    if (!settingsReceived) return; // wait for native to tell us what to do
    if (blurDisabledGlobally()) return;
    const url = getImageUrl(el);
    if (!url) return;
    // Re-classify only when URL has changed since last pass (lazy-load swaps src)
    if (processed.get(el) === url) return;

    const rect = el.getBoundingClientRect?.();
    const width =
      el.naturalWidth ||
      el.videoWidth ||
      el.width ||
      Math.round(rect?.width || 0);
    const height =
      el.naturalHeight ||
      el.videoHeight ||
      el.height ||
      Math.round(rect?.height || 0);

    // Skip tiny icons / spacers — raised from 80 to 112 to drop avatars / favicons
    if (!width || !height) return;
    if (width < MIN_CLASSIFY_DIMENSION || height < MIN_CLASSIFY_DIMENSION) {
      processed.set(el, url);
      stopObserving(el);
      return;
    }

    // Skip very large data: URIs (mostly inlined SVGs / placeholders)
    if (
      url.startsWith("data:") &&
      (url.length < 4096 || url.length > MAX_DATA_URL_LENGTH)
    ) {
      processed.set(el, url);
      stopObserving(el);
      return;
    }

    // Skip extreme aspect ratios — banner / divider images
    if (isExtremeAspect(width, height)) {
      processed.set(el, url);
      stopObserving(el);
      return;
    }

    processed.set(el, url);
    stopObserving(el);

    if (blurOnLoad) applyBlur(el);

    // Domain-based instant blur is a content fallback. Keep it on for broad
    // content filtering modes, but defer to ML for gender-specific people modes.
    if (
      domainBlurMode &&
      (genderFilter === 0 || genderFilter === 3) &&
      !blurDisabledGlobally()
    ) {
      applyBlur(el);
      return;
    }

    if (!port) {
      processed.delete(el);
      if (blurOnLoad) removeBlur(el);
      observeElement(el);
      return;
    }

    const id = ++idCounter;

    pendingCallbacks.set(id, {
      element: el,
      url,
      resolve: (shouldBlur) => {
        if (getImageUrl(el) !== url) return;
        if (shouldBlur) applyBlur(el);
        else if (blurred.has(el)) {
          removeBlur(el);
        }
      },
    });

    try {
      port?.postMessage({
        type: "classify_url",
        id,
        url,
        origin: window.location.origin,
        width,
        height,
      });
    } catch (_) {
      pendingCallbacks.delete(id);
      if (blurOnLoad) removeBlur(el);
    }
  }

  function getImageUrl(el) {
    if (el.tagName === "IMG") return el.currentSrc || el.src || "";
    if (el.tagName === "VIDEO") return el.poster || "";
    return "";
  }

  // ── Blur / unblur ─────────────────────────────────────────────────────────
  function blurCssValue() {
    return `grayscale(1) blur(${blurPx}px)`;
  }

  function applyBlurStyle(el) {
    const value = blurCssValue();
    el.style.setProperty("filter", value, "important");
    el.style.setProperty("-webkit-filter", value, "important");
    el.style.setProperty("transition", "filter 0.18s ease", "important");
  }

  function syncTapHandler(el) {
    if (tapToUnblur) {
      el.addEventListener("click", handleUnblurClick, { passive: true });
    } else {
      el.removeEventListener("click", handleUnblurClick);
    }
  }

  function applyBlur(el) {
    if (blurred.has(el)) {
      applyBlurStyle(el);
      syncTapHandler(el);
      return;
    }
    blurred.add(el);

    applyBlurStyle(el);
    el.dataset.amanBlurred = "true";
    syncTapHandler(el);
  }

  function handleUnblurClick(e) {
    const el = e.currentTarget;
    if (!blurred.has(el)) return;
    // Reveal on single tap; classification can still re-blur unsafe media.
    removeBlur(el);
  }

  function removeBlur(el) {
    el.style.removeProperty("filter");
    el.style.removeProperty("-webkit-filter");
    el.dataset.amanBlurred = "false";
    blurred.delete(el);
    el.removeEventListener("click", handleUnblurClick);
  }

  function unblurAll() {
    document.querySelectorAll('[data-aman-blurred="true"]').forEach((el) => {
      removeBlur(el);
    });
  }

  function refreshBlurredElements() {
    document.querySelectorAll('[data-aman-blurred="true"]').forEach((el) => {
      applyBlurStyle(el);
      syncTapHandler(el);
    });
  }

  function failPendingClassifications() {
    for (const pending of pendingCallbacks.values()) {
      if (blurOnLoad && blurred.has(pending.element))
        removeBlur(pending.element);
    }
    pendingCallbacks.clear();
  }

  function stopObserving(el) {
    try {
      intersectionObserver.unobserve(el);
    } catch (_) {}
  }

  function rescan() {
    // Force re-evaluation of currently visible images.
    processed = new WeakMap();
    document.querySelectorAll(MEDIA_SELECTOR).forEach((el) => {
      if (el.tagName === "IMG" && el.complete && el.naturalWidth > 0) {
        classifyElement(el);
      } else if (el.tagName === "VIDEO") {
        classifyElement(el);
      }
    });
  }

  // ── IntersectionObserver: only process visible elements ——————————────
  const intersectionObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const el = entry.target;
          if (el.tagName === "IMG") {
            if (el.complete && el.naturalWidth > 0) {
              classifyElement(el);
            } else {
              el.addEventListener("load", () => classifyElement(el), {
                once: true,
              });
              el.addEventListener(
                "error",
                () => intersectionObserver.unobserve(el),
                { once: true },
              );
            }
          } else if (el.tagName === "VIDEO") {
            classifyElement(el);
          }
        }
      }
    },
    { rootMargin: "300px 0px", threshold: 0.01 },
  );

  function observeElement(el) {
    if (el.tagName === "IMG" || el.tagName === "VIDEO") {
      intersectionObserver.observe(el);
    }
  }

  // ── MutationObserver: catch dynamically injected content ─────────────────
  const mutationObserver = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeType !== Node.ELEMENT_NODE) continue;

        if (node.tagName === "IMG" || node.tagName === "VIDEO") {
          observeElement(node);
        } else {
          // querySelectorAll is faster than tree-walking for subtrees
          node.querySelectorAll?.(MEDIA_SELECTOR)?.forEach(observeElement);
        }

        // Re-check src changes on existing images (lazy loaders swap src)
        if (node.tagName === "IMG" && node.src && !processed.has(node)) {
          observeElement(node);
        }
      }

      // Attribute mutations (lazy-load: data-src → src swap). The element is
      // in `processed` against its OLD URL, so classifyElement() will detect
      // the change and re-evaluate when IntersectionObserver next fires.
      if (mutation.type === "attributes") {
        const el = mutation.target;
        if (el.tagName === "IMG" || el.tagName === "VIDEO") {
          observeElement(el);
          // Don't classify directly here — the IntersectionObserver will
          // schedule classification only when the element is on-screen,
          // which avoids burning ML cycles on off-viewport lazy-loaded imgs.
        }
      }
    }
  });

  // ── Initialise on current DOM ─────────────────────────────────────────────
  function scanExisting() {
    document.querySelectorAll(MEDIA_SELECTOR).forEach(observeElement);
  }

  function startObservers() {
    mutationObserver.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src", "data-src", "srcset", "poster"],
    });
  }

  // Wait for DOM to be at least partially ready
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      scanExisting();
      startObservers();
    });
  } else {
    scanExisting();
    startObservers();
  }

  // ── Settings sync from native ─────────────────────────────────────────────
  // The Kotlin side can send a "settings" message to update blur intensity
  // and tap-to-unblur without a page reload.
  browser.runtime.onMessage.addListener((msg) => {
    if (msg.type === "update_settings") {
      enabled = msg.enabled ?? enabled;
      blurPx = msg.blur_px ?? blurPx;
      tapToUnblur = msg.tap_to_unblur ?? tapToUnblur;
      if (enabled) refreshBlurredElements();
    }
  });
})();
