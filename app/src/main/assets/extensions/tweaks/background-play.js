// Keep videos playing when the app is in the background or the screen is off:
// tell the page it is always visible, and hide visibility-change events from it.
"use strict";

const page = document.wrappedJSObject;
Object.defineProperty(page, "visibilityState", { value: "visible", configurable: true });
Object.defineProperty(page, "hidden", { value: false, configurable: true });
Object.defineProperty(page, "webkitVisibilityState", { value: "visible", configurable: true });
Object.defineProperty(page, "webkitHidden", { value: false, configurable: true });

for (const type of ["visibilitychange", "webkitvisibilitychange"]) {
  window.addEventListener(type, (event) => event.stopImmediatePropagation(), true);
}
