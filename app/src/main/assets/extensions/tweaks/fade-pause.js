// Runs in the page. A short fade when you pause or resume, like the YouTube Music app: on
// pause the sound fades out over a quarter of a second before the video stops; on resuming a
// paused video or song it fades back in. Applies to the player's own video, however it was
// paused or played (its buttons, the notification, headphones, Android Auto).
//
// Left alone: a new video or song starting (crossfade.js fades those on the Music tab), muted
// or silent videos, ads, and anything crossfade.js is in the middle of. YouTube doesn't see the
// temporary volume changes, so its own volume setting stays as it was.
(() => {
  "use strict";
  if (window.top !== window) {
    return; // e.g. crossfade's hidden player
  }

  const OUT_MS = 250;
  const IN_MS = 350;
  const STEP_MS = 16;
  const realPlay = HTMLMediaElement.prototype.play;
  const realPause = HTMLMediaElement.prototype.pause;

  // Equal-power curve, like crossfade.js.
  const curve = (x) => Math.sin((Math.PI / 2) * Math.max(0, Math.min(1, x)));

  let fade = null; // { video, dir: "out" | "in", from (the listener's volume), timer }
  // Our volume changes aren't the listener's: YouTube's player mustn't see (and save) them.
  // Their volumechange events come a little later, so they're hidden for a moment after each.
  const QUIET_MS = 150;
  let quietVideo = null;
  let quietUntil = 0;

  window.addEventListener("volumechange", (e) => {
    if (e.target === quietVideo && performance.now() < quietUntil) {
      e.stopImmediatePropagation();
    }
  }, true);

  function setVolume(video, v) {
    quietVideo = video;
    quietUntil = performance.now() + QUIET_MS;
    video.volume = Math.max(0, Math.min(1, v));
  }

  function isPlayerVideo(video) {
    const player = video.closest && video.closest("#movie_player, .html5-video-player");
    return !!player && !player.classList.contains("ad-showing")
      && !document.documentElement.hasAttribute("data-ytdead-xfade-busy");
  }

  function stop(restore) {
    if (!fade) {
      return;
    }
    clearInterval(fade.timer);
    if (restore) {
      setVolume(fade.video, fade.from);
    }
    fade = null;
  }

  // Fades `video` out (to silence) or in (to `from`, the listener's volume), starting from
  // where its volume is now, so a fade that turns around doesn't jump.
  function ramp(video, dir, from, ms, done) {
    const level = Math.max(0, Math.min(1, video.volume / Math.max(from, 0.01)));
    const x0 = (2 / Math.PI) * Math.asin(level); // position on the curve now
    const start = performance.now();
    const state = { video, dir, from, timer: 0 };
    fade = state;
    const step = () => {
      if (fade !== state) {
        return;
      }
      const t = (performance.now() - start) / ms;
      const x = dir === "in" ? x0 + t : x0 - t;
      setVolume(video, from * curve(x));
      if (dir === "in" ? x >= 1 : x <= 0) {
        clearInterval(state.timer);
        fade = null;
        done();
      }
    };
    state.timer = setInterval(step, STEP_MS);
    step();
  }

  HTMLMediaElement.prototype.pause = function pause() {
    if (fade && fade.video === this && fade.dir === "out") {
      return undefined; // already fading out; it pauses at the end
    }
    const resumeVolume = fade && fade.video === this ? fade.from : this.volume;
    stop(false);
    if (this.paused || this.ended || this.muted || resumeVolume < 0.01 || this.currentTime < 0.5
        || !isPlayerVideo(this)) {
      if (fade === null && this.volume !== resumeVolume) {
        setVolume(this, resumeVolume);
      }
      return realPause.call(this);
    }
    ramp(this, "out", resumeVolume, OUT_MS, () => {
      realPause.call(this);
      setVolume(this, resumeVolume); // back to the listener's volume, silently, while paused
    });
    return undefined;
  };

  HTMLMediaElement.prototype.play = function play() {
    if (fade && fade.video === this) {
      // Played again while fading: fade (back) in from where the volume is.
      const from = fade.from;
      stop(false);
      const result = realPlay.call(this);
      ramp(this, "in", from, IN_MS, () => {});
      return result;
    }
    const from = this.volume;
    // Only resuming a paused video or song, not one that's starting (crossfade.js fades those).
    if (!this.paused || this.muted || from < 0.01 || this.currentTime < 0.5 || !isPlayerVideo(this)) {
      return realPlay.call(this);
    }
    setVolume(this, 0);
    const result = realPlay.call(this);
    ramp(this, "in", from, IN_MS, () => {});
    if (result && typeof result.catch === "function") {
      result.catch(() => {
        // Didn't start (e.g. blocked): put the volume back.
        if (fade && fade.video === this) {
          stop(true);
        } else {
          setVolume(this, from);
        }
      });
    }
    return result;
  };

  // A new video or song, or the page leaving: drop any fade and put the volume back.
  for (const type of ["emptied", "loadstart"]) {
    window.addEventListener(type, (e) => {
      if (fade && e.target === fade.video) {
        stop(true);
      }
    }, true);
  }
})();
