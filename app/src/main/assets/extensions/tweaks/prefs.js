// Passes the app's settings to page scripts as attributes on <html>.
"use strict";

function apply(prefs) {
  const html = document.documentElement;
  html.setAttribute("data-ytdead-hq", prefs.highestQuality === false ? "0" : "1");
  html.setAttribute("data-ytdead-swipe", prefs.swipePip === false ? "0" : "1");
}

browser.runtime.sendMessage({ type: "getPrefs" }).then((prefs) => apply(prefs || {}), () => apply({}));
