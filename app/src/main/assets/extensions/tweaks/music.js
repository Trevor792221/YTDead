// YouTube Music clean-ups: hide "Open App" (in the menu, and in the top bar when signed in).
// ads-page.js already removes the menu entry from YouTube Music's data and tweaks.css hides
// links to the app; this catches the rest, by where they link (any language) or by their label.
"use strict";

const OPEN_APP_URL = /^(https?:\/\/app\.(music\.)?youtube\.com|intent:)/;
const OPEN_APP_LABEL = /^(open app|open in app|get the app)$/i;

function linksToApp(el) {
  try {
    const data = el.wrappedJSObject && el.wrappedJSObject.data;
    const endpoint = data && (data.navigationEndpoint || data.command);
    const url = endpoint && endpoint.urlEndpoint && endpoint.urlEndpoint.url;
    if (typeof url === "string" && OPEN_APP_URL.test(url)) {
      return true;
    }
  } catch (e) {
    // Page data not readable.
  }
  const a = el.matches("a") ? el : el.querySelector("a[href]");
  return !!(a && OPEN_APP_URL.test(a.getAttribute("href") || ""));
}

function hideOpenApp() {
  const candidates = document.querySelectorAll(
    "ytmusic-guide-entry-renderer, ytmusic-nav-bar ytmusic-button-renderer, "
    + "ytmusic-nav-bar yt-button-renderer, ytmusic-nav-bar yt-button-shape, ytmusic-nav-bar a, "
    + "ytmusic-nav-bar button");
  for (const el of candidates) {
    if (el.style.display === "none") {
      continue;
    }
    if (linksToApp(el) || OPEN_APP_LABEL.test((el.textContent || "").trim())) {
      const host = el.closest("ytmusic-guide-entry-renderer, ytmusic-button-renderer, yt-button-renderer") || el;
      host.style.display = "none";
    }
  }
}

// YouTube Music changes the page constantly; check at most a few times a second.
let pending = false;
new MutationObserver(() => {
  if (!pending) {
    pending = true;
    setTimeout(() => {
      pending = false;
      hideOpenApp();
    }, 250);
  }
}).observe(document.documentElement, { childList: true, subtree: true });
hideOpenApp();
