# YTDead

YouTube for Android without the ads. Not affiliated with YouTube or Google.

An Android app that shows YouTube's mobile site (`m.youtube.com`, laid out like the
YouTube app) in GeckoView, Firefox's browser engine for Android.

- No browser UI: full screen YouTube with a dark theme.
- Foldables and resizable windows: the app adapts when unfolded, folded or resized without
  restarting, and only turns full-screen video to landscape on phone-sized screens.
- Two tabs, **YouTube** and **Music** (YouTube Music), switched from the bar at the top. Each
  keeps its own page and history; both share sign-in, extensions, ad removal, background
  playback and the media notification. Starting playback in one tab pauses the other. Links to
  the other site open in its tab; Back on the Music tab's first page returns to YouTube.
  Long-press the app icon for a *Music* shortcut.
- Crossfade on the Music tab for non-stop playback: a few seconds before a song ends, the next
  song in the queue starts in a hidden second YouTube Music player and the two are faded across
  each other; YouTube Music then moves on to that song and takes over from the hidden player.
  Length (3-12 seconds) or off in App settings. Falls back to a plain fade if the second
  player isn't ready or you seek into the last seconds; a song you skip to fades in quickly.
  Seeking back, ads and repeat-one cancel it. App settings shows what it last did.
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
- Smooth playback: YouTube is only offered video formats the phone decodes in hardware (no
  software AV1/VP9), and "highest quality" stops at the screen's resolution.
- Text keeps the size YouTube's pages are laid out for, so it doesn't run over icons;
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
  picture-in-picture, which shows just the video with Android's play/pause and skip buttons.
- YouTube links (and *Share → YouTube*) can open in the app. Other links open in your browser.

The app's own helper extension (`app/src/main/assets/extensions/tweaks`) provides the
passkey bridge, ad removal, the settings button, background play and banner hiding.

## Versions

- **Phone** (this branch, `main`): releases tagged `v<version>`.
- **Android TV** (the [`tv` branch](../../tree/tv)): YouTube's TV interface for remotes; releases
  tagged `tv-v<version>`, APKs named `YTDead-TV-<version>-arm32.apk` / `-arm64.apk`.

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

## License

Copyright (C) 2026 Trevor792221

YTDead is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, version 3 of the License
(see [LICENSE](LICENSE)). It is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
PURPOSE.

**Additional permission under GNU GPL version 3 section 7:** If you modify this Program, or any
covered work, by linking or combining it with Google Play services or other Google libraries
distributed under the Android Software Development Kit License (or a modified version of those
libraries), containing parts covered by the terms of that license, the licensors of this Program
grant you additional permission to convey the resulting work.

Third-party components keep their own licenses: GeckoView is under the Mozilla Public License
2.0, AndroidX, Guava, the Kotlin libraries, JSpecify and SnakeYAML under the Apache License 2.0.
The full list is in the app under Settings → Open-source licenses. YTDead is not affiliated with
Google, YouTube or Mozilla.
