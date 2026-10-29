// Runs in the YouTube Music page. Crossfade for non-stop playback: the next song starts in a
// second, hidden YouTube Music player while the current one is still playing, and the two
// are faded across each other.
//
//   1. About PRELOAD seconds before the end, a hidden same-site frame loads the next song in
//      the queue, held paused and silent.
//   2. Over the last `fade` seconds, the current song fades out while the hidden player plays
//      the next song and fades it in.
//   3. When the current song ends, YouTube Music itself moves on to the next song (so the
//      queue, likes and history stay right). It's muted, jumped to where the hidden player
//      is, and the two swap over in a fraction of a second; then the frame is removed.
//
// If the hidden player isn't ready in time, the current song just fades out and the next fades
// in. Seeking back, skipping by hand, ads and repeat-one cancel the crossfade.
(() => {
  "use strict";
  if (window.top !== window) {
    return; // the hidden player frame itself
  }

  const TICK_MS = 100;
  const PRELOAD = 12; // seconds before the fade starts
  const ADVANCE_AT = 0.3; // move YouTube Music on this long before the end
  const SWAP_MS = 400; // final hand-over from the hidden player
  const HANDOVER_TIMEOUT_MS = 8000;
  const SEEK_LEAD = 0.25; // seeking takes a moment; aim a little ahead

  // Set by prefs.js from App settings: fade length in seconds, "0" when off.
  const fadeSeconds = () => Number(document.documentElement.getAttribute("data-ytdead-xfade")) || 0;
  // Equal-power curve: the overlap sounds even, without a dip in the middle.
  const curve = (x) => Math.sin((Math.PI / 2) * Math.max(0, Math.min(1, x)));

  let phase = "idle"; // idle | preload | fading | handover | swap | fadein
  let trackId = null; // main player's song this crossfade belongs to
  let nextId = null;
  let baseVolume = 1; // the listener's volume, restored afterwards
  let frame = null;
  let handoverStart = 0;
  let swapStart = 0;
  let fadeInPlayed = 0;
  let seekTarget = null;
  let lastTick = performance.now();

  function mainPlayer() {
    const p = document.getElementById("movie_player");
    return p && typeof p.getVideoData === "function" ? p : null;
  }

  function frameVideo() {
    try {
      const p = frame && frame.contentDocument && frame.contentDocument.getElementById("movie_player");
      const v = p && p.querySelector("video");
      return v && p.getVideoData && p.getVideoData().video_id === nextId && Number.isFinite(v.duration) ? v : null;
    } catch (e) {
      return null;
    }
  }

  // Any video in the hidden frame, ready or not: YouTube Music autoplays on load, so it's
  // silenced the moment it exists.
  function rawFrameVideo() {
    try {
      return (frame && frame.contentDocument && frame.contentDocument.querySelector("video")) || null;
    } catch (e) {
      return null;
    }
  }

  function queueNext() {
    const items = [...document.querySelectorAll("ytmusic-player-queue-item")];
    const sel = items.findIndex((e) => e.hasAttribute("selected") || e.selected);
    const next = sel >= 0 ? items[sel + 1] : null;
    return (next && next.data && next.data.videoId) || null;
  }

  function repeatOne() {
    const c = document.querySelector("[repeat-mode]");
    return !!c && c.getAttribute("repeat-mode") === "ONE";
  }

  function removeFrame() {
    if (frame) {
      try {
        const v = frame.contentDocument && frame.contentDocument.querySelector("video");
        if (v) {
          v.pause();
        }
      } catch (e) {
        // Frame already gone.
      }
      frame.remove();
      frame = null;
    }
  }

  function reset(video) {
    removeFrame();
    if (video && phase !== "idle") {
      video.muted = false;
      video.volume = baseVolume;
    }
    phase = "idle";
    nextId = null;
    seekTarget = null;
  }

  function startPreload(id) {
    nextId = id;
    frame = document.createElement("iframe");
    frame.name = "ytdead-crossfade";
    frame.allow = "autoplay";
    frame.setAttribute("aria-hidden", "true");
    frame.tabIndex = -1;
    frame.style.cssText = "position:fixed;left:-2000px;top:0;width:360px;height:640px;opacity:0;"
      + "pointer-events:none;border:0";
    frame.src = "/watch?v=" + encodeURIComponent(id);
    document.body.appendChild(frame);
    phase = "preload";
    guardFrame(frame);
  }

  // Silence the hidden player's autoplay in the same event that starts it, so not even a
  // fraction of a second of the next song is heard before the fade.
  function guardFrame(f) {
    const silence = (event) => {
      if (phase === "preload" && event.target instanceof f.contentWindow.HTMLMediaElement) {
        event.target.muted = true;
        event.target.pause();
      }
    };
    const started = performance.now();
    const poll = setInterval(() => {
      if (f !== frame || performance.now() - started > 20000) {
        clearInterval(poll);
        return;
      }
      try {
        const w = f.contentWindow;
        if (w && w.location.href !== "about:blank" && !w.__ytdeadGuard) {
          w.__ytdeadGuard = true;
          for (const type of ["loadstart", "play", "playing"]) {
            w.addEventListener(type, silence, true);
          }
        }
      } catch (e) {
        // Not loaded yet.
      }
    }, 20);
  }

  function tick() {
    const now = performance.now();
    const dt = now - lastTick;
    lastTick = now;

    const player = mainPlayer();
    const video = player && player.querySelector("video");
    if (!video) {
      return;
    }
    const fade = fadeSeconds();
    if (!fade || player.classList.contains("ad-showing")) {
      if (phase !== "idle") {
        reset(video);
      }
      return;
    }
    const id = (player.getVideoData() || {}).video_id;

    switch (phase) {
      case "idle": {
        trackId = id;
        const d = video.duration;
        if (!Number.isFinite(d) || d < fade * 2 + PRELOAD || video.paused || repeatOne()) {
          return;
        }
        const remaining = d - video.currentTime;
        if (remaining <= fade + PRELOAD && remaining > fade) {
          const next = queueNext();
          if (next && next !== id) {
            baseVolume = video.volume;
            startPreload(next);
          }
        }
        return;
      }

      case "preload":
      case "fading": {
        if (id !== trackId) {
          reset(video); // skipped by hand
          return;
        }
        const remaining = video.duration - video.currentTime;
        if (remaining > fade + PRELOAD + 1) {
          reset(video); // seeked back
          return;
        }
        const fv = frameVideo();
        if (phase === "preload") {
          // Hold the next song at its start, silent, until the fade.
          const raw = rawFrameVideo();
          if (raw) {
            raw.muted = true;
            if (!raw.paused) {
              raw.pause();
            }
            if (fv && fv.currentTime > 0.5) {
              fv.currentTime = 0;
            }
          }
        }
        if (remaining > fade) {
          if (phase === "fading") {
            // Seeked back out of the fade (but not out of the preload window).
            video.volume = baseVolume;
            if (fv) {
              fv.pause();
              fv.currentTime = 0;
              fv.muted = true;
            }
            phase = "preload";
          }
          return;
        }
        phase = "fading";
        const x = remaining / fade; // 1 -> 0 over the fade
        video.volume = baseVolume * curve(x);
        // A song that has reached its end also reports "paused"; that isn't the listener pausing.
        const atEnd = remaining <= ADVANCE_AT || video.ended;
        if (fv) {
          fv.muted = false;
          fv.volume = baseVolume * curve(1 - x);
          if (atEnd || !video.paused) {
            if (fv.paused) {
              fv.play().catch(() => {});
            }
          } else if (!fv.paused) {
            fv.pause(); // paused during the fade: pause both
          }
        }
        if (atEnd) {
          // Hand over to YouTube Music's own next song; muted until it lines up.
          video.muted = true;
          handoverStart = now;
          phase = fv ? "handover" : "fadein";
          fadeInPlayed = 0;
          if (phase === "fadein") {
            removeFrame();
          }
          player.nextVideo();
        }
        return;
      }

      case "handover": {
        const fv = frameVideo();
        if (!fv || now - handoverStart > HANDOVER_TIMEOUT_MS) {
          // The hidden player went away or this took too long: just carry on with the main one.
          reset(video);
          return;
        }
        fv.volume = baseVolume;
        if (id !== nextId || !Number.isFinite(video.duration) || video.paused) {
          return; // still loading the next song
        }
        if (seekTarget === null) {
          seekTarget = fv.currentTime + SEEK_LEAD;
          video.currentTime = seekTarget;
          return;
        }
        if (video.seeking || video.currentTime < seekTarget - 0.05) {
          return;
        }
        if (Math.abs(fv.currentTime - video.currentTime) > 0.3) {
          seekTarget = fv.currentTime + SEEK_LEAD; // drifted; once more
          video.currentTime = seekTarget;
          return;
        }
        video.muted = false;
        video.volume = 0;
        swapStart = now;
        phase = "swap";
        return;
      }

      case "swap": {
        const fv = frameVideo();
        const x = Math.min(1, (now - swapStart) / SWAP_MS);
        video.volume = baseVolume * curve(x);
        if (fv) {
          fv.volume = baseVolume * curve(1 - x);
        }
        if (x >= 1) {
          removeFrame();
          video.volume = baseVolume;
          phase = "idle";
          trackId = id;
          nextId = null;
          seekTarget = null;
        }
        return;
      }

      case "fadein": {
        // Fallback without the hidden player: the next song fades in from the start.
        if (now - handoverStart > HANDOVER_TIMEOUT_MS && id === trackId) {
          reset(video); // never moved on
          return;
        }
        if (id === trackId) {
          return;
        }
        video.muted = false;
        if (!video.paused) {
          fadeInPlayed += dt;
        }
        const x = fadeInPlayed / (fade * 1000);
        video.volume = baseVolume * curve(x);
        if (x >= 1) {
          video.volume = baseVolume;
          phase = "idle";
          trackId = id;
        }
        return;
      }
    }
  }

  setInterval(tick, TICK_MS);
})();
