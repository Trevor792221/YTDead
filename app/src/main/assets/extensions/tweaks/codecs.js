// Runs in the page. Offers YouTube only the video formats this device decodes in hardware, so
// it never picks one (usually AV1, on TVs sometimes VP9) that would have to be decoded by the
// processor: that stutters on TVs and drains phone batteries. YouTube then streams VP9 or
// H.264 instead. Set by prefs.js from the app: data-ytdead-av1 / data-ytdead-vp9 = "0" when
// there's no hardware decoder.
(() => {
  "use strict";
  const html = document.documentElement;
  function blocked(type) {
    const t = String(type || "").toLowerCase();
    if (html.getAttribute("data-ytdead-av1") === "0" && /av01/.test(t)) {
      return true;
    }
    if (html.getAttribute("data-ytdead-vp9") === "0" && /vp0?9/.test(t)) {
      return true;
    }
    return false;
  }

  if (window.MediaSource && MediaSource.isTypeSupported) {
    MediaSource.isTypeSupported = new Proxy(MediaSource.isTypeSupported, {
      apply(target, thisArg, args) {
        return blocked(args[0]) ? false : Reflect.apply(target, thisArg, args);
      },
    });
  }

  HTMLMediaElement.prototype.canPlayType = new Proxy(HTMLMediaElement.prototype.canPlayType, {
    apply(target, thisArg, args) {
      return blocked(args[0]) ? "" : Reflect.apply(target, thisArg, args);
    },
  });

  const mc = navigator.mediaCapabilities;
  if (mc && mc.decodingInfo) {
    const original = mc.decodingInfo.bind(mc);
    Object.defineProperty(mc, "decodingInfo", {
      configurable: true,
      writable: true,
      value: function decodingInfo(config) {
        if (config && config.video && blocked(config.video.contentType)) {
          return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
        }
        return original(config);
      },
    });
  }
})();
