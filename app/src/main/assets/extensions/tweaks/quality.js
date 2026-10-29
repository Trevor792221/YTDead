// Runs in the page. YouTube's player starts at a quality sized to the (small) phone player
// and the data-saving defaults; this switches each new video to the best quality offered,
// once. Picking a quality yourself in the player menu is left alone. Levels that need
// YouTube Premium are skipped.
(() => {
  "use strict";
  const ORDER = ["highres", "hd2880", "hd2160", "hd1440", "hd1080", "hd720", "large", "medium", "small", "tiny"];
  const MAX_TRIES = 20;
  let videoId = null;
  let tries = 0;
  let done = false;

  // Set by prefs.js from App settings: "1" on, "0" off, missing until known.
  const enabled = () => document.documentElement.getAttribute("data-ytdead-hq") === "1";

  function best(player) {
    let levels = [];
    try {
      const data = player.getAvailableQualityData && player.getAvailableQualityData();
      if (Array.isArray(data) && data.length) {
        levels = data
          .filter((q) => q && q.isPlayable !== false && !q.paygatedQualityDetails)
          .map((q) => q.quality);
      }
    } catch (e) {
      // Older player without quality data.
    }
    if (!levels.length && player.getAvailableQualityLevels) {
      levels = player.getAvailableQualityLevels() || [];
    }
    levels = levels.filter((q) => ORDER.includes(q));
    levels.sort((a, b) => ORDER.indexOf(a) - ORDER.indexOf(b));
    return levels[0];
  }

  function check() {
    if (!enabled()) {
      return;
    }
    const player = document.getElementById("movie_player");
    if (!player || typeof player.getVideoData !== "function" || player.classList.contains("ad-showing")) {
      return;
    }
    const id = (player.getVideoData() || {}).video_id;
    if (!id) {
      return;
    }
    if (id !== videoId) {
      videoId = id;
      tries = 0;
      done = false;
    }
    if (done || tries >= MAX_TRIES) {
      return;
    }
    tries++;
    const quality = best(player);
    if (!quality) {
      return;
    }
    if (player.getPlaybackQuality && player.getPlaybackQuality() === quality) {
      done = true;
      return;
    }
    if (player.setPlaybackQualityRange) {
      player.setPlaybackQualityRange(quality, quality);
    }
    if (player.setPlaybackQuality) {
      player.setPlaybackQuality(quality);
    }
  }

  setInterval(check, 750);
})();
