// Passes the app's settings to page scripts as attributes on <html>.
"use strict";

function apply(prefs) {
  const html = document.documentElement;
  html.setAttribute("data-ytdead-hq", prefs.highestQuality === false ? "0" : "1");
  html.setAttribute("data-ytdead-xfade", String(Number(prefs.crossfadeSeconds) || 0));
}

browser.runtime.sendMessage({ type: "getPrefs" }).then((prefs) => apply(prefs || {}), () => apply({}));
