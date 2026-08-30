# Walking Meditation

Walking Meditation is an Android application backed by a FastAPI service. It
builds time-aware walking routes and generates guided meditations that respond
to the selected route, current conditions, and the user's purpose for the walk.

This repository contains:

- `WalkingMeditationMobileApp-main`: the Java Android application.
- `WalkingMeditationServer-main`: the FastAPI service for route planning,
  meditation generation, narration, and account registration.

## Android setup

Requirements:

- Android Studio with Android SDK 36.1 installed.
- JDK 17 or the JDK bundled with the current Android Studio.
- An Android 10 (API 29) or newer emulator or device.

Open `WalkingMeditationMobileApp-main` in Android Studio and let Gradle sync.
The default debug server URL is `http://10.0.2.2:8000`, the Android emulator
alias for the host computer.

The mobile app reads its API key, server URL, and optional access token from
Gradle properties. For a PowerShell session, configure them without writing
secrets into the repository:

```powershell
$env:ORG_GRADLE_PROJECT_MAPS_API_KEY = "your-android-restricted-key"
$env:ORG_GRADLE_PROJECT_SERVER_BASE_URL = "http://10.0.2.2:8000"
$env:ORG_GRADLE_PROJECT_SERVER_ACCESS_TOKEN = "your-server-access-token"
```

When server access-token protection is enabled, `SERVER_ACCESS_TOKEN` must
match the token expected by the server. The app sends it in the `X-App-Token`
header for route, script, and narration requests.

For a physical device, use an HTTPS development endpoint or a host address
reachable by the device. Release builds reject cleartext HTTP; debug builds
permit it for local emulator development.

Build and test from PowerShell:

```powershell
cd WalkingMeditationMobileApp-main
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

The Android build and local unit tests do not require credentials. With no
`MAPS_API_KEY`, route search is disabled and the app displays a configuration
message.

## Server tests

After preparing the server development environment, run its credential-free unit tests from
`WalkingMeditationServer-main`:

```powershell
$env:PYTHONPATH = (Resolve-Path .).Path
python -m unittest discover -s tests -v
```

## End-to-end check (for final tests)

With a configured server already running and reachable from the Android app:

1. Configure `MAPS_API_KEY`, `SERVER_BASE_URL`, and, when required,
   `SERVER_ACCESS_TOKEN`.
2. Launch the app and choose **Explore**.
3. Select source and destination suggestions, enter the available time and a
   meditation purpose, and request routes.
4. Choose the direct route or a park route to generate the meditation.
5. Download narration audio with the floating download button.
6. When the download completes, start playback and walk along the selected
   route.

Live integration checks require the corresponding service credentials and TTS
model dependencies.
