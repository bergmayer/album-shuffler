# Album Shuffler for Android

Native Kotlin / Jetpack Compose companion to the Apple app. Requires Android 8.0 or later, an ARM device, Apple Music sign-in, and an active Apple Music subscription. No widget or watch app is included.

## Features

- Entire Library or an Apple Music library playlist as the source, with complete pagination.
- **Playlist Subset:** picks an album and queues only its playlist tracks, in disc/track order. Catalog matching cannot silently add or drop playlist tracks.
- **Complete Album:** resolves an album in the Apple Music catalog and opens that exact album in the Apple Music app. Tap Play there; Apple Music owns playback and listening history.
- **Open random album** (Complete Album), **Play random album** (Playlist Subset), and **Choose another**, avoiding repeats while alternatives remain. Failed launches/playback are not recorded as successful picks. An album opened externally counts as a pick; the app cannot confirm whether you played it.
- Saved source and mode, a refresh action, artwork and metadata, and an Apple Music shortcut. Playlist Subset also provides previous/play/pause/next controls.
- For Playlist Subset: foreground playback service, media notification and lock-screen/headset controls, audio focus, and pause when headphones disconnect. Complete Album uses Apple Music’s controls.
- Separate portrait and landscape layouts, light/dark appearance, scrolling for large text, and accessible control labels.
- `albumshuffler://shuffle` opens the saved source/mode and starts a pick after initialization.

## Build

Install JDK 17 and Android SDK platform 37. Set `ANDROID_HOME` and `JAVA_HOME`, or configure the SDK location in Android Studio's `local.properties`. Open this directory in Android Studio.

1. Download [Apple's MusicKit SDK](https://developer.apple.com/download/all/?q=Android%20MusicKit), extract it, and run:

   ```sh
   python3 tools/install_musickit.py /path/to/Android-MusicKit-SDK-1.1.2
   ```

2. Register a Media ID with MusicKit enabled and an associated Media Services key in Apple Developer. Keep the downloaded `.p8` private key outside this repository. It is used only on the development machine or a server; it is never bundled into the APK.

3. For a personal build, generate a developer token:

   ```sh
   python3 -m venv .venv
   .venv/bin/pip install -r tools/requirements.txt
   .venv/bin/python tools/make_developer_token.py \
     --private-key /private/path/AuthKey_KEYID.p8 \
     --team-id YOUR_TEAM_ID --key-id YOUR_KEY_ID
   ```

   This writes the ignored, mode-0600 `musickit.properties`. Its developer token expires after 180 days. Renew it and rebuild before expiration. The script refuses to overwrite existing configuration; rename the old file before renewal.

   For distributed builds, configure `tokenUrl=https://your-server.example/musickit-token` instead. The endpoint must return `{"developerToken":"<signed JWT>"}` over HTTPS. Sign tokens on your server; never ship the `.p8` key. No Music User Token is sent to this endpoint. The app fetches a fresh developer token before API/playback operations when its cached token expires.

4. Build and install:

   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. Tap **Connect to Apple Music** and approve access in Apple Music. The application's Music User Token is stored using Android Keystore encryption. Apple's playback SDK also maintains an app-private token cache. Both are excluded from backups and cleared on disconnect, which also stops playback.

`assembleRelease` creates an unsigned optimized release APK. Configure your own Android signing key before distributing release builds.

## Validation

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

Unit tests cover pagination, credential destination restrictions, error propagation, catalog matching, disc order, subset integrity, repeat cycles, empty sources and cancellation. Device tests exercise the real SDK's native initialization and render portrait, phone landscape, tablet landscape and enlarged text. Test fixtures are confined to the test APK; the application has no demo playback mode.

Authenticated streaming must also be checked on a signed-in ARM Android device: complete/subset playback through a track transition, choose another, screen-off playback, lock-screen/headset controls, interruption and resume. Emulator initialization or compilation alone does not establish successful DRM playback.

## Apple Music authorization hangs

As of September 9, 2026, developers report an Apple Music 6.5.1/6.5.2 regression: when the Apple Music app is already signed in, its authorization screen can spin indefinitely without returning a result. See [Apple Developer Forums, thread 838633](https://developer.apple.com/forums/thread/838633). This is not resolved by a valid developer token or by rebuilding Album Shuffler.

The reported workaround is to press Back, sign out inside Apple Music, then return to Album Shuffler and start **Connect to Apple Music** again. Complete sign-in and consent from that flow. Signing out may remove downloaded music from the device. Album Shuffler exposes these steps under **Sign-in stuck?**, and distinguishes subscription/token errors from an incomplete sign-in. This workaround still needs verification on the user's tablet; simulated callback tests do not establish successful account authorization.

## Android platform differences

Complete Album sends the catalog album URL directly to the installed Apple Music app, with no embedded-player or browser fallback. Playback controls live in Apple Music; Album Shuffler shows the selected album without claiming it is playing. Apple does not expose a system-player controller in its Android SDK, so automatic playback and Recently Played updates must be verified on a signed-in device. Opening an album alone does not record a play.

Playlist Subset uses MusicKit's independent player inside Album Shuffler to preserve the exact subset and album order. That mode does not control Apple Music's queue and cannot guarantee an album entry in Recently Played.

The published Android SDK accepts catalog identifiers for playback. Local-only/uploaded songs without a matching playable catalog version cannot be queued. An unavailable album is skipped; an unavailable subset is never silently broadened or partially queued. If no eligible album remains, the app explains the limitation.

Apple's current downloadable SDK is 1.1.2 (authentication 1.1.2, playback 1.1.1). It ships ARM native libraries from 2020 and no x86 libraries. Device compatibility, including 16 KB page-size devices and store submission requirements, requires testing against Apple's supplied native binary. The build includes AndroidX compatibility and current manifest declarations without altering the vendor SDK.

## Sources

- [MusicKit for Android](https://developer.apple.com/musickit/)
- [Android MusicKit API reference](https://developer.apple.com/musickit/android/overview-summary.html)
- [Apple Music user authentication](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit)
- [Generating developer tokens](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens)
- [Android foreground media playback](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
