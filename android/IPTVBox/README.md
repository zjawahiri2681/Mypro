# IPTV Box (Android)

Personal IPTV player for Android phones, tablets and TV boxes (Android 7.0+), built with
Kotlin, Jetpack Compose and AndroidX Media3 (ExoPlayer).

## What it does

**Sources**
- M3U / M3U8 playlists from a URL or a local file (gzip supported). Reads `tvg-id`, `tvg-logo`,
  `group-title`, `tvg-chno`, `catchup*`, `#EXTGRP`, `#EXTVLCOPT` headers and `url-tvg` EPG links.
- Xtream Codes API logins (server + username + password): live categories, movies, series with
  seasons/episodes, short EPG and catch-up/timeshift. A `get.php` M3U link is upgraded to the API automatically.
- Stalker / MAG portals (experimental): handshake, channel list and link creation.
- Several playlists can be stored; one is active at a time.

**Live TV like a set-top box**
- Groups, favourites, recents, search, channel numbers, number keys for direct zapping.
- D-pad up/down zaps, OK opens the channel list, left/right opens the programme guide, MENU opens options,
  CHANNEL ± and media keys are honoured. Touch controls exist for phones.
- XMLTV programme guide (now/next + progress, full day list) with catch-up playback for channels that offer it.
- Auto-reconnect when a live stream drops, buffer size presets, hardware/software decoder choice,
  tunnelled playback, aspect-ratio modes, audio/subtitle track selection, playback speed for VOD,
  picture-in-picture, stream information, "open in another player".

**Watch together**
- From the player: Options → Watch together → "Over the internet" or "Same Wi-Fi".
- Share the `iptvbox://join?d=…` link or scan the QR code. Opening the link on another device running
  IPTV Box joins the party; the link can also be pasted under *Watch together*.
- Internet mode relays messages through a public MQTT broker; every message is AES-GCM encrypted with
  the key that lives only inside the link. Wi-Fi mode uses a direct TCP connection and needs no internet.
- Works for streams, URLs and local video files (each side picks the same file). Anyone can play, pause
  or seek; followers stay within ~0.25 s via speed nudging and re-seek on larger drift.

**Open anything**
- *Open URL / file* plays any HTTP(S)/RTSP/UDP stream or local file; the app registers as a video
  player so browsers, file managers and messaging apps can hand media to it.

## Project layout

```
android/IPTVBox
├── core/   pure-JVM library: models, M3U parser, XMLTV parser + EPG index, Xtream & Stalker clients,
│           watch-together protocol (messages, link codec, crypto, LAN + MQTT transports, sync maths). Unit tested.
└── app/    Android app: data layer (JSON persistence, settings), Compose UI, Media3 player, watch session glue.
```

## Building

The APK is built by GitHub Actions (`.github/workflows/android-build.yml`) on every push touching this folder
and published as a GitHub Release (`IPTVBox-vX.Y.Z-buildN.apk`) plus a workflow artifact.

Local build (needs Android SDK 35 and JDK 17):

```bash
cd android/IPTVBox
./gradlew :core:test            # unit tests for the parsers and sync protocol
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/
```

Only the pure-JVM core can be built on a machine without Google's Maven repository:
`IPTVBOX_CORE_ONLY=1 ./gradlew :core:test`.

### Signing

Release builds are signed with the key given through `IPTVBOX_KEYSTORE`, `IPTVBOX_KEYSTORE_PASSWORD`,
`IPTVBOX_KEY_ALIAS`, `IPTVBOX_KEY_PASSWORD` (environment) or a `keystore.properties` file; without one, the
debug key is used so the APK still installs. In CI, add the repository secrets `IPTVBOX_KEYSTORE_BASE64`
(base64 of the `.jks`) and `IPTVBOX_KEYSTORE_PASSWORD`; when they are missing, the workflow generates a key,
uses it, and uploads it as the `signing-key` artifact so it can be saved as those secrets. Keep the same key
to install later builds as updates instead of uninstall/reinstall.

## Installing on a TV box

1. Download the APK from the release page onto a USB stick, or use the "Downloader" app with the release URL.
2. Settings → Security (or Apps → Special access) → allow installs from the file manager/Downloader.
3. Open the APK and install. The app appears in the Android TV launcher (banner provided) and on phones.
4. Playlists → Add playlist → M3U link, Xtream login or portal → Test → Save. Live TV fills in; the guide loads in the background.

## Notes

- The app ships no channels or content: it plays only what the user's own playlists, servers and files provide.
- Minification is disabled for this personal build to keep behaviour predictable; the APK is larger than it needs to be.
- Stalker portal support is best-effort; portals differ widely.
