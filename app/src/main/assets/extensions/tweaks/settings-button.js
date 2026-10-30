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

// Also a "Settings" pill at the start of the pill bar at the top of the home page
// (YouTube's filter chips, YouTube Music's mood chips).
const GEAR = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32"
  + "a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17"
  + "-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03"
  + " 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96"
  + "c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62"
  + "-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6"
  + "-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";

function gearIcon() {
  const svgNS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", "18");
  svg.setAttribute("height", "18");
  svg.setAttribute("aria-hidden", "true");
  const path = document.createElementNS(svgNS, "path");
  path.setAttribute("d", GEAR);
  path.setAttribute("fill", "currentColor");
  svg.appendChild(path);
  return svg;
}

function makePill() {
  const wrap = document.createElement("div");
  wrap.className = "ytdead-settings-chip";
  const button = document.createElement("button");
  button.type = "button";
  button.setAttribute("aria-label", "App settings");
  const label = document.createElement("span");
  label.textContent = "Settings";
  button.append(gearIcon(), label);
  // Capture phase, so the pill bar doesn't treat it as one of its filters.
  wrap.addEventListener("click", (e) => {
    e.stopPropagation();
    e.preventDefault();
    browser.runtime.sendMessage({ type: "openSettings" });
  }, true);
  wrap.appendChild(button);
  return wrap;
}

let pill = null;

function placePill() {
  if (location.pathname !== "/") {
    return;
  }
  // YouTube: the filter chips' scroll container; YouTube Music: the chip cloud.
  const first = document.querySelector(
    "chip-bar-view-model .ytChipBarViewModelChipWrapper, "
    + "ytm-feed-filter-chip-bar-renderer ytm-chip-cloud-chip-renderer, "
    + "ytmusic-chip-cloud-renderer ytmusic-chip-cloud-chip-renderer");
  if (!first || !first.parentElement) {
    return;
  }
  if (pill && pill.nextElementSibling === first) {
    return;
  }
  pill = pill || makePill();
  pill.classList.toggle("ytdead-settings-chip-music", first.tagName === "YTMUSIC-CHIP-CLOUD-CHIP-RENDERER");
  if (pill.classList.contains("ytdead-settings-chip-music")) {
    // Line up with YouTube Music's chips, whose spacing is set on the chip itself.
    const style = getComputedStyle(first);
    pill.style.padding = style.padding;
    pill.style.margin = style.margin;
  } else {
    // Same colours as YouTube's unselected chips, in light and dark mode.
    const chip = first.parentElement.querySelector(".ytChipShapeInactive");
    if (chip) {
      const style = getComputedStyle(chip);
      pill.firstElementChild.style.background = style.backgroundColor;
      pill.firstElementChild.style.color = style.color;
    }
  }
  first.parentElement.insertBefore(pill, first);
}

setInterval(placePill, 700);
