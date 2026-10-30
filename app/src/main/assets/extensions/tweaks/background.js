"use strict";

// Relays requests from content scripts to the app. The page's origin comes from the
// sender, which the page can't fake, never from the message itself.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg?.type === "openSettings" || msg?.type === "getPrefs") {
    return browser.runtime.sendNativeMessage("ytgecko", { type: msg.type });
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
  return browser.runtime.sendNativeMessage("ytgecko", {
    type: "webauthn",
    op: msg.op,
    origin,
    crossOrigin: false,
    options: msg.options,
  });
}
