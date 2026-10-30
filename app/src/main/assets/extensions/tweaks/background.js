"use strict";

// Relays requests from content scripts to the app. The page's origin comes from the
// sender, which the page can't fake, never from the message itself.

// The app sends structured replies as JSON text (GeckoView can only pass back true/false,
// numbers and text); turn them back into objects.
async function ask(message) {
  const reply = await browser.runtime.sendNativeMessage("ytgecko", message);
  if (typeof reply === "string" && (reply.startsWith("{") || reply.startsWith("["))) {
    return JSON.parse(reply);
  }
  return reply;
}

browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg?.type === "openSettings" || msg?.type === "getPrefs") {
    return ask({ type: msg.type });
  }
  if (msg?.type === "status") {
    return browser.runtime.sendNativeMessage("ytgecko", {
      type: "status", key: String(msg.key).slice(0, 40), text: String(msg.text).slice(0, 200),
    });
  }
  if (msg?.type === "webauthn") {
    return webauthn(msg, sender);
  }
  return undefined;
});

// Ad requests YouTube makes from the app: ad servers, ad-break and mid-roll lookups, ad
// tracking pings, and ad video streams (googlevideo URLs whose signed parameters include
// ctier=L, as in uBlock Origin's own filters).
const BLOCK_HOSTS = /(^|\.)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|googletagservices\.com|imasdk\.googleapis\.com|ads\.youtube\.com)$/;
const BLOCK_PATHS = [
  /^https:\/\/(www|m|music)\.youtube\.com\/(pagead\/|api\/stats\/ads|ptracking|get_midroll_|youtubei\/v1\/player\/ad_break)/,
  /^https:\/\/(www|m)\.youtube\.com\/[^?]*\/ad\.js/,
  /^https:\/\/[^/]+\.googlevideo\.com\/videoplayback\?.*ctier=L&.*%2Cxpc%2Cctier%2C/,
];

browser.webRequest.onBeforeRequest.addListener((details) => {
  let host;
  try {
    host = new URL(details.url).hostname;
  } catch (e) {
    return {};
  }
  if (BLOCK_HOSTS.test(host) || BLOCK_PATHS.some((re) => re.test(details.url))) {
    return { cancel: true };
  }
  return {};
}, { urls: ["<all_urls>"] }, ["blocking"]);

async function webauthn(msg, sender) {
  const origin = sender.origin || new URL(sender.url).origin;
  // Only top-level pages and same-origin frames, so crossOrigin is always false.
  if (sender.frameId !== 0) {
    const top = sender.tab?.url ? new URL(sender.tab.url).origin : null;
    if (top !== origin) {
      return { ok: false, name: "NotAllowedError", message: "Passkeys in cross-origin frames aren't supported." };
    }
  }
  return ask({
    type: "webauthn",
    op: msg.op,
    origin,
    crossOrigin: false,
    options: msg.options,
  });
}

// Messages from the app to the pages (page-control.js): picture-in-picture on/off, and play /
// pause for YouTube's own player. The app can only answer the extension, so the extension keeps
// a line open to it and passes its messages on.
const pagePorts = new Set();
let pip = { type: "pip", on: false };

function connectApp() {
  const app = browser.runtime.connectNative("ytgecko");
  app.onMessage.addListener((msg) => {
    if (msg?.type === "pip") {
      pip = msg;
    }
    for (const port of pagePorts) {
      port.postMessage(msg);
    }
  });
  // The app may not be listening yet at startup; try again.
  app.onDisconnect.addListener(() => setTimeout(connectApp, 1000));
}
connectApp();

browser.runtime.onConnect.addListener((port) => {
  if (port.name !== "ytdead-page") {
    return;
  }
  pagePorts.add(port);
  port.onDisconnect.addListener(() => pagePorts.delete(port));
  port.postMessage(pip);
});
