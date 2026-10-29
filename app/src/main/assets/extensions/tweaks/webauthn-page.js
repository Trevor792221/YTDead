// Runs in the page itself. Replaces navigator.credentials.create()/get() for passkeys with
// versions that go through the app (webauthn-bridge.js -> background.js -> Android Credential
// Manager), because GeckoView's own passkey support only works in Google-approved browsers.
(() => {
  "use strict";
  if (!window.isSecureContext || !navigator.credentials || !window.PublicKeyCredential) {
    return;
  }

  const container = navigator.credentials;
  const nativeCreate = container.create.bind(container);
  const nativeGet = container.get.bind(container);
  const pending = new Map();
  let nextId = 1;

  window.addEventListener("ytgecko-webauthn-response", (event) => {
    let msg;
    try {
      msg = JSON.parse(event.detail);
    } catch (e) {
      return;
    }
    const entry = pending.get(msg.id);
    if (entry) {
      pending.delete(msg.id);
      entry(msg.result);
    }
  });

  // --- base64url <-> bytes ---------------------------------------------------

  function toB64(source) {
    const bytes = source instanceof ArrayBuffer
      ? new Uint8Array(source)
      : new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
    let s = "";
    for (let i = 0; i < bytes.length; i++) {
      s += String.fromCharCode(bytes[i]);
    }
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function fromB64(s) {
    s = s.replace(/-/g, "+").replace(/_/g, "/");
    s += "===".slice((s.length + 3) % 4);
    const bin = atob(s);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    return bytes.buffer;
  }

  function isBuffer(v) {
    return v instanceof ArrayBuffer || ArrayBuffer.isView(v);
  }

  // Options -> the JSON form of WebAuthn (every BufferSource becomes base64url).
  function toJSONValue(v) {
    if (isBuffer(v)) {
      return toB64(v);
    }
    if (Array.isArray(v)) {
      return v.map(toJSONValue);
    }
    if (v && typeof v === "object") {
      const out = {};
      for (const key of Object.keys(v)) {
        const value = v[key];
        if (value !== undefined && typeof value !== "function") {
          out[key] = toJSONValue(value);
        }
      }
      return out;
    }
    return v;
  }

  // --- results -> PublicKeyCredential-like objects -----------------------------

  function define(obj, props) {
    for (const [key, value] of Object.entries(props)) {
      Object.defineProperty(obj, key, { value, enumerable: true, configurable: true });
    }
    return obj;
  }

  function extensionResults(json) {
    const results = json.clientExtensionResults ? structuredClone(json.clientExtensionResults) : {};
    const prf = results.prf && results.prf.results;
    if (prf) {
      for (const k of ["first", "second"]) {
        if (typeof prf[k] === "string") {
          prf[k] = fromB64(prf[k]);
        }
      }
    }
    if (results.largeBlob && typeof results.largeBlob.blob === "string") {
      results.largeBlob.blob = fromB64(results.largeBlob.blob);
    }
    return results;
  }

  function makeCredential(json, op) {
    const r = json.response;
    let response;
    if (op === "create") {
      response = Object.create(AuthenticatorAttestationResponse.prototype);
      define(response, {
        clientDataJSON: fromB64(r.clientDataJSON),
        attestationObject: fromB64(r.attestationObject),
        getTransports: () => (r.transports || []).slice(),
        getAuthenticatorData: () => (r.authenticatorData ? fromB64(r.authenticatorData) : new ArrayBuffer(0)),
        getPublicKey: () => (r.publicKey ? fromB64(r.publicKey) : null),
        getPublicKeyAlgorithm: () => (typeof r.publicKeyAlgorithm === "number" ? r.publicKeyAlgorithm : -7),
      });
    } else {
      response = Object.create(AuthenticatorAssertionResponse.prototype);
      define(response, {
        clientDataJSON: fromB64(r.clientDataJSON),
        authenticatorData: fromB64(r.authenticatorData),
        signature: fromB64(r.signature),
        userHandle: r.userHandle ? fromB64(r.userHandle) : null,
      });
    }
    const credential = Object.create(PublicKeyCredential.prototype);
    define(credential, {
      id: json.id,
      rawId: fromB64(json.rawId || json.id),
      type: "public-key",
      authenticatorAttachment: json.authenticatorAttachment || "platform",
      response,
      getClientExtensionResults: () => extensionResults(json),
      toJSON: () => structuredClone(json),
    });
    return credential;
  }

  // --- the replacements -----------------------------------------------------------

  function request(op, publicKey, signal) {
    return new Promise((resolve, reject) => {
      if (signal && signal.aborted) {
        reject(signal.reason ?? new DOMException("The operation was aborted.", "AbortError"));
        return;
      }
      const id = nextId++;
      const onAbort = () => {
        pending.delete(id);
        reject(signal.reason ?? new DOMException("The operation was aborted.", "AbortError"));
      };
      if (signal) {
        signal.addEventListener("abort", onAbort, { once: true });
      }
      pending.set(id, (result) => {
        if (signal) {
          signal.removeEventListener("abort", onAbort);
        }
        if (result && result.ok) {
          try {
            resolve(makeCredential(result.credential, op));
          } catch (e) {
            reject(new DOMException("Invalid passkey response.", "UnknownError"));
          }
        } else {
          const name = (result && result.name) || "NotAllowedError";
          const message = (result && result.message) || "The request was not allowed.";
          reject(name === "TypeError" ? new TypeError(message) : new DOMException(message, name));
        }
      });
      let options;
      try {
        options = toJSONValue(publicKey);
      } catch (e) {
        pending.delete(id);
        reject(new TypeError("Invalid passkey options."));
        return;
      }
      window.dispatchEvent(new CustomEvent("ytgecko-webauthn-request", {
        detail: JSON.stringify({ id, op, options }),
      }));
    });
  }

  function replace(obj, name, fn) {
    Object.defineProperty(obj, name, { value: fn, writable: true, configurable: true });
  }

  replace(container, "create", function create(options) {
    if (!options || !options.publicKey) {
      return nativeCreate(options);
    }
    return request("create", options.publicKey, options.signal);
  });

  replace(container, "get", function get(options) {
    if (!options || !options.publicKey) {
      return nativeGet(options);
    }
    if (options.mediation === "conditional") {
      // Passkey suggestions inside the username field aren't supported; behave like a
      // browser that never shows any.
      return new Promise((resolve, reject) => {
        options.signal?.addEventListener("abort", () =>
          reject(options.signal.reason ?? new DOMException("The operation was aborted.", "AbortError")));
      });
    }
    return request("get", options.publicKey, options.signal);
  });

  const PKC = window.PublicKeyCredential;
  replace(PKC, "isUserVerifyingPlatformAuthenticatorAvailable", () => Promise.resolve(true));
  replace(PKC, "isConditionalMediationAvailable", () => Promise.resolve(false));
  replace(PKC, "getClientCapabilities", () => Promise.resolve({
    conditionalCreate: false,
    conditionalGet: false,
    hybridTransport: true,
    passkeyPlatformAuthenticator: true,
    userVerifyingPlatformAuthenticator: true,
    relatedOrigins: false,
    signalAllAcceptedCredentials: false,
    signalCurrentUserDetails: false,
    signalUnknownCredential: false,
  }));
})();
