// Swipe down on the video player to pop the video out into a small floating window
// (Android picture-in-picture). The player is made full screen first so the window shows
// just the video; the app undoes that when the window is expanded again.
"use strict";

const MIN_DISTANCE = 90; // px down
const MAX_TIME = 700; // ms
const STEEPNESS = 2.5; // down must be this many times more than sideways

let start = null;
let last = null;
let dragged = null;
// The app's answer to "start", asked for mid-swipe so it is ready before full screen.
let armed = null;
// false once the app has said no (no picture-in-picture on this phone).
let armedOk;

// Set by prefs.js from App settings.
const enabled = () => document.documentElement.getAttribute("data-ytdead-swipe") === "1";

function playerAt(target) {
  return target instanceof Element ? target.closest(".html5-video-player") : null;
}

function resetDrag() {
  if (dragged) {
    dragged.style.transition = "transform 150ms ease, opacity 150ms ease";
    dragged.style.transform = "";
    dragged.style.opacity = "";
    const el = dragged;
    setTimeout(() => {
      el.style.transition = "";
    }, 160);
    dragged = null;
  }
}

document.addEventListener("touchstart", (event) => {
  start = null;
  if (!enabled() || event.touches.length !== 1 || document.fullscreenElement) {
    return;
  }
  const player = playerAt(event.target);
  if (!player || !player.querySelector("video")) {
    return;
  }
  const t = event.touches[0];
  start = { x: t.clientX, y: t.clientY, time: Date.now(), player };
  last = start;
  armed = null;
  armedOk = undefined;
}, { capture: true, passive: true });

document.addEventListener("touchmove", (event) => {
  if (!start || event.touches.length !== 1) {
    return;
  }
  const t = event.touches[0];
  last = { x: t.clientX, y: t.clientY };
  const dy = last.y - start.y;
  const dx = Math.abs(last.x - start.x);
  // A little visual feedback while the swipe looks like a pull-down.
  if (dy > 10 && dy > dx * STEEPNESS) {
    if (!armed && dy > MIN_DISTANCE / 2) {
      armed = browser.runtime.sendMessage({ type: "pip", phase: "start" }).catch(() => null);
      armed.then((answer) => {
        armedOk = !!(answer && answer.ok);
      });
    }
    dragged = start.player;
    dragged.style.transform = `translateY(${Math.min(dy, 160) * 0.4}px) scale(${1 - Math.min(dy, 160) / 1600})`;
    dragged.style.opacity = String(1 - Math.min(dy, 160) / 500);
  }
}, { capture: true, passive: true });

document.addEventListener("touchend", (event) => {
  const s = start;
  start = null;
  resetDrag();
  if (!s || !last) {
    return;
  }
  const dy = last.y - s.y;
  const dx = Math.abs(last.x - s.x);
  const ready = armed;
  armed = null;
  if (ready && armedOk !== false && dy >= MIN_DISTANCE && dy > dx * STEEPNESS && Date.now() - s.time <= MAX_TIME) {
    popOut(s.player, ready);
  } else if (ready) {
    cancel();
  }
}, { capture: true, passive: true });

document.addEventListener("touchcancel", () => {
  start = null;
  resetDrag();
  if (armed) {
    armed = null;
    cancel();
  }
}, { capture: true, passive: true });

function cancel() {
  browser.runtime.sendMessage({ type: "pip", phase: "cancel" }).catch(() => {});
}

async function popOut(player, ready) {
  const video = player.querySelector("video");
  // Firefox only allows full screen while the touch is being handled, so ask right away.
  const fullscreen = player.requestFullscreen();
  try {
    const answer = await ready;
    if (!answer || !answer.ok) {
      fullscreen.then(() => document.exitFullscreen(), () => {});
      return;
    }
    await fullscreen;
    await browser.runtime.sendMessage({
      type: "pip",
      phase: "enter",
      width: (video && video.videoWidth) || 16,
      height: (video && video.videoHeight) || 9,
    });
  } catch (e) {
    cancel();
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    }
  }
}
