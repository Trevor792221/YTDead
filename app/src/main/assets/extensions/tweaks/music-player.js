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
  // On the full-screen player and on the mini player bar, like the YouTube Music app. Only the
  // album art moves: it follows the finger; let go past SIDE_MIN (or flick) and it carries on
  // off the screen while the song fades out (crossfade.js), then the next song's art slides in
  // from the other side once YouTube Music has switched to it. Otherwise it springs back. On
  // the mini player bar the small thumbnail moves a shorter way, so it stays over the bar.

  const SIDE_MIN = 90; // CSS pixels sideways to change song
  const FLING_SPEED = 0.5; // ... or a flick this fast (px/ms), at least FLING_MIN_X along
  const FLING_MIN_X = 30;
  const OUT_MS = 180; // slide off the screen (same as the audio fade-out in crossfade.js)
  const IN_MS = 280; // slide back in with the new song
  const BACK_MS = 220; // spring back when the swipe isn't far enough
  const CHANGE_WAIT_MS = 1500; // longest to wait for the new song before sliding back in
  const EASE_OUT = "cubic-bezier(0.2, 0, 0, 1)";
  const EASE_IN = "cubic-bezier(0.4, 0, 1, 1)";

  let slide = 0; // the running slide animation; bumped to cancel it
  let slideEl = null;

  function player() {
    const el = document.getElementById("movie_player");
    const p = el && el.wrappedJSObject;
    return p && typeof p.nextVideo === "function" ? { el, p } : null;
  }

  function songId() {
    try {
      const pl = player();
      const data = pl && pl.p.getVideoData();
      return (data && data.video_id) || null;
    } catch (e) {
      return null;
    }
  }

  function currentTime() {
    const pl = player();
    const video = pl && pl.el.querySelector("video");
    return video ? video.currentTime : 0;
  }

  // Like the previous button: first back to the start of this song.
  const skipAction = (next) => (next ? "next" : currentTime() > 3 ? "restart" : "previous");

  // crossfade.js (in the page) fades the song out, changes it and fades the new one in. If it
  // isn't there for some reason, change the song straight away.
  function changeSong(action) {
    if (document.documentElement.hasAttribute("data-ytdead-skip")) {
      window.dispatchEvent(new CustomEvent("ytdead-skip", { detail: action }));
      return;
    }
    const pl = player();
    if (!pl) {
      return;
    }
    if (action === "next") {
      pl.p.nextVideo();
    } else if (action === "previous") {
      pl.p.previousVideo();
    } else {
      pl.p.seekTo(0, true);
    }
  }

  function setStyle(el, transition, transform, opacity) {
    el.style.transition = transition;
    el.style.transform = transform;
    el.style.opacity = opacity;
  }

  // Stops a slide that's still running and shows its panel where it is.
  function cancelSlide() {
    if (slideEl) {
      slide++;
      setStyle(slideEl, "none", "", "");
      slideEl = null;
    }
  }

  // Clears the inline transition once an animation is done, so it doesn't apply to anything
  // YouTube Music changes later.
  function settle(el, ms, token) {
    setTimeout(() => {
      if (token === slide) {
        el.style.transition = "";
        if (slideEl === el) {
          slideEl = null;
        }
      }
    }, ms + 50);
  }

  function springBack(el) {
    const token = ++slide;
    slideEl = el;
    setStyle(el, `transform ${BACK_MS}ms ${EASE_OUT}, opacity ${BACK_MS}ms ${EASE_OUT}`, "", "");
    settle(el, BACK_MS, token);
  }

  // How far the art goes when it slides away: off the screen on the full player; on the mini
  // player bar, half its own width again.
  function travel(art, page) {
    return page ? window.innerWidth : (art.getBoundingClientRect().width || 48) * 1.5;
  }

  // The album art (or the video, for songs with one) that moves with a sideways swipe.
  function artFor(area, page) {
    return page
      ? area.querySelector("ytmusic-player#player, #player, #song-image")
      : area.querySelector(".thumbnail-image-wrapper, .image, img");
  }

  function slideAway(el, page, next) {
    const token = ++slide;
    const dir = next ? -1 : 1;
    const far = el ? travel(el, page) : 0;
    if (el) {
      slideEl = el;
      setStyle(el, `transform ${OUT_MS}ms ${EASE_IN}, opacity ${OUT_MS}ms linear`, `translateX(${dir * far}px)`, "0");
    }

    const action = skipAction(next);
    const before = songId();
    changeSong(action);
    const started = Date.now();
    const slideIn = () => {
      if (token !== slide || !el) {
        return;
      }
      const elapsed = Date.now() - started;
      const changed = action === "restart" || songId() !== before
        // "previous" on the first song just starts it again
        || (action === "previous" && elapsed > 600 && currentTime() < 1);
      if (!changed && elapsed < CHANGE_WAIT_MS) {
        setTimeout(slideIn, 40);
        return;
      }
      setStyle(el, "none", `translateX(${-dir * far * 0.3}px)`, "0");
      el.getBoundingClientRect(); // start the next transition from there
      setStyle(el, `transform ${IN_MS}ms ${EASE_OUT}, opacity ${IN_MS}ms ease-out`, "", "");
      settle(el, IN_MS, token);
    };
    setTimeout(slideIn, OUT_MS);
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

  // Moves follow the finger once per frame, however many touch events arrive in between.
  let frame = 0;
  function render() {
    frame = 0;
    if (!drag || !drag.axis) {
      return;
    }
    const el = drag.target;
    if (!el) {
      return;
    }
    if (drag.axis === "x") {
      if (drag.page) {
        el.style.transform = `translateX(${drag.d}px)`;
        el.style.opacity = String(Math.max(0.5, 1 - Math.abs(drag.d) / (drag.far * 1.2)));
      } else {
        const x = Math.max(-drag.far, Math.min(drag.far, drag.d * 0.5));
        el.style.transform = `translateX(${x}px)`;
        el.style.opacity = String(1 - 0.6 * Math.abs(x) / drag.far);
      }
    } else {
      el.style.transform = `translateY(${drag.d * 0.6}px)`;
    }
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
    const now = performance.now();
    drag = {
      el: area.el, page: area.page, x: t.clientX, y: t.clientY, time: now, axis: null, d: 0,
      target: null, far: 0, v: 0, lastX: 0, lastTime: now,
    };
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
      // Sideways only the art moves; down, the whole player.
      drag.target = drag.axis === "x" ? artFor(drag.el, drag.page) : drag.el;
      if (slideEl && (slideEl === drag.target || drag.el.contains(slideEl))) {
        cancelSlide(); // grabbed again mid-slide: carry on from the finger
      }
      if (drag.target) {
        drag.far = travel(drag.target, drag.page);
        drag.target.style.transition = "none";
      }
      if (drag.axis === "x") {
        // Measured from where the drag locked on, so the panel doesn't jump.
        drag.x += Math.sign(dx) * 12;
      }
    }
    const now = performance.now();
    if (drag.axis === "x") {
      drag.d = t.clientX - drag.x;
      const dt = now - drag.lastTime;
      if (dt > 0) {
        const v = (drag.d - drag.lastX) / dt;
        drag.v = dt > 100 ? v : drag.v * 0.4 + v * 0.6; // recent speed, smoothed
      }
      drag.lastX = drag.d;
      drag.lastTime = now;
    } else {
      drag.d = Math.max(0, dy);
    }
    if (!frame) {
      frame = requestAnimationFrame(render);
    }
  }, { capture: true, passive: true });

  function end(e) {
    const current = drag;
    drag = null;
    if (frame) {
      cancelAnimationFrame(frame); // a late frame would undo what's set below
      frame = 0;
    }
    if (current && current.axis) {
      const { el, target, page, axis, d, time } = current;
      const now = performance.now();
      if (axis === "y") {
        const quick = now - time < FLICK_MS;
        if (d > SWIPE_MIN || (d > FLICK_MIN && quick)) {
          e.stopImmediatePropagation();
          minimize(el);
          return;
        }
        springBack(el);
      } else {
        // A finger that stopped before lifting isn't a flick.
        const v = now - current.lastTime > 80 ? 0 : current.v;
        const fling = Math.abs(v) > FLING_SPEED && Math.sign(v) === Math.sign(d) && Math.abs(d) > FLING_MIN_X;
        if (Math.abs(d) > SIDE_MIN || fling) {
          e.stopImmediatePropagation(); // not a tap on the bar (which would open the player)
          slideAway(target, page, d < 0); // swipe left: next; right: previous
          return;
        }
        if (target) {
          springBack(target);
        }
      }
    }
    lastTouchEnd = Date.now();
  }

  document.addEventListener("touchend", end, { capture: true, passive: true });
  document.addEventListener("touchcancel", end, { capture: true, passive: true });
}
