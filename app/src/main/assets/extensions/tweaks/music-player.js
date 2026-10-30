// YouTube Music's full-screen player: tells the app when it's open (the app's tab bar stays
// hidden there), and minimizes it when you swipe down on it, like the YouTube Music app.
"use strict";

if (window.top === window) {
  const SWIPE_MIN = 120; // CSS pixels down to minimize
  const FLICK_MIN = 50; // ... or this far in a quick flick
  const FLICK_MS = 250;

  let layout = null;
  let reported = null;

  const isOpen = () => !!layout && layout.hasAttribute("player-page-open");

  function report() {
    const open = isOpen();
    if (open !== reported) {
      reported = open;
      browser.runtime.sendMessage({ type: "playerPage", open }).catch(() => {});
    }
  }

  setInterval(() => {
    const found = document.querySelector("ytmusic-app-layout");
    if (found && found !== layout) {
      layout = found;
      new MutationObserver(report).observe(found, { attributes: true, attributeFilter: ["player-page-open"] });
    }
    report();
  }, 1000);

  // --- Swipe down to minimize -------------------------------------------------------------
  // YouTube Music ignores clicks for 2.5 s after a touch ends (so a touch's "ghost" click
  // doesn't press twice), which would swallow a click on its minimize button right after the
  // swipe. So the swipe's own touch end is kept from it, the panel slides away at once, and if
  // an earlier tap is still within that 2.5 s, the click is repeated as soon as it's over.

  const GHOST_CLICK_MS = 2600;
  let drag = null;
  let lastTouchEnd = 0; // other touches, which YouTube Music did see end

  function minimizeButton() {
    // The real button inside the ⌄ icon (the outer element doesn't react to clicks).
    return document.querySelector("ytmusic-player-page #collapse-button button")
      || document.querySelector("ytmusic-player-page #collapse-button");
  }

  function minimize(panel) {
    panel.style.transition = "transform 0.2s ease-in";
    panel.style.transform = "translateY(100vh)";
    const restore = () => {
      panel.style.transition = "none";
      panel.style.transform = "";
    };
    const click = () => {
      const button = minimizeButton();
      if (button && isOpen()) {
        button.click();
      }
    };
    click();
    setTimeout(() => {
      if (!isOpen()) {
        restore();
        return;
      }
      setTimeout(() => {
        click();
        setTimeout(restore, 300);
      }, Math.max(0, lastTouchEnd + GHOST_CLICK_MS - Date.now()));
    }, 250);
  }

  document.addEventListener("touchstart", (e) => {
    drag = null;
    if (!isOpen() || e.touches.length !== 1) {
      return;
    }
    const target = e.target instanceof Element ? e.target : null;
    // The artwork, title and controls; not the seek bar or the Up next / Lyrics panel.
    const panel = target && target.closest("ytmusic-player-page #main-panel");
    if (!panel || target.closest("tp-yt-paper-slider, #progress-bar, #side-panel, ytmusic-player-queue")) {
      return;
    }
    const t = e.touches[0];
    drag = { panel, x: t.clientX, y: t.clientY, time: Date.now(), moving: false, dy: 0 };
  }, { capture: true, passive: true });

  document.addEventListener("touchmove", (e) => {
    if (!drag || e.touches.length !== 1) {
      return;
    }
    const t = e.touches[0];
    const dx = t.clientX - drag.x;
    const dy = t.clientY - drag.y;
    if (!drag.moving && dy > 10 && dy > Math.abs(dx) * 1.5) {
      drag.moving = true;
      drag.panel.style.transition = "none";
    }
    if (drag.moving) {
      drag.dy = Math.max(0, dy);
      drag.panel.style.transform = `translateY(${drag.dy * 0.6}px)`;
    }
  }, { capture: true, passive: true });

  function end(e) {
    const current = drag;
    drag = null;
    if (current && current.moving) {
      const { panel, dy, time } = current;
      if (dy > SWIPE_MIN || (dy > FLICK_MIN && Date.now() - time < FLICK_MS)) {
        e.stopImmediatePropagation();
        minimize(panel);
        return;
      }
      panel.style.transition = "transform 0.2s ease-out";
      panel.style.transform = "";
    }
    lastTouchEnd = Date.now();
  }

  document.addEventListener("touchend", end, { capture: true, passive: true });
  document.addEventListener("touchcancel", end, { capture: true, passive: true });
}
