// Carries passkey requests between webauthn-page.js (page world) and background.js.
"use strict";

window.addEventListener("ytgecko-webauthn-request", async (event) => {
  let req;
  try {
    req = JSON.parse(event.detail);
  } catch (e) {
    return;
  }
  let result;
  try {
    result = await browser.runtime.sendMessage({ type: "webauthn", op: req.op, options: req.options });
  } catch (e) {
    result = { ok: false, name: "NotAllowedError", message: String(e && e.message || e) };
  }
  window.dispatchEvent(new CustomEvent("ytgecko-webauthn-response", {
    detail: JSON.stringify({ id: req.id, result: result || { ok: false, name: "NotAllowedError", message: "No response." } }),
  }));
});
