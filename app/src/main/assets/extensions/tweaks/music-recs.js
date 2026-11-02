// Runs in the YouTube Music page. Fetches your YouTube Music home recommendations (Quick picks,
// Listen again, Mixed for you, ...) as YouTube Music's own home page does, signed in as you,
// and hands a short list of them to the app (via page-control.js) for Android Auto.
(() => {
  "use strict";
  if (window.top !== window) {
    return;
  }

  const MAX_SHELVES = 8;
  const MAX_ITEMS = 20;

  function cookie(name) {
    const m = document.cookie.match(new RegExp("(?:^|; )" + name.replace(/[$()*+.?[\\\]^{|}]/g, "\\$&") + "=([^;]*)"));
    return m ? decodeURIComponent(m[1]) : null;
  }

  async function sha1(text) {
    const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  // The same Authorization header YouTube's own pages send for signed-in requests.
  async function authorization() {
    const ts = Math.floor(Date.now() / 1000);
    const parts = [];
    for (const [cookieName, scheme] of [["SAPISID", "SAPISIDHASH"], ["__Secure-1PAPISID", "SAPISID1PHASH"],
      ["__Secure-3PAPISID", "SAPISID3PHASH"]]) {
      const value = cookie(cookieName);
      if (value) {
        parts.push(`${scheme} ${ts}_${await sha1(`${ts} ${value} ${location.origin}`)}`);
      }
    }
    return parts.join(" ");
  }

  const text = (t) => (t && (t.simpleText || (t.runs || []).map((r) => r.text).join(""))) || "";

  // The first way to play an item: a song (watchEndpoint) or a playlist / album / mix.
  function playUrl(obj, depth) {
    if (!obj || typeof obj !== "object" || depth > 12) {
      return null;
    }
    if (obj.watchEndpoint && obj.watchEndpoint.videoId) {
      const w = obj.watchEndpoint;
      return "/watch?v=" + encodeURIComponent(w.videoId) + (w.playlistId ? "&list=" + encodeURIComponent(w.playlistId) : "");
    }
    if (obj.watchPlaylistEndpoint && obj.watchPlaylistEndpoint.playlistId) {
      return "/watch?list=" + encodeURIComponent(obj.watchPlaylistEndpoint.playlistId);
    }
    for (const key in obj) {
      const found = playUrl(obj[key], depth + 1);
      if (found) {
        return found;
      }
    }
    return null;
  }

  function thumb(item) {
    const list = (((item.thumbnailRenderer || item.thumbnail || {}).musicThumbnailRenderer || {}).thumbnail || {})
      .thumbnails || [];
    const small = list.find((t) => t.width >= 120) || list[list.length - 1];
    return small ? small.url : "";
  }

  function toItem(entry) {
    const two = entry.musicTwoRowItemRenderer;
    const row = entry.musicResponsiveListItemRenderer;
    const item = two || row;
    if (!item) {
      return null;
    }
    const url = playUrl(item, 0);
    if (!url) {
      return null;
    }
    let title;
    let subtitle;
    if (two) {
      title = text(two.title);
      subtitle = text(two.subtitle);
    } else {
      const cols = (row.flexColumns || []).map((c) => text((c.musicResponsiveListItemFlexColumnRenderer || {}).text));
      title = cols[0] || "";
      subtitle = cols.slice(1).filter(Boolean).join(" • ");
    }
    return title ? { title: title.slice(0, 120), subtitle: subtitle.slice(0, 160), thumb: thumb(item), url } : null;
  }

  function shelves(data) {
    const out = [];
    const walk = (o, depth) => {
      if (!o || typeof o !== "object" || depth > 20 || out.length >= MAX_SHELVES) {
        return;
      }
      const shelf = o.musicCarouselShelfRenderer || o.musicImmersiveCarouselShelfRenderer;
      if (shelf) {
        const header = shelf.header || {};
        const title = text((header.musicCarouselShelfBasicHeaderRenderer || header.musicImmersiveCarouselShelfBasicHeaderRenderer
          || {}).title);
        const items = (shelf.contents || []).map(toItem).filter(Boolean).slice(0, MAX_ITEMS);
        if (title && items.length) {
          out.push({ title: title.slice(0, 80), items });
        }
        return;
      }
      for (const key in o) {
        walk(o[key], depth + 1);
      }
    };
    walk(data, 0);
    return out;
  }

  async function load() {
    const cfg = window.ytcfg;
    const get = (k) => (cfg && cfg.get ? cfg.get(k) : cfg && cfg.data_ && cfg.data_[k]);
    if (!get("INNERTUBE_CONTEXT")) {
      return;
    }
    const headers = {
      "Content-Type": "application/json",
      "X-Origin": location.origin,
      "X-Goog-AuthUser": String(get("SESSION_INDEX") || 0),
      "X-Youtube-Client-Name": String(get("INNERTUBE_CONTEXT_CLIENT_NAME") || 67),
      "X-Youtube-Client-Version": String(get("INNERTUBE_CLIENT_VERSION") || ""),
    };
    const auth = await authorization();
    if (auth) {
      headers.Authorization = auth;
    }
    const response = await fetch("/youtubei/v1/browse?prettyPrint=false", {
      method: "POST",
      credentials: "include",
      headers,
      body: JSON.stringify({ context: get("INNERTUBE_CONTEXT"), browseId: "FEmusic_home" }),
    });
    if (!response.ok) {
      return;
    }
    const found = shelves(await response.json());
    if (found.length) {
      window.dispatchEvent(new CustomEvent("ytdead-recs", { detail: JSON.stringify(found) }));
    }
  }

  // Asked for by page-control.js when the app's list is missing or old.
  window.addEventListener("ytdead-recs-refresh", () => {
    load().catch(() => {});
  });
})();
