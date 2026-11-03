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
// If the hidden player isn't ready in time (or you seek into the last seconds), the current song
// just fades out and the next fades in. A song you skip to fades in quickly. Seeking back,
// skipping by hand, ads and repeat-one cancel the crossfade. What happened last is shown in App
// settings.
//
// Also does the sound for swiping to the next / previous song (music-player.js): the song
// fades out while its panel slides away, then the new one fades in.
(() => {
  "use strict";
  if (window.top !== window) {
    return; // the hidden player frame itself
  }

  const TICK_MS = 100;
  const FAST_TICK_MS = 20; // during the short volume ramps (fade-in, swap), so they're smooth
  const PRELOAD = 12; // seconds before the fade starts
  const ADVANCE_AT = 0.3; // move YouTube Music on this long before the end
  const SWAP_MS = 400; // final hand-over from the hidden player
  const HANDOVER_TIMEOUT_MS = 8000;
  const SEEK_LEAD = 0.25; // seeking takes a moment; aim a little ahead
  const SKIP_FADE_IN = 3; // seconds: fade-in for a song you skipped to
  const SWIPE_OUT_MS = 180; // swiped away: fade-out before changing song (the slide's length)
  const SWIPE_IN_MS = 300; // ... and fade-in for the new song when crossfade is off
  const RESTART = "\u0000restart"; // trackId while fading in a song swiped back to its start

  // Set by prefs.js from App settings: fade length in seconds, "0" when off.
  const fadeSeconds = () => Number(document.documentElement.getAttribute("data-ytdead-xfade")) || 0;
  // Equal-power curve: the overlap sounds even, without a dip in the middle.
  const curve = (x) => Math.sin((Math.PI / 2) * Math.max(0, Math.min(1, x)));

  let phase = "idle"; // idle | preload | fading | handover | swap | fadein | swipe
  let trackId = null; // main player's song this crossfade belongs to
  let nextId = null;
  let baseVolume = 1; // the listener's volume, restored afterwards
  let frame = null;
  let handoverStart = 0;
  let swapStart = 0;
  let fadeInPlayed = 0;
  let fadeInMs = 0;
  let fadeInFallback = false; // fading in because the overlap couldn't be done (for the status)
  let reported = null; // last status sent, so each is sent once per song
  let seekTarget = null;
  let lastTick = performance.now();

  // Tells App settings what the crossfade last did (via prefs.js, which can message the app).
  function report(text) {
    const key = trackId + "|" + text;
    if (key === reported) {
      return;
    }
    reported = key;
    window.dispatchEvent(new CustomEvent("ytdead-status", {
      detail: JSON.stringify({ key: "crossfade", text }),
    }));
  }

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
    if (phase === "swipe") {
      return; // swipeTo() has the volume
    }
    const fade = fadeSeconds();
    const id = (player.getVideoData() || {}).video_id;
    if (player.classList.contains("ad-showing")) {
      if (phase !== "idle") {
        reset(video);
      }
      return;
    }
    // Off: nothing to do, except finishing a swiped-to song's short fade-in.
    if (!fade && phase !== "fadein") {
      if (phase !== "idle") {
        reset(video);
      }
      trackId = id;
      return;
    }

    switch (phase) {
      case "idle": {
        if (trackId !== null && id && id !== trackId && video.currentTime < 2 && video.volume > 0) {
          // Moved to another song without a crossfade (skipped): a short fade-in.
          baseVolume = video.volume;
          video.volume = 0;
          fadeInPlayed = 0;
          fadeInMs = Math.min(fade, SKIP_FADE_IN) * 1000;
          fadeInFallback = false;
          handoverStart = now;
          phase = "fadein"; // with trackId still the old song, so it starts right away
          return;
        }
        trackId = id;
        const d = video.duration;
        if (!Number.isFinite(d) || video.paused) {
          return;
        }
        const remaining = d - video.currentTime;
        if (remaining > fade + PRELOAD) {
          return;
        }
        if (d < fade * 2 + PRELOAD) {
          report("Not crossfading: the song is too short");
          return;
        }
        if (repeatOne()) {
          report("Not crossfading: repeat one is on");
          return;
        }
        const next = queueNext();
        if (!next || next === id) {
          report("Not crossfading: no next song in the queue");
          return;
        }
        baseVolume = video.volume;
        if (remaining > fade) {
          startPreload(next);
        } else if (remaining > ADVANCE_AT + 0.5) {
          // Seeked into the last seconds: no time to load the next song, so just fade.
          nextId = next;
          phase = "fading";
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
          fadeInMs = fade * 1000;
          fadeInFallback = true;
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
          report("Faded into the next song (the overlap didn't line up)");
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
          report("Crossfaded into the next song");
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
        const x = fadeInPlayed / fadeInMs;
        video.volume = baseVolume * curve(x);
        if (x >= 1) {
          video.volume = baseVolume;
          phase = "idle";
          if (fadeInFallback) {
            report("Faded into the next song (it wasn't ready to overlap)");
          }
          trackId = id;
        }
        return;
      }
    }
  }

  // --- Swiped to the next / previous song (music-player.js) --------------------------------

  function swipeTo(action) {
    const player = mainPlayer();
    if (!player || typeof player.nextVideo !== "function") {
      return;
    }
    const go = () => {
      if (action === "next") {
        player.nextVideo();
      } else if (action === "previous") {
        player.previousVideo();
      } else {
        player.seekTo(0, true);
      }
    };
    const video = player.querySelector("video");
    if (phase === "swipe") {
      go(); // swiped again while the last one is fading out
      return;
    }
    if (!video || video.paused || video.muted || phase === "handover" || phase === "swap"
        || player.classList.contains("ad-showing")) {
      // Nothing to fade out (or the hidden player is the one playing): just change song; a
      // skipped-to song still fades in (tick) when crossfade is on.
      reset(video);
      go();
      return;
    }
    // Back to the listener's volume afterwards, even if this interrupts a fade.
    const base = phase === "idle" ? video.volume : baseVolume;
    const from = video.volume;
    removeFrame();
    nextId = null;
    seekTarget = null;
    const oldId = (player.getVideoData() || {}).video_id;
    baseVolume = base;
    phase = "swipe";
    const started = performance.now();
    const step = () => {
      if (phase !== "swipe") {
        return;
      }
      const x = (performance.now() - started) / SWIPE_OUT_MS;
      if (x < 1) {
        video.volume = from * curve(1 - x);
        setTimeout(step, 16);
        return;
      }
      video.volume = 0;
      go();
      // tick fades the new song in once YouTube Music has moved on to it (straight away when
      // it's this song from the start).
      const fade = fadeSeconds();
      trackId = action === "restart" ? RESTART : oldId;
      fadeInPlayed = 0;
      fadeInMs = fade ? Math.min(fade, SKIP_FADE_IN) * 1000 : SWIPE_IN_MS;
      fadeInFallback = false;
      handoverStart = performance.now();
      lastTick = handoverStart;
      phase = "fadein";
    };
    step();
  }

  window.addEventListener("ytdead-skip", (e) => {
    if (typeof e.detail === "string") {
      swipeTo(e.detail);
    }
  });
  // Tells music-player.js that swipes can come here.
  document.documentElement.setAttribute("data-ytdead-skip", "");

  (function loop() {
    try {
      tick();
    } finally {
      // Tells fade-pause.js to leave the volume alone while a crossfade or skip is under way.
      document.documentElement.toggleAttribute("data-ytdead-xfade-busy", phase !== "idle");
      setTimeout(loop, phase === "fadein" || phase === "swap" ? FAST_TICK_MS : TICK_MS);
    }
  })();
})();
