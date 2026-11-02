// Pull down to refresh on the YouTube and YouTube Music home pages, like the apps: at the top of
// the page, drag down until the circle turns, and let go.
"use strict";

if (window.top === window) {
  const THRESHOLD = 80; // CSS pixels of (damped) pull to refresh
  const MAX_PULL = 130;
  const ARROW = "M17.65 6.35A7.958 7.958 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A5.99 5.99 0 0 1 12 18 "
    + "6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";

  let pull = null;
  let indicator = null;

  const isHome = () => location.pathname === "/"
    && !document.querySelector("ytmusic-app-layout[player-page-open]");

  // At the very top: the page and anything scrollable under the finger.
  function atTop(target) {
    if (window.scrollY > 0 || (document.scrollingElement && document.scrollingElement.scrollTop > 0)) {
      return false;
    }
    for (let el = target; el && el !== document.body; el = el.parentElement) {
      if (el.scrollTop > 0) {
        return false;
      }
    }
    return true;
  }

  function circle() {
    if (!indicator) {
      const svgNS = "http://www.w3.org/2000/svg";
      indicator = document.createElement("div");
      indicator.className = "ytdead-pull";
      const svg = document.createElementNS(svgNS, "svg");
      svg.setAttribute("viewBox", "0 0 24 24");
      const path = document.createElementNS(svgNS, "path");
      path.setAttribute("d", ARROW);
      path.setAttribute("fill", "currentColor");
      svg.appendChild(path);
      indicator.appendChild(svg);
    }
    if (!indicator.isConnected) {
      document.documentElement.appendChild(indicator);
    }
    return indicator;
  }

  function show(distance) {
    const c = circle();
    c.classList.remove("ytdead-pull-spin", "ytdead-pull-back");
    c.classList.toggle("ytdead-pull-ready", distance >= THRESHOLD);
    c.style.transform = `translate(-50%, ${distance}px) rotate(${distance * 3}deg)`;
    c.style.opacity = String(Math.min(1, distance / THRESHOLD));
  }

  function hide() {
    if (indicator) {
      indicator.classList.add("ytdead-pull-back");
      indicator.style.transform = "translate(-50%, 0)";
      indicator.style.opacity = "0";
    }
  }

  document.addEventListener("touchstart", (e) => {
    pull = null;
    if (e.touches.length !== 1 || !isHome() || !atTop(e.target instanceof Element ? e.target : null)) {
      return;
    }
    pull = { x: e.touches[0].clientX, y: e.touches[0].clientY, active: false, distance: 0 };
  }, { capture: true, passive: true });

  document.addEventListener("touchmove", (e) => {
    if (!pull || e.touches.length !== 1) {
      return;
    }
    const dx = e.touches[0].clientX - pull.x;
    const dy = e.touches[0].clientY - pull.y;
    if (!pull.active) {
      if (dy > 10 && dy > Math.abs(dx) * 1.5 && window.scrollY === 0) {
        pull.active = true;
      } else if (dy < -5 || Math.abs(dx) > 12) {
        pull = null; // scrolling the page or a carousel
        return;
      } else {
        return;
      }
    }
    pull.distance = Math.max(0, Math.min(MAX_PULL, dy * 0.5));
    show(pull.distance);
  }, { capture: true, passive: true });

  function end() {
    const current = pull;
    pull = null;
    if (!current || !current.active) {
      return;
    }
    if (current.distance >= THRESHOLD) {
      const c = circle();
      c.classList.add("ytdead-pull-spin");
      c.style.transform = `translate(-50%, ${THRESHOLD}px)`;
      location.reload();
    } else {
      hide();
    }
  }

  document.addEventListener("touchend", end, { capture: true, passive: true });
  document.addEventListener("touchcancel", end, { capture: true, passive: true });
}
