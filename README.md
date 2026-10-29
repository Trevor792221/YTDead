# YTDead

YouTube for Android without the ads. Not affiliated with YouTube or Google.

An Android app that shows YouTube's mobile site (`m.youtube.com`, laid out like the
YouTube app) in GeckoView, Firefox's browser engine for Android.

- No browser UI: full screen YouTube with a dark theme.
- **App settings** (long-press the app icon → *Extensions & settings*, or the
  *App settings* button on YouTube's *You* page; it also opens on first launch):
  - install, update, turn on/off, remove and configure uBlock Origin, SponsorBlock and
    Return YouTube Dislike, downloaded from addons.mozilla.org
  - optional daily automatic updates
  - shortcuts to Android's autofill service and passkey provider settings
- Password managers: sign-in forms are exposed to Android's autofill framework.
- Passkeys: `navigator.credentials` is routed to Android's Credential Manager, acting as a
  browser for the page's origin (GeckoView's own passkey support only works in browsers
  Google has approved).
- Built-in ad removal (works with or without uBlock Origin):
  - strips ad data (`adPlacements`, `playerAds`, `adSlots`, ad renderers in feeds, search and
    watch-next, ad entries in Shorts) from YouTube's responses before the page reads them:
    inline `ytInitial*` data, `fetch`, XHR and `JSON.parse`
  - blocks ad servers, ad-break and mid-roll lookups, ad tracking pings and ad video streams
  - mutes, skips and fast-forwards any video ad that still plays, then restores sound and speed
  - hides leftover ad boxes
- Pages can show alerts, confirmations, `<select>` menus, HTTP sign-in and the share sheet.
- Full-screen videos go landscape; Home during a full-screen video switches to
  picture-in-picture; videos keep playing in the background.
- YouTube links (and *Share → YouTube*) can open in the app. Other links open in your browser.

The app's own helper extension (`app/src/main/assets/extensions/tweaks`) provides the
passkey bridge, ad removal, the settings button, background play and banner hiding.

## Install

Download the APK from [Releases](../../releases), open it on your phone and allow
"Install unknown apps" when Android asks.

## Build

Needs JDK 17+ and the Android SDK (platform `android-37.2`, build tools 37).

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

### Signing

The release key is not in this repository. For local builds, put it next to this README with
a `keystore.properties` file (both are ignored by git):

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, release builds are signed with the debug key, and Android won't install them over
a copy signed with the release key. Keep the key safe: every update must be signed with it.

### Releases

`.github/workflows/build.yml` builds the APK on every push. Pushing a tag such as `v2.1`
publishes a GitHub Release with the signed APK, once these repository secrets are set:
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Only `arm64-v8a` is built. For older 32-bit phones change the GeckoView dependency in
`app/build.gradle` to `geckoview-armeabi-v7a`.
