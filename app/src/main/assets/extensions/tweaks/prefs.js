// Passes the app's settings to page scripts as attributes on <html>.
"use strict";

function apply(prefs) {
  const html = document.documentElement;
  html.setAttribute("data-ytdead-hq", prefs.highestQuality === false ? "0" : "1");
  html.setAttribute("data-ytdead-xfade", String(Number(prefs.crossfadeSeconds) || 0));
  html.setAttribute("data-ytdead-av1", prefs.av1 === false ? "0" : "1");
  html.setAttribute("data-ytdead-vp9", prefs.vp9 === false ? "0" : "1");
  html.setAttribute("data-ytdead-maxh", String(Number(prefs.maxHeight) || 0));
}

browser.runtime.sendMessage({ type: "getPrefs" }).then((prefs) => apply(prefs || {}), () => apply({}));

// Status lines for App settings from page scripts (crossfade.js), passed on to the app.
window.addEventListener("ytdead-status", (event) => {
  try {
    const { key, text } = JSON.parse(event.detail);
    browser.runtime.sendMessage({ type: "status", key: String(key), text: String(text) }).catch(() => {});
  } catch (e) {
    // Malformed: ignore.
  }
});
