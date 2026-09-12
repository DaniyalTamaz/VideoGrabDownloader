# VideoGrab Downloader (Android)

VideoGrab is a small Android browser/downloader MVP designed for ordinary, non-DRM web video.

## What works

- Built-in WebView browser.
- Watches page/resource/JavaScript requests for common media URLs.
- Detects direct `.mp4`, `.m4v`, `.webm`, `.mov`, `.3gp`, `.mkv` files.
- Detects HLS `.m3u8` playlists and downloads ordinary **unencrypted VOD** playlists.
- Picks the highest BANDWIDTH variant from a simple HLS master playlist.
- Saves HLS output to `Downloads/VideoGrab`.
- Detects DASH `.mpd` manifests (v1 does not merge DASH audio/video tracks).
- Appears in Android's **Share** menu, so Chrome can use **Share → VideoGrab**.
- Can also appear as an app choice for web links.
- Passes current WebView cookies, User-Agent and Referer to downloads where possible.
- Basic local download history.

## Important limitations

A normal Android app cannot silently inspect all encrypted HTTPS media traffic playing inside the separate Chrome app. For reliable detection, share/open the page in VideoGrab and play the video there.

VideoGrab intentionally does **not** bypass DRM, Widevine, encrypted HLS, paywalls, authentication controls, or other access protections. `blob:`/MediaSource-only pages may not expose a directly downloadable URL. Live HLS is not supported by v1. Only download media you are permitted to save.

## Build with Android Studio

Current project versions when created (10 Sep 2026):

- Android Gradle Plugin: 9.4.0
- Gradle: 9.6.0
- compileSdk / targetSdk: 36
- minSdk: 29 (Android 10+)
- Java: 17

1. Install current Android Studio.
2. Open the `VideoGrabDownloader` folder.
3. Let Gradle Sync finish and install Android SDK 36 if prompted.
4. Select **Build → Build APK(s)** (or **Build → Generate App Bundles or APKs → Generate APKs**, depending on Android Studio UI).
5. The debug APK is normally generated under `app/build/outputs/apk/debug/app-debug.apk`.
6. Copy the APK to your Android phone and install it. Android may ask you to allow installs from the app you used to open the APK.

## Use with Chrome

1. Open the webpage in Chrome.
2. Tap **Share**.
3. Choose **VideoGrab**.
4. The page opens in VideoGrab.
5. Play the video.
6. Tap **Videos (N)** at the bottom.
7. Tap the detected MP4/HLS item to start downloading.

## Source structure

- `MainActivity.java` — browser, media URL detection, Share/Open-with integration.
- `DirectDownloader.java` — direct-file downloads using Android DownloadManager.
- `HlsDownloadService.java` — foreground unencrypted HLS VOD downloader.
- `DownloadHistoryStore.java` — lightweight history storage.
- `MediaCandidate.java` — supported media URL classification.

### Gradle wrapper note

The project includes `gradle/wrapper/gradle-wrapper.properties`. The binary wrapper JAR is not bundled in this source package; Android Studio can import/sync the Gradle project. If you specifically want command-line `./gradlew`, use Android Studio/Gradle's **wrapper** task once to generate the standard wrapper scripts/JAR.

## Cloud build without Android Studio
This project includes `.github/workflows/build-apk.yml`.
Push it to GitHub, run **Build VideoGrab APK** under the Actions tab, and download the `VideoGrab-debug-apk` artifact. No local Android Studio installation is required.
