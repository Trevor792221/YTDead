// YouTube Music's full-screen player: tells the app when it's open (the app's tab bar stays
// hidden there), minimizes it when you swipe down on it, and goes to the next / previous song
// when you swipe left / right on it or on the mini player bar, like the YouTube Music app.
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

  // --- Swipe left / right for the next / previous song -------------------------------------
  // On the full-screen player and on the mini player bar, like the YouTube Music app.

  const SIDE_MIN = 90; // CSS pixels sideways to change song

  function changeSong(next) {
    const el = document.getElementById("movie_player");
    const player = el && el.wrappedJSObject;
    if (!player || typeof player.nextVideo !== "function") {
      return;
    }
    const video = el.querySelector("video");
    if (next) {
      player.nextVideo();
    } else if (video && video.currentTime > 3) {
      player.seekTo(0, true); // like the previous button: first back to the start of this song
    } else {
      player.previousVideo();
    }
  }

  // Where a swipe can start: the full player's artwork, title and controls (not the seek bar or
  // the Up next / Lyrics panel), or the mini player bar at the bottom.
  function swipeArea(target) {
    if (!target || target.closest("tp-yt-paper-slider, #progress-bar, #side-panel, ytmusic-player-queue")) {
      return null;
    }
    if (isOpen()) {
      const panel = target.closest("ytmusic-player-page #main-panel");
      return panel ? { el: panel, page: true } : null;
    }
    const bar = target.closest("ytmusic-player-bar:not(#top-player-bar)");
    return bar ? { el: bar, page: false } : null;
  }

  document.addEventListener("touchstart", (e) => {
    drag = null;
    if (e.touches.length !== 1) {
      return;
    }
    const area = swipeArea(e.target instanceof Element ? e.target : null);
    if (!area) {
      return;
    }
    const t = e.touches[0];
    drag = { el: area.el, page: area.page, x: t.clientX, y: t.clientY, time: Date.now(), axis: null, d: 0 };
  }, { capture: true, passive: true });

  document.addEventListener("touchmove", (e) => {
    if (!drag || e.touches.length !== 1) {
      return;
    }
    const t = e.touches[0];
    const dx = t.clientX - drag.x;
    const dy = t.clientY - drag.y;
    if (!drag.axis) {
      if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.5) {
        drag.axis = "x";
      } else if (drag.page && dy > 10 && dy > Math.abs(dx) * 1.5) {
        drag.axis = "y";
      } else {
        return;
      }
      drag.el.style.transition = "none";
    }
    if (drag.axis === "x") {
      drag.d = dx;
      drag.el.style.transform = `translateX(${dx * 0.5}px)`;
      drag.el.style.opacity = String(Math.max(0.4, 1 - Math.abs(dx) / 600));
    } else {
      drag.d = Math.max(0, dy);
      drag.el.style.transform = `translateY(${drag.d * 0.6}px)`;
    }
  }, { capture: true, passive: true });

  function end(e) {
    const current = drag;
    drag = null;
    if (current && current.axis) {
      const { el, axis, d, time } = current;
      const quick = Date.now() - time < FLICK_MS;
      if (axis === "y" && (d > SWIPE_MIN || (d > FLICK_MIN && quick))) {
        e.stopImmediatePropagation();
        minimize(el);
        return;
      }
      el.style.transition = "transform 0.2s ease-out, opacity 0.2s ease-out";
      el.style.transform = "";
      el.style.opacity = "";
      if (axis === "x" && (Math.abs(d) > SIDE_MIN || (Math.abs(d) > FLICK_MIN && quick))) {
        e.stopImmediatePropagation(); // not a tap on the bar (which would open the player)
        changeSong(d < 0); // swipe left: next; right: previous
        return;
      }
    }
    lastTouchEnd = Date.now();
  }

  document.addEventListener("touchend", end, { capture: true, passive: true });
  document.addEventListener("touchcancel", end, { capture: true, passive: true });
}
