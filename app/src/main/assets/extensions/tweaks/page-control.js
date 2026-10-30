// Carries out the app's requests in the page: play / pause through YouTube's own player (so
// its idea of whether it's playing stays right), and showing only the video while the app is
// in picture-in-picture (tweaks.css).
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

  const connect = () => {
    const port = browser.runtime.connect({ name: "ytdead-page" });
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => setTimeout(connect, 1000));
  };
  connect();
}
