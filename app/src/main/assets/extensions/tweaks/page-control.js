// Carries out the app's requests in the page: play / pause through YouTube's own player (so
// its idea of whether it's playing stays right), and showing only the video while the app is
// in picture-in-picture (tweaks.css). Also remembers where you paused, to resume next time.
"use strict";

if (window.top === window) {
  const music = location.hostname === "music.youtube.com";

  const control = (action) => {
    const el = document.getElementById("movie_player");
    const player = el && el.wrappedJSObject;
    if (player && typeof player.playVideo === "function") {
      if (action === "play") {
        player.playVideo();
      } else {
        player.pauseVideo();
      }
      return;
    }
    const video = document.querySelector("video");
    if (video) {
      if (action === "play") {
        video.play().catch(() => {});
      } else {
        video.pause();
      }
    }
  };

  const onMessage = (msg) => {
    if (msg.type === "pip") {
      document.documentElement.toggleAttribute("data-ytdead-pip", !!msg.on);
    } else if (msg.type === "control" && !!msg.music === music) {
      control(msg.action);
    }
  };

  // --- Resume where you left off -----------------------------------------------------------
  // Saves the video (or song) and position when you pause, every 15 s while playing, and when
  // the page goes away; the app reopens it there next time, paused at that spot.

  const SAVE_EVERY_MS = 15000;
  let lastSave = 0;

  function current() {
    const el = document.getElementById("movie_player");
    const player = el && el.wrappedJSObject;
    if (!player || typeof player.getVideoData !== "function" || el.classList.contains("ad-showing")) {
      return null;
    }
    const id = (player.getVideoData() || {}).video_id;
    const video = el.querySelector("video");
    return id && video ? { player, id, video } : null;
  }

  // The address to reopen: this watch page, or (when a video plays on in the mini player
  // while you browse) the video's own watch page. Without a start time.
  function watchUrl(now) {
    if (location.pathname === "/watch" || location.pathname.startsWith("/shorts/")) {
      const url = new URL(location.href);
      url.searchParams.delete("t");
      return url.href;
    }
    const url = new URL("/watch", location.origin);
    url.searchParams.set("v", now.id);
    const list = typeof now.player.getPlaylistId === "function" ? now.player.getPlaylistId() : null;
    if (list) {
      url.searchParams.set("list", list);
    }
    return url.href;
  }

  function save(ended) {
    const now = current();
    if (!now || !Number.isFinite(now.video.currentTime)) {
      return;
    }
    lastSave = Date.now();
    const d = now.video.duration;
    browser.runtime.sendMessage({
      type: "resume",
      url: watchUrl(now),
      videoId: now.id,
      time: now.video.currentTime,
      ended: ended || (Number.isFinite(d) && d > 0 && now.video.currentTime > d - 5),
    }).catch(() => {});
  }

  document.addEventListener("pause", (e) => {
    if (e.target instanceof HTMLVideoElement) {
      save(false);
    }
  }, true);
  document.addEventListener("ended", (e) => {
    if (e.target instanceof HTMLVideoElement) {
      save(true);
    }
  }, true);
  document.addEventListener("timeupdate", (e) => {
    if (e.target instanceof HTMLVideoElement && !e.target.paused && Date.now() - lastSave > SAVE_EVERY_MS) {
      save(false);
    }
  }, true);
  window.addEventListener("pagehide", () => save(false));

  // Opened by the app to resume: once the saved video has loaded, go to the saved spot and pause.
  browser.runtime.sendMessage({ type: "getResume" }).then((saved) => {
    if (!saved || !saved.videoId || !(saved.time > 0)) {
      return;
    }
    const started = Date.now();
    const timer = setInterval(() => {
      const now = current();
      if (Date.now() - started > 30000) {
        clearInterval(timer);
      } else if (now && now.id === saved.videoId && Number.isFinite(now.video.duration)) {
        clearInterval(timer);
        now.player.seekTo(saved.time, true);
        now.player.pauseVideo();
      }
    }, 300);
  }).catch(() => {});

  const connect = () => {
    const port = browser.runtime.connect({ name: "ytdead-page" });
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => setTimeout(connect, 1000));
  };
  connect();
}
