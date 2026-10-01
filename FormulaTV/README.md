# Formula TV 0.4.0 — testing build

Original Android TV and phone media player with a purple interface inspired by MYTVOnline+ navigation. This is a separate application (`com.formulatv.player`). It is not affiliated with Formuler or MYTVOnline+.

## Features

- Multiple saved sources: Xtream Codes server/user/password and Stalker portal URL/MAC.
- Live TV list with preview/fullscreen, Movies and Series poster grids, season/episode labels, channel search and favorites.
- TV remote focus borders, OK to expand a playing live channel, Back to return; touch layouts and phone bottom navigation.
- Provider EPG/catch-up where available, favorite and recently watched items, VOD resume, group pinning/hiding, local parental PIN and provider protected-content handling.
- Media3 HLS/TS/DASH/RTSP playback; optional Android PiP where supported; saved sources and media metadata encrypted with Android Keystore.
- Android 7+; no channels, subscriptions, real portal credentials or customer data are bundled.

## Testing

Install the testing APK, choose Add content source, select Xtream or Stalker, enter your authorized source details, and choose Save & connect. Register the displayed MAC with the Stalker provider or replace it with your authorized MAC. Test live, movies, series, switching channels, remote focus and Back/fullscreen behavior on your actual devices.

The build workflow compiles the APK, runs fixture tests for both authentication/catalog/playback flows, runs Android lint, and launches the app in an emulator. These tests do not establish compatibility with every real provider. Catch-up, guide, VOD, series, and protected content depend on what the source returns. Full feature or pixel parity with the proprietary reference app has not been established. This APK uses Android's debug signing for testing and is not a store release.

## Build

Use Android Studio or Gradle 8.11.1 with JDK 17 and Android SDK 36:

```
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The source contains a standalone project. Formula TV source is maintained on the `formula-tv-testing` branch under `FormulaTV/` in the existing app repository.


Stalker is the default source type. Auto load on launch is enabled by default and is the first option in startup and Settings. VOD loads a provider page at a time with Load more; classic VOD series support season and episode listings. Please use authorized TV providers only.

Version 0.4 probes Stalker API endpoints before forwarding pages, adjusts HTTP/HTTPS, resolves relative artwork, and adds show → season → episode navigation. Classic season/episode rows must match their returned parent IDs. The launcher icon uses Formula TV typography.
