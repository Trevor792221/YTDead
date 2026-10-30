// YouTube Music clean-ups: hide "Open App" in its menu. ads-page.js already removes it from the
// menu's data; this catches any that get drawn anyway, by where they link (so it works in every
// language), falling back to the English label.
"use strict";

const OPEN_APP_URL = /^https?:\/\/app\.(music\.)?youtube\.com/;

function isOpenApp(entry) {
  try {
    const data = entry.wrappedJSObject && entry.wrappedJSObject.data;
    const url = data && data.navigationEndpoint && data.navigationEndpoint.urlEndpoint
      && data.navigationEndpoint.urlEndpoint.url;
    if (typeof url === "string") {
      return OPEN_APP_URL.test(url);
    }
  } catch (e) {
    // Page data not readable: use the label.
  }
  return /^open app$/i.test((entry.textContent || "").trim());
}

function hideOpenApp() {
  for (const entry of document.querySelectorAll("ytmusic-guide-entry-renderer")) {
    if (entry.style.display !== "none" && isOpenApp(entry)) {
      entry.style.display = "none";
    }
  }
}

new MutationObserver(hideOpenApp).observe(document.documentElement, { childList: true, subtree: true });
hideOpenApp();
