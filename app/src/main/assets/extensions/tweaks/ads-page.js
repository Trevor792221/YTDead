// Runs in the page before YouTube's own scripts. YouTube sends ads as data inside its normal
// responses (the player response's adPlacements/playerAds/adSlots, ad renderers in feeds,
// search and watch-next lists, ad entries in the Shorts sequence). This removes that data from
// every response the page reads: the inline ytInitial* objects, fetch() and XHR responses, and
// JSON.parse() results. The page then renders as if no ads were sent.
(() => {
  "use strict";

  // Properties holding video-ad schedules in player responses.
  const AD_KEYS = ["adPlacements", "playerAds", "adSlots"];

  // Renderers that are ads or ad-like promotions wherever they appear.
  const AD_RENDERERS = new Set([
    "adSlotRenderer",
    "displayAdRenderer",
    "promotedSparklesWebRenderer",
    "promotedSparklesTextSearchRenderer",
    "promotedVideoRenderer",
    "compactPromotedVideoRenderer",
    "searchPyvRenderer",
    "inFeedAdLayoutRenderer",
    "bannerPromoRenderer",
    "statementBannerRenderer",
    "brandVideoSingletonRenderer",
    "brandVideoShelfRenderer",
    "mealbarPromoRenderer",
    "companionAdRenderer",
    "actionCompanionAdRenderer",
    "adsEngagementPanelContentRenderer",
    "merchandiseShelfRenderer",
    "productListRenderer",
  ]);

  // Wrappers whose ad-ness is decided by what they contain.
  const WRAPPERS = ["richItemRenderer", "richSectionRenderer", "reelItemRenderer", "shelfRenderer"];

  function isAdItem(item) {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      return false;
    }
    for (const key in item) {
      if (AD_RENDERERS.has(key)) {
        return true;
      }
    }
    for (const w of WRAPPERS) {
      const content = item[w] && item[w].content;
      if (content && typeof content === "object") {
        for (const key in content) {
          if (AD_RENDERERS.has(key)) {
            return true;
          }
        }
      }
    }
    // The ad panel next to a video.
    const panel = item.engagementPanelSectionListRenderer;
    if (panel && (panel.panelIdentifier === "engagement-panel-ads" || panel.targetId === "engagement-panel-ads")) {
      return true;
    }
    // Shorts: an entry in the reel sequence that is an ad.
    const reel = item.command && item.command.reelWatchEndpoint;
    if (reel && reel.adClientParams && reel.adClientParams.isAd) {
      return true;
    }
    // The empty "companion slot" under the mobile player that ads are drawn into.
    if (item.companionSlotRenderer) {
      return true;
    }
    return false;
  }

  function clean(node, depth) {
    if (!node || typeof node !== "object" || depth > 60) {
      return;
    }
    if (Array.isArray(node)) {
      for (let i = node.length - 1; i >= 0; i--) {
        if (isAdItem(node[i])) {
          node.splice(i, 1);
        } else {
          clean(node[i], depth + 1);
        }
      }
      return;
    }
    for (const key of AD_KEYS) {
      if (key in node) {
        delete node[key];
      }
    }
    for (const key in node) {
      if (AD_RENDERERS.has(key)) {
        delete node[key];
      } else {
        const value = node[key];
        if (value && typeof value === "object") {
          clean(value, depth + 1);
        }
      }
    }
  }

  // Only YouTube API data is walked, not every small object the page parses.
  function looksLikeYouTubeData(obj) {
    if (!obj || typeof obj !== "object") {
      return false;
    }
    if (Array.isArray(obj)) {
      return obj.length > 0 && obj.length < 20 && obj.some(looksLikeYouTubeData);
    }
    return "responseContext" in obj || "playerResponse" in obj || "adPlacements" in obj
      || "playabilityStatus" in obj || "onResponseReceivedActions" in obj;
  }

  function prune(obj) {
    try {
      if (looksLikeYouTubeData(obj)) {
        clean(obj, 0);
      }
    } catch (e) {
      // Never break the page over an ad.
    }
    return obj;
  }

  // --- JSON.parse and fetch ------------------------------------------------------

  JSON.parse = new Proxy(JSON.parse, {
    apply(target, thisArg, args) {
      return prune(Reflect.apply(target, thisArg, args));
    },
  });

  Response.prototype.json = new Proxy(Response.prototype.json, {
    apply(target, thisArg, args) {
      return Reflect.apply(target, thisArg, args).then(prune);
    },
  });

  // --- XMLHttpRequest ------------------------------------------------------------

  const API_URL = /\/youtubei\/v1\/|\/watch\?|\/playlist\?|\/shorts\/|[?&]pbj=1/;
  const urls = new WeakMap();
  const cache = new WeakMap();

  XMLHttpRequest.prototype.open = new Proxy(XMLHttpRequest.prototype.open, {
    apply(target, thisArg, args) {
      urls.set(thisArg, String(args[1] || ""));
      cache.delete(thisArg);
      return Reflect.apply(target, thisArg, args);
    },
  });

  function cleanedText(xhr, text) {
    if (typeof text !== "string" || xhr.readyState !== 4 || !API_URL.test(urls.get(xhr) || "")) {
      return text;
    }
    const cached = cache.get(xhr);
    if (cached && cached.raw === text) {
      return cached.clean;
    }
    let out = text;
    const prefix = text.startsWith(")]}'") ? text.slice(0, text.indexOf("\n") + 1) : "";
    try {
      // The patched JSON.parse already removes the ads.
      out = prefix + JSON.stringify(JSON.parse(text.slice(prefix.length)));
    } catch (e) {
      out = text;
    }
    cache.set(xhr, { raw: text, clean: out });
    return out;
  }

  for (const prop of ["responseText", "response"]) {
    const desc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, prop);
    if (!desc || !desc.get) {
      continue;
    }
    Object.defineProperty(XMLHttpRequest.prototype, prop, {
      ...desc,
      get: new Proxy(desc.get, {
        apply(target, thisArg, args) {
          const value = Reflect.apply(target, thisArg, args);
          if (prop === "response" && value && typeof value === "object") {
            return prune(value);
          }
          return cleanedText(thisArg, value);
        },
      }),
    });
  }

  // --- Inline data in the page HTML ---------------------------------------------

  for (const name of ["ytInitialPlayerResponse", "ytInitialData", "ytInitialReelWatchSequenceResponse"]) {
    let value;
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        enumerable: true,
        get() {
          return value;
        },
        set(v) {
          value = v && typeof v === "object" ? prune(v) : v;
        },
      });
    } catch (e) {
      // Already defined; leave it.
    }
  }
})();
