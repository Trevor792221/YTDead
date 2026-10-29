// Passes the app's settings to page scripts as attributes on <html>.
"use strict";

browser.runtime.sendMessage({ type: "getPrefs" }).then((prefs) => {
  document.documentElement.setAttribute("data-ytdead-hq", prefs && prefs.highestQuality === false ? "0" : "1");
}, () => {
  document.documentElement.setAttribute("data-ytdead-hq", "1");
});
