# YTDead

YouTube for Android without the ads. Not affiliated with YouTube or Google.

An Android app that shows YouTube's mobile site (`m.youtube.com`, laid out like the
YouTube app) in GeckoView, Firefox's browser engine for Android.

- No browser UI: full screen YouTube with a dark theme.
- Android TV: shows up on the TV home screen and opens YouTube's own TV interface
  (`youtube.com/tv`), driven by the remote. Back goes back (and the home screen offers Exit),
  Menu opens App settings, and a separate *YTDead settings* entry is on the TV home screen. Sign
  in with the code shown on screen at `yt.be/activate`. Ad removal, extensions and highest
  quality apply as on phones; the Music tab and pop-up window are phone-only.
- Two tabs, **YouTube** and **Music** (YouTube Music), switched from the bar at the top. Each
  keeps its own page and history; both share sign-in, extensions, ad removal, background
  playback and the media notification. Starting playback in one tab pauses the other. Links to
  the other site open in its tab; Back on the Music tab's first page returns to YouTube.
  Long-press the app icon for a *Music* shortcut.
- Crossfade on the Music tab for non-stop playback: a few seconds before a song ends, the next
  song in the queue starts in a hidden second YouTube Music player and the two are faded across
  each other; YouTube Music then moves on to that song and takes over from the hidden player.
  Length (3-12 seconds) or off in App settings. Falls back to a plain fade if the second
  player isn't ready; seeking back, skipping, ads and repeat-one cancel it.
- **App settings** (long-press the app icon → *Extensions & settings*, or the
  *App settings* button on YouTube's *You* page; it also opens on first launch):
  - install, update, turn on/off, remove and configure uBlock Origin, SponsorBlock,
    Return YouTube Dislike and (optionally) Bitwarden, downloaded from addons.mozilla.org;
    *Open* shows an extension's pop-up full screen (uBlock Origin's per-page controls, the
    Bitwarden vault)
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
- Smooth playback on slower devices: YouTube is only offered video formats the device decodes
  in hardware (no software AV1/VP9), and "highest quality" stops at the screen's resolution.
- Text keeps the size YouTube's pages are laid out for, so it doesn't run over icons. On phones,
  *Use phone's text size* in App settings applies the phone's font size instead.
- Extensions can't open pages by themselves (welcome or "what's new" pages after installing or
  updating); links you open from an extension's own pages still work.
- Pages can show alerts, confirmations, `<select>` menus, HTTP sign-in and the share sheet.
- Highest video quality: each video switches to the best quality offered (Premium-only
  levels are skipped; a quality you pick yourself is kept). Can be turned off in App settings.
- Background playback with a media notification: play/pause, previous/next (or back/forward
  10 seconds when there is no next video), a seek bar, lock-screen controls, headset buttons,
  and pausing when headphones are unplugged or a call comes in. Can be turned off in App settings.
- Full-screen videos go landscape; Home during a full-screen video switches to
  picture-in-picture.
- YouTube links (and *Share → YouTube*) can open in the app. Other links open in your browser.

The app's own helper extension (`app/src/main/assets/extensions/tweaks`) provides the
passkey bridge, ad removal, the settings button, background play and banner hiding.

## Install

Download an APK from [Releases](../../releases), open it on your phone or TV and allow
"Install unknown apps" when Android asks:

- `YTDead-<version>-arm64.apk`: most phones and tablets, and newer TVs
- `YTDead-<version>-arm32.apk`: 32-bit Android, which includes most Android TV devices
  (Chromecast with Google TV, Fire TV, Mi Box) and older phones

If one says the app isn't compatible with your device, use the other.

## Build

Needs JDK 17+ and the Android SDK (platform `android-37.2`, build tools 37).

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/arm64/release/app-arm64-release.apk
#    app/build/outputs/apk/arm32/release/app-arm32-release.apk
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

Two APKs are built, one per processor type (`arm64-v8a` and `armeabi-v7a`), because GeckoView
ships a separate engine build for each.
