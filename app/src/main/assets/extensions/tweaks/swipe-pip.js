// Swipe down on the video player to pop the video out into a small floating window
// (Android picture-in-picture). The player is made full screen first so the window shows
// just the video; the app undoes that when the window is expanded again.
//
// Only on watch pages: in the home feed a downward swipe is scrolling, and on Shorts it means
// "previous Short".
"use strict";

const MIN_DISTANCE = 80; // px down
const MAX_TIME = 800; // ms
const STEEPNESS = 2; // down must be this many times more than sideways
const PLAYER_BOXES = "#player-container-id, .player-container, ytm-player, #player, .html5-video-player";

let start = null;
let last = null;
let dragged = null;
// The app's answer to "start", asked for mid-swipe so it is ready before full screen.
let armed = null;
// false once the app has said no (no picture-in-picture on this phone).
let armedOk;

// Set by prefs.js from App settings.
const enabled = () => document.documentElement.getAttribute("data-ytdead-swipe") === "1";
const onWatchPage = () => location.pathname === "/watch" || location.pathname.startsWith("/live/");

function inside(rect, x, y) {
  return rect.width > 0 && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
}

// The player under a screen point, found by position rather than by the touched element:
// YouTube's mobile controls are an overlay that sits outside the player element.
function playerAt(x, y) {
  for (const video of document.querySelectorAll("video")) {
    const v = video.getBoundingClientRect();
    if (v.width < 120 || v.height < 60) {
      continue;
    }
    const box = video.closest(PLAYER_BOXES) || video;
    if (inside(v, x, y) || inside(box.getBoundingClientRect(), x, y)) {
      return {
        video,
        // What goes full screen: the player itself, so the window shows only the video.
        player: video.closest(".html5-video-player") || video.parentElement || video,
        // What slides down while dragging: the whole player area.
        area: video.closest("#player-container-id, .player-container, ytm-player") || box,
      };
    }
  }
  return null;
}

function toast(text) {
  const el = document.createElement("div");
  el.textContent = text;
  el.style.cssText = "position:fixed;left:50%;bottom:96px;transform:translateX(-50%);z-index:2147483647;"
    + "background:#323232;color:#fff;padding:10px 16px;border-radius:8px;font:14px/1.3 Roboto,Arial,sans-serif;"
    + "max-width:80vw;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.5)";
  (document.body || document.documentElement).appendChild(el);
  setTimeout(() => el.remove(), 3500);
}

function resetDrag() {
  if (dragged) {
    const el = dragged;
    el.style.transition = "transform 150ms ease, opacity 150ms ease";
    el.style.transform = "";
    el.style.opacity = "";
    setTimeout(() => {
      el.style.transition = "";
    }, 160);
    dragged = null;
  }
}

function cancel() {
  browser.runtime.sendMessage({ type: "pip", phase: "cancel" }).catch(() => {});
}

window.addEventListener("touchstart", (event) => {
  start = null;
  armed = null;
  armedOk = undefined;
  if (!enabled() || !onWatchPage() || event.touches.length !== 1 || document.fullscreenElement) {
    return;
  }
  const t = event.touches[0];
  const hit = playerAt(t.clientX, t.clientY);
  if (!hit) {
    return;
  }
  start = { x: t.clientX, y: t.clientY, time: Date.now(), ...hit };
  last = start;
}, { capture: true, passive: true });

window.addEventListener("touchmove", (event) => {
  if (!start || event.touches.length !== 1) {
    return;
  }
  const t = event.touches[0];
  last = { x: t.clientX, y: t.clientY };
  const dy = last.y - start.y;
  const dx = Math.abs(last.x - start.x);
  if (dy > 10 && dy > dx * STEEPNESS) {
    if (!armed && dy > MIN_DISTANCE / 2) {
      armed = browser.runtime.sendMessage({ type: "pip", phase: "start" }).catch(() => null);
      armed.then((answer) => {
        armedOk = !!(answer && answer.ok);
      });
    }
    // A little visual feedback while the swipe looks like a pull-down.
    dragged = start.area;
    const d = Math.min(dy, 160);
    dragged.style.transform = `translateY(${d * 0.4}px) scale(${1 - d / 1600})`;
    dragged.style.opacity = String(1 - d / 500);
  }
}, { capture: true, passive: true });

window.addEventListener("touchend", () => {
  const s = start;
  start = null;
  resetDrag();
  const ready = armed;
  armed = null;
  if (!s || !last) {
    return;
  }
  const dy = last.y - s.y;
  const dx = Math.abs(last.x - s.x);
  const isSwipe = dy >= MIN_DISTANCE && dy > dx * STEEPNESS && Date.now() - s.time <= MAX_TIME;
  if (isSwipe && ready && armedOk !== false) {
    popOut(s, ready);
  } else if (isSwipe && armedOk === false) {
    toast("Pop-up player isn't available. Check that picture-in-picture is allowed for YTDead.");
  } else if (ready) {
    cancel();
  }
}, { capture: true, passive: true });

window.addEventListener("touchcancel", () => {
  start = null;
  resetDrag();
  if (armed) {
    armed = null;
    cancel();
  }
}, { capture: true, passive: true });

async function popOut(hit, ready) {
  // Firefox only allows full screen while the touch is being handled, so ask right away.
  const fullscreen = hit.player.requestFullscreen();
  try {
    const answer = await ready;
    if (!answer || !answer.ok) {
      fullscreen.then(() => document.exitFullscreen(), () => {});
      toast("Pop-up player isn't available. Check that picture-in-picture is allowed for YTDead.");
      return;
    }
    await fullscreen;
    const entered = await browser.runtime.sendMessage({
      type: "pip",
      phase: "enter",
      width: hit.video.videoWidth || 16,
      height: hit.video.videoHeight || 9,
    });
    if (!entered || !entered.ok) {
      toast("Couldn't open the pop-up player.");
    }
  } catch (e) {
    cancel();
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    }
    toast("Couldn't open the pop-up player: " + ((e && e.message) || e));
  }
}
