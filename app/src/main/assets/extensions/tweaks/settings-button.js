// Adds an "App settings" button to YouTube's "You" page and YouTube Music's Library.
"use strict";

const PAGES = /^\/(feed\/(you|library|account)|account|library)(\/|$)/;
let button = null;

function update() {
  const wanted = PAGES.test(location.pathname);
  if (wanted && !button && document.body) {
    button = document.createElement("button");
    button.id = "ytgecko-settings";
    button.textContent = "⚙ App settings";
    button.addEventListener("click", () => browser.runtime.sendMessage({ type: "openSettings" }));
    document.body.appendChild(button);
  } else if (!wanted && button) {
    button.remove();
    button = null;
  }
}

setInterval(update, 700);
document.addEventListener("DOMContentLoaded", update);
