# Tuned: source

Two apps: `app/` (Tuned, the TV player) and `phone/` (Tuned Remote, the phone companion).

Kotlin, Compose for TV, Media3. One universal APK (Android 6.0+, all CPU types) for Shield, Google TV and Fire TV.

**Using the app, installing on Shield / Google TV / Fire TV, and downloads:** see the [user guide](../README.md).

## Signing
The release key is deliberately **not** in this public repo: anyone holding it could publish an "update" that installed copies would accept. Official builds are signed with:
```
export TUNED_KEYSTORE=/path/to/tuned-sideload.jks TUNED_KEYSTORE_PASSWORD=…
```
Without those variables, release builds use your local debug key. They run fine but can't install over an official build (uninstall first).

## Build
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```
`ScreenshotTest` renders the screens with fake data into `app/build/screenshots/` (Robolectric, no device needed).

## Layout
- `data/`: Xtream API client and lenient parsing, cached channel list, now/next EPG cache, preferences
- `player/`: Media3 setup, frame-rate measurement, display-mode choice (`FrameRates`, unit-tested), `RefreshRateMatcher`
- `remote/`: phone-remote server on the TV: a tiny local-network HTTP server, mDNS announcement (`_tuned._tcp`), pairing codes and tokens (`RemoteApi` documents the endpoints)
- `data/guide/`: TV guide. Streaming XMLTV reader, layered loader (provider guide, then epgshare01 country guides), SQLite store per profile, sports recogniser (`SportsCatalog`, tested on real Foxtel / Sky NZ / TSN listings)
- `remote/ChannelMatcher.kt`: matches guide channel names to provider channels (indexed for big lineups; numbers and countries must agree)
- `phone/.../sports/`: Sports tab: matches from the TV's guide, saved on the phone for when the TV is off
- `phone/`: Tuned Remote. Finds the TV over mDNS, pairs with the code, lists channels and guide, changes channel
- `data/M3u.kt`: M3U/M3U8 playlist parser · `data/NetworkDoctor.kt`: internet / provider / account checks for the buffering panel
- `data/Prefs.kt`: app settings, profiles, and per-profile state (favourites, groups, recents)
- `data/Backup*.kt`: backup contents, passphrase encryption (PBKDF2 → AES-256-GCM), Download/Tuned storage
- `ui/`: Login, Home (groups + channels over live video), Player (info bar, recents, channel list), Groups, channels-in-a-group, Settings, Backup, themes

Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, run `./gradlew testDebugUnitTest assembleRelease` with the signing variables set, copy `app-release.apk` to `../releases/tuned-x.y.z.apk` and `tuned-latest.apk`, and `phone-release.apk` to `tuned-remote-x.y.z.apk` and `tuned-remote-latest.apk`, add the `.sha256`, and update the version in the user guide.
