// YouTube Music clean-ups: hide the "Open App" entry in its menu (it has no link to match
// in CSS, only its label).
"use strict";

function hideOpenApp() {
  for (const entry of document.querySelectorAll("ytmusic-guide-entry-renderer")) {
    if (entry.style.display !== "none" && (entry.innerText || "").trim() === "Open App") {
      entry.style.display = "none";
    }
  }
}

new MutationObserver(hideOpenApp).observe(document.documentElement, { childList: true, subtree: true });
hideOpenApp();
