// Runs in the YouTube Music page. Adds Like / Dislike buttons to the player bar (the mobile
// layout has none). They send the same requests YouTube Music's own buttons do, signed in as
// you, so likes land in "Liked Music" everywhere. Whether the current song is liked comes from
// the song data YouTube Music already loads.
(() => {
  "use strict";
  if (window.top !== window) {
    return; // e.g. crossfade's hidden player
  }

  const ratings = new Map(); // videoId -> "LIKE" | "DISLIKE" | "INDIFFERENT"

  // --- Current ratings, from YouTube Music's own responses ------------------------------

  function collect(obj, depth) {
    if (!obj || typeof obj !== "object" || depth > 40) {
      return;
    }
    if (Array.isArray(obj)) {
      for (const item of obj) {
        collect(item, depth + 1);
      }
      return;
    }
    const like = obj.likeButtonRenderer;
    if (like && like.target && like.target.videoId && like.likeStatus) {
      ratings.set(like.target.videoId, like.likeStatus);
    }
    for (const key in obj) {
      const value = obj[key];
      if (value !== null && typeof value === "object" && key !== "frameworkUpdates") {
        collect(value, depth + 1);
      }
    }
  }

  JSON.parse = new Proxy(JSON.parse, {
    apply(target, thisArg, args) {
      const result = Reflect.apply(target, thisArg, args);
      if (typeof args[0] === "string" && args[0].includes("likeStatus")) {
        try {
          collect(result, 0);
          refresh();
        } catch (e) {
          // Never break the page.
        }
      }
      return result;
    },
  });

  Response.prototype.json = new Proxy(Response.prototype.json, {
    apply(target, thisArg, args) {
      const url = String(thisArg.url || "");
      return Reflect.apply(target, thisArg, args).then((result) => {
        if (/\/youtubei\/v1\/(next|player|music\/get_queue)/.test(url)) {
          try {
            collect(result, 0);
            refresh();
          } catch (e) {
            // Never break the page.
          }
        }
        return result;
      });
    },
  });

  // --- Sending a like / dislike -----------------------------------------------------------

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
    const origin = location.origin;
    const ts = Math.floor(Date.now() / 1000);
    const parts = [];
    for (const [cookieName, scheme] of [["SAPISID", "SAPISIDHASH"], ["__Secure-1PAPISID", "SAPISID1PHASH"],
      ["__Secure-3PAPISID", "SAPISID3PHASH"]]) {
      const value = cookie(cookieName);
      if (value) {
        parts.push(`${scheme} ${ts}_${await sha1(`${ts} ${value} ${origin}`)}`);
      }
    }
    return parts.join(" ");
  }

  async function rate(videoId, action) {
    const cfg = window.ytcfg;
    const get = (k) => (cfg && cfg.get ? cfg.get(k) : cfg && cfg.data_ && cfg.data_[k]);
    const auth = await authorization();
    if (!auth) {
      throw new Error("signed-out");
    }
    const response = await fetch(`/youtubei/v1/like/${action}?prettyPrint=false`, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        "Authorization": auth,
        "X-Origin": location.origin,
        "X-Goog-AuthUser": String(get("SESSION_INDEX") || 0),
        "X-Youtube-Client-Name": String(get("INNERTUBE_CONTEXT_CLIENT_NAME") || 67),
        "X-Youtube-Client-Version": String(get("INNERTUBE_CLIENT_VERSION") || ""),
      },
      body: JSON.stringify({ context: get("INNERTUBE_CONTEXT"), target: { videoId } }),
    });
    if (!response.ok) {
      throw new Error("HTTP " + response.status);
    }
  }

  // --- Buttons --------------------------------------------------------------------------

  const THUMB = "M2 21h4V9H2v12zm20-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.17 1 "
    + "6.59 7.59C6.22 7.95 6 8.45 6 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47"
    + ".14-.73v-2z";

  function icon(down, filled) {
    const svgNS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("width", "24");
    svg.setAttribute("height", "24");
    if (down) {
      svg.style.transform = "rotate(180deg)";
    }
    const path = document.createElementNS(svgNS, "path");
    path.setAttribute("d", THUMB);
    path.setAttribute("fill", filled ? "currentColor" : "none");
    path.setAttribute("stroke", "currentColor");
    path.setAttribute("stroke-width", filled ? "0" : "1.6");
    svg.appendChild(path);
    return svg;
  }

  const boxes = []; // one in the mini player bar, one on the full player page
  let busy = false;

  function currentId() {
    const p = document.getElementById("movie_player");
    return p && p.getVideoData ? (p.getVideoData() || {}).video_id : null;
  }

  function toast(text) {
    const el = document.createElement("div");
    el.textContent = text;
    el.style.cssText = "position:fixed;left:50%;bottom:120px;transform:translateX(-50%);z-index:2147483647;"
      + "background:#323232;color:#fff;padding:10px 16px;border-radius:8px;font:14px/1.3 Roboto,Arial,sans-serif;"
      + "max-width:80vw;text-align:center";
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3000);
  }

  async function press(kind) {
    const id = currentId();
    if (!id || busy) {
      return;
    }
    const was = ratings.get(id) || "INDIFFERENT";
    const now = was === kind ? "INDIFFERENT" : kind; // pressing it again takes it back
    const action = now === "LIKE" ? "like" : now === "DISLIKE" ? "dislike" : "removelike";
    busy = true;
    ratings.set(id, now);
    refresh();
    try {
      await rate(id, action);
    } catch (e) {
      ratings.set(id, was);
      toast(String(e.message) === "signed-out" ? "Sign in to YouTube Music to like songs." : "Couldn't save that. Try again.");
    } finally {
      busy = false;
      refresh();
    }
  }

  function makeButton(kind, label) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ytdead-rate";
    b.setAttribute("aria-label", label);
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      e.preventDefault();
      press(kind);
    }, true);
    return b;
  }

  function refresh() {
    const status = ratings.get(currentId()) || "INDIFFERENT";
    for (const box of boxes) {
      if (box.dataset.status === status) {
        continue; // already showing it; don't redraw every second
      }
      box.dataset.status = status;
      const [dislike, like] = box.children;
      like.replaceChildren(icon(false, status === "LIKE"));
      dislike.replaceChildren(icon(true, status === "DISLIKE"));
      like.setAttribute("aria-pressed", String(status === "LIKE"));
      dislike.setAttribute("aria-pressed", String(status === "DISLIKE"));
    }
  }

  function makeBox(extraClass) {
    const box = document.createElement("div");
    box.className = "ytdead-rating " + extraClass;
    box.append(makeButton("DISLIKE", "Dislike"), makeButton("LIKE", "Like"));
    boxes.push(box);
    return box;
  }

  let barBox = null;
  let pageBox = null;

  // Mini player bar: at the start of its visible controls (the mobile layout's
  // #right-controls-mweb), just before play/pause.
  function placeInBar(playing) {
    // Not the full player page's own copy of the bar (#top-player-bar).
    const bar = document.querySelector("ytmusic-player-bar:not(#top-player-bar)");
    if (!bar || !playing) {
      if (barBox) {
        barBox.style.display = "none";
      }
      return;
    }
    barBox = barBox || makeBox("ytdead-rating-bar");
    const controls = bar.querySelector("#right-controls-mweb");
    const first = controls ? controls.firstElementChild
      : bar.querySelector("#play-pause-button-mweb, #play-pause-button, .play-pause-button");
    const anchor = first === barBox ? barBox.nextElementSibling : first;
    if (anchor && anchor.parentElement && barBox.nextElementSibling !== anchor) {
      anchor.parentElement.insertBefore(barBox, anchor);
    } else if (!anchor && barBox.parentElement !== bar) {
      bar.appendChild(barBox);
    }
    barBox.style.display = "";
  }

  // Full player page: to the right of the song title, as in the YouTube Music app.
  function placeOnPage(playing) {
    const info = document.querySelector("ytmusic-player-page #player-controls .content-info-wrapper");
    if (!info || !playing) {
      if (pageBox) {
        pageBox.style.display = "none";
      }
      return;
    }
    pageBox = pageBox || makeBox("ytdead-rating-page");
    if (pageBox.parentElement !== info) {
      info.appendChild(pageBox);
    }
    info.classList.add("ytdead-has-rating");
    pageBox.style.display = "";
  }

  function place() {
    const playing = !!currentId();
    placeInBar(playing);
    placeOnPage(playing);
    refresh();
  }

  setInterval(place, 1000);
})();
