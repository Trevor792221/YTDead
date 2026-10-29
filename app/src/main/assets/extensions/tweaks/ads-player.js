// Last line of defence for video ads that still reach the player (for example ads stitched
// into the video stream on YouTube's servers): while the player shows an ad, mute it, press
// "Skip" as soon as it appears, and jump to the ad's end. Sound and speed come back afterwards.
"use strict";

const SKIP_BUTTONS = [
  ".ytp-ad-skip-button",
  ".ytp-ad-skip-button-modern",
  ".ytp-skip-ad-button",
  ".ytp-ad-skip-button-container button",
  ".ytp-ad-overlay-close-button",
].join(",");

let inAd = false;
let savedMuted = false;
let savedRate = 1;

function check() {
  const player = document.querySelector(".html5-video-player");
  const video = player && player.querySelector("video");
  if (!video) {
    return;
  }
  const ad = player.classList.contains("ad-showing") || player.classList.contains("ad-interrupting");
  if (ad) {
    if (!inAd) {
      inAd = true;
      savedMuted = video.muted;
      savedRate = video.playbackRate || 1;
    }
    video.muted = true;
    for (const button of document.querySelectorAll(SKIP_BUTTONS)) {
      button.click();
    }
    if (Number.isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.25) {
      video.currentTime = video.duration - 0.1;
    }
    if (video.playbackRate < 16) {
      video.playbackRate = 16;
    }
  } else if (inAd) {
    inAd = false;
    video.muted = savedMuted;
    video.playbackRate = savedRate;
  }
}

setInterval(check, 250);
