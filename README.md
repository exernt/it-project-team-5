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

The mobile app reads its Maps API key and server URL from Gradle properties.
For a PowerShell session, configure them without writing secrets into the
repository:

```powershell
$env:ORG_GRADLE_PROJECT_MAPS_API_KEY = "your-android-restricted-key"
$env:ORG_GRADLE_PROJECT_SERVER_BASE_URL = "http://10.0.2.2:8000"
```

Release builds also read `APP_LINK_HOST`, the HTTPS hostname authorized to
open links in the app. Local password-reset testing uses the
`walkingmeditation://` debug link and does not require this setting.

Users must sign in before using route, script, and narration services. The app
sends the user's short-lived JWT in the `Authorization: Bearer` header and
rotates the refresh token automatically when the access token expires.

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

### Installable AWS-connected debug APK

Use the debug variant for outdoor field testing: it is automatically signed by
the local Android debug certificate and can be installed directly. In a fresh
PowerShell session:

```powershell
cd WalkingMeditationMobileApp-main
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ORG_GRADLE_PROJECT_SERVER_BASE_URL = "https://j6qory41cc.execute-api.ap-southeast-2.amazonaws.com/dev"
$env:ORG_GRADLE_PROJECT_MAPS_API_KEY = "your-android-restricted-key"
.\gradlew.bat clean testDebugUnitTest assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

If Android Studio is installed elsewhere, point `JAVA_HOME` at its bundled JBR
25. The project currently pins the Gradle daemon toolchain to Java 25.

Create a dedicated Google key for the APK. Give it the **Android apps**
application restriction with package name `com.example.walking_meditation` and
the SHA-1 fingerprint of the certificate that signs the APK. Restrict its APIs
to **Maps SDK for Android** and **Places API (New)**. Obtain the debug SHA-1 with:

```powershell
keytool -list -v -alias androiddebugkey `
  -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -storepass android -keypass android
```

Do not put the backend Google key in the APK. Keep that separate key in AWS
Secrets Manager and restrict it to **Routes API**, **Places API (New)**,
**Elevation API**, and **Weather API**. A future release-signed APK needs a
separate Android-key restriction entry using the release certificate SHA-1.

## Server setup

Requirements:
- Python 3.13 specifically. Other versions may cause dependency issues.
- Docker Desktop, for the local PostgreSQL database.

From `WalkingMeditationServer-main`:

1. Create and activate a virtual environment, then install dependencies:
   ```powershell
   python -m venv venv
   venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```
2. Copy `.env.example` to `.env`:
   ```powershell
   Copy-Item .env.example .env
   ```
   Fill in `GOOGLE_MAPS_API_KEY` and `OPENAI_API_KEY`. The `POSTGRES_*`/`DATABASE_*` defaults already match the Docker Compose setup below and don't need to change for local development. For `JWT_SECRET_KEY`, generate your own random value rather than using the placeholder:
   ```powershell
   python -c "import secrets; print(secrets.token_urlsafe(32))"
   ```
3. Ensure Docker Desktop is running, then start PostgreSQL:
   ```powershell
   docker compose up -d database
   docker compose ps
   ```
4. Apply database migrations:
   ```powershell
   alembic -c database/alembic.ini upgrade head
   ```
5. Start the FastAPI server:
   ```powershell
   uvicorn main:app --host 127.0.0.1 --port 8000 --reload --reload-exclude "venv/*"
   ```
6. In a second PowerShell window, confirm it's up:
   ```powershell
   Invoke-RestMethod http://127.0.0.1:8000/
   Invoke-RestMethod http://127.0.0.1:8000/health/database
   ```

See `READList/Procedure.md` for the full set of database commands (inspecting migration state, opening a psql console, resetting the local database, etc.).

### Server environment variables

| Variable | Purpose |
|---|---|
| `GOOGLE_MAPS_API_KEY` | Google Routes/Places/Elevation/Weather access |
| `ENABLE_PARK_FALLBACK` | Defaults to `false`; set to `true` to supplement `city_park` and `garden` results with generic `park` results |
| `OPENAI_API_KEY` | Meditation script generation |
| `DATABASE_URL` | PostgreSQL connection string (defaults match the local Docker Compose setup) |
| `JWT_SECRET_KEY` | Signs access tokens — generate a real random value per environment, never reuse the placeholder |
| `JWT_ALGORITHM` | Defaults to `HS256` |
| `ACCESS_TOKEN_TTL_MINUTES` | Access token lifetime, default 15 |
| `REFRESH_TOKEN_TTL_DAYS` | Refresh token lifetime, default 30 |
| `EMAIL_BACKEND` | `file` for ignored local `.eml` captures or `ses` for Amazon SES |
| `EMAIL_CAPTURE_DIRECTORY` | Local capture directory used by the `file` backend |
| `PASSWORD_RESET_FROM_EMAIL` | Sender identity used for password-reset email |
| `PASSWORD_RESET_REPLY_TO_EMAIL` | Optional reply-to address |
| `PASSWORD_RESET_LINK_BASE` | Absolute reset-link base; the token is added as a URL fragment |
| `PASSWORD_RESET_REQUEST_COOLDOWN_SECONDS` | Minimum interval between reset emails for one account |
| `AWS_REGION` | SES region when `EMAIL_BACKEND=ses` |
| `SES_CONFIGURATION_SET` | Optional SES configuration set for password-reset delivery events |
| `ANDROID_APP_PACKAGE_NAME` | Android package exposed through Digital Asset Links |
| `ANDROID_APP_SHA256_CERT_FINGERPRINTS` | Comma-separated release signing fingerprints for Digital Asset Links |

---

## Authentication

The server exposes account and session endpoints under `/auth`:

| Endpoint | Purpose |
|---|---|
| `POST /auth/register` | Create an account (email + password) |
| `POST /auth/login` | Exchange email + password for an access token and a refresh token |
| `POST /auth/refresh` | Exchange a refresh token for a new access/refresh pair (rotates the refresh token) |
| `POST /auth/logout` | Revoke a refresh token |
| `POST /auth/password-reset/request` | Request a single-use password-reset link; local email is captured under `var/dev-mail` |
| `POST /auth/password-reset/confirm` | Complete a password reset with the token and a new password |
| `POST /auth/register-researcher` | Upgrade the logged-in account to a researcher account pending admin verification |
| `POST /auth/admin/researchers/{user_id}/verify` | Verify a researcher account (admin only) |

All authenticated account operations and all study, questionnaire, feedback,
route-generation, script-generation, and narration endpoints require the
user's bearer access token. Registration, login, password-reset, health, and
study-link redirect endpoints remain public.

### Android session and local-data security

The Android app encrypts access and refresh tokens with an AES-GCM key held by
Android Keystore. It restores a saved session at launch by validating the
access token and, when necessary, rotating the refresh token. Session
preferences and private generated files are excluded from both cloud backup
and device-to-device transfer. Release builds reject cleartext HTTP; the debug
build permits it only for local emulator development.

Logout revokes the current refresh token when the server is reachable and
immediately clears all local account, study, route, script, and generated-audio
data. Route plans are stored in Android's no-backup directory, deleted when
playback closes, and pruned after a maximum retention period of 24 hours.

Details, worked PowerShell examples, and manual test steps for each of these live in `READList/`.
The domain-free password-reset procedure is in
[`Password_Reset_Emulator_Testing.md`](WalkingMeditationServer-main/READList/Password_Reset_Emulator_Testing.md).

Account registration also accepts optional `gender` and `age` fields. Gender is
a self-described string of up to 100 characters, and age must be between 0 and
150. Existing clients may continue sending only email and password.

## Studies

Verified researchers can create studies with `POST /studies`. The request body
contains a non-empty `name` and a positive integer `duration` in days. This
endpoint requires the JWT access token returned by `/auth/login`:

```json
{
  "name": "Two-week walking meditation",
  "duration": 14
}
```

A newly created study starts in `draft` status, and a placeholder consent form
is created automatically alongside it.

The study owner can edit its name, duration, or meditation script-generation
parameters with
`POST /studies/{study_id}/edit`. The request names one editable column and its
new value. The value must use the column's JSON type (`name` and
`script_generation_parameters` are strings, while `duration` is a positive
integer):

```json
{
  "column": "duration",
  "value": 21
}
```

For example, a researcher can provide instructions that are added to every
meditation generated for current participants:

```json
{
  "column": "script_generation_parameters",
  "value": "Use short sentences and emphasize gratitude throughout."
}
```

Study IDs cannot be changed, and status changes must use the dedicated study
lifecycle endpoints below.

### Browsing studies

Any logged-in user can list studies with `GET /studies`, optionally filtered
by status, e.g. `GET /studies?status=recruiting`. A single study can be
retrieved with `GET /studies/{study_id}`.

### Study lifecycle

A study starts in `draft` and is not joinable until its owner publishes it.
The study owner moves it through its lifecycle with:

- `POST /studies/{study_id}/publish` — `draft` → `recruiting`
- `POST /studies/{study_id}/activate` — `recruiting` → `active`
- `POST /studies/{study_id}/close` — `recruiting` or `active` → `closed`

Each of these rejects the request with a conflict if the study isn't in the
expected starting status. Only the study's owner can call these — any other
user, including other verified researchers, is rejected.

Activating records the study-wide start time. The participant duration countdown
and researcher-defined script parameters begin at that transition; participants
who join while the study is recruiting can still run ordinary meditations with
their own parameters.

### Consent

Every study has a consent form, created automatically alongside it. Any
logged-in user can view it before deciding whether to join:

`GET /studies/{study_id}/consent-form`

```json
{
  "study_id": "00000000-0000-0000-0000-000000000001",
  "body_text": "Placeholder consent text — to be finalized.",
  "version": 1
}
```

The consent text is currently a placeholder pending the actual wording; the
`agrees_to_consent` requirement on joining (below) is enforced regardless.

### Joining, leaving, and rejoining

Any logged-in user can join an existing study with
`POST /studies/{study_id}/join`. The server derives the user ID from the bearer
access token. The request confirms consent with
`{"agrees_to_consent": true}`. Joining only succeeds while the study is
`recruiting` or `active`; a study that hasn't been published yet, or has been
closed, rejects the join with a conflict. A user can participate in only one
recruiting or active study at a time. They must withdraw from that study, or
wait until it is closed, before joining another. On first registration, the
server assigns the participant to the smaller of the study's control and
experimental groups, choosing control when the groups are tied.

A participant can withdraw from a study they've joined with
`POST /studies/{study_id}/leave`. This records a withdrawal timestamp rather
than deleting the registration; leaving a study twice, or leaving one you were
never registered for, is rejected. Sending another `join` request for the same
study afterward reactivates the existing registration rather than creating a
new one — the participant's original group assignment is preserved.

The Android app checks `GET /studies/current-script-parameters` before route
selection. The endpoint returns configuration only for a non-withdrawn `active`
study. Its parameters are cached locally and included in subsequent
meditation-generation prompts. A recruiting or closed study, or a withdrawal,
causes this endpoint to return `participating: false`, restoring the normal
meditation-purpose input. Enrolment is checked separately, so a recruiting
participant can still open study management and leave the study.

### Groups and questionnaires

The verified study owner can retrieve the current active groups with
`POST /studies/{study_id}/getstatus`. Each participant entry contains the user
ID and the optional demographics stored on that account:

```json
{
  "name": "Two-week walking meditation",
  "duration": 14,
  "groups": {
    "control": [
      {"user_id": "00000000-0000-0000-0000-000000000001", "gender": "woman", "age": 28}
    ],
    "experimental": []
  }
}
```

The owner can move any registered user to another configured group with
`POST /studies/{study_id}/swapgroup`:

```json
{
  "user_id": "00000000-0000-0000-0000-000000000001",
  "target_group": "experimental"
}
```

Questionnaires are reusable researcher-owned definitions. A verified researcher
creates a draft with `POST /questionnaires`. Researchers can list their own
definitions with `GET /questionnaires` and retrieve one with
`GET /questionnaires/{questionnaire_id}`. Before publication, the owner can
replace the complete draft definition with
`PUT /questionnaires/{questionnaire_id}`. Published definitions are immutable.

A study owner opens a new questionnaire round for participants with
`POST /studies/{study_id}/questionnaires`:

```json
{
  "questionnaire_id": "00000000-0000-0000-0000-000000000001"
}
```

The questionnaire must be owned by the same researcher and the study must be
active. When the definition is still a draft, this request publishes it and
creates its first issuance in one database transaction. Any failure rolls both
changes back. Calls for an already-published questionnaire create a new round
number for that `(study, questionnaire)` pair, allowing the same questionnaire
to be sent repeatedly without mixing response sets.

`GET /studies/mine` includes an `open_questionnaires` array for each current
study. Participants may also list open rounds with
`GET /studies/{study_id}/questionnaires`, then retrieve a round's complete
definition with `GET /studies/{study_id}/questionnaires/{issuance_id}`. Answers
are submitted once per participant per round to
`POST /studies/{study_id}/questionnaires/{issuance_id}/responses`.

Only active participants in the named study can retrieve participant-facing
questionnaires or submit answers. Study owners can revoke a round and retrieve
its responses. See [`questionnaire.md`](questionnaire.md) for the questionnaire
JSON and answer formats, lifecycle, and endpoint examples.

## Server tests

After preparing the server development environment, run its credential-free unit tests from
`WalkingMeditationServer-main`:

```powershell
$env:PYTHONPATH = (Resolve-Path .).Path
python -m unittest discover -s tests -v
```

## Manual route-generation check

Live Google integration is intentionally tested manually; automated tests use
mock clients and do not require an API key or consume Maps quota.

### Preparation

1. In Google Cloud, enable **Maps SDK for Android**, **Places API (New)**, and
   **Routes API**. Enable Elevation and Weather as well if testing the current
   script generator.
2. Use an Android-restricted key for the app (package name and debug/release
   SHA-1 certificate restrictions) and a separate server-side key restricted
   to the APIs called by the server.
3. Set `GOOGLE_MAPS_API_KEY` and `ENABLE_PARK_FALLBACK=false` in the server
   `.env`, then start FastAPI. Set the Android `MAPS_API_KEY` and
   `SERVER_BASE_URL` Gradle properties as described above.
4. Use an Android emulator with a configured location, or a physical device
   that can reach the server. A physical device should use a reachable HTTPS
   endpoint; the debug emulator can use `http://10.0.2.2:8000`.

### Main flow

1. Launch the app, choose **Explore**, and grant location permission. Confirm
   that the map centres on the device location without filling the Source
   field automatically.
2. Press **Use current location**. Confirm that Source becomes “Current
   location” and a start marker appears.
3. Search for a destination, choose a Places suggestion, and confirm that its
   marker appears and the map frames both endpoints. Also verify that manually
   editing the selected text invalidates the old marker until another
   suggestion is selected.
4. Enter a time limit and meditation purpose, then press **Generate routes**.
   Confirm that the button displays **Loading...** and cannot be submitted
   twice.
5. Confirm that no more than three park-constrained route cards are displayed;
   the direct non-park baseline must not appear.
6. Tap each card. Confirm that the selected card is highlighted, its route and
   park marker are displayed on the map, and **Select Route** becomes enabled.
7. Press **Back**. Confirm that the source, destination, time, purpose, and map
   markers remain populated. Generate the routes again and also verify Android
   system Back from the result state.
8. Select a route and press **Select Route**. Confirm the button displays
   **Loading...** and the next screen displays the server response. `{}` and an
   empty response are valid for this temporary screen.

### Failure and configuration cases

1. Deny location permission. Confirm autocomplete remains usable and **Use
   current location** explains that permission is required.
2. Choose endpoints whose direct walking time exceeds a very short limit.
   Confirm the app reports that the direct walk exceeds the limit and remains
   on the input state.
3. Choose a route corridor with no qualifying city-park or garden route.
   Confirm the app reports that no urban-park route fits rather than offering
   the baseline.
4. Stop the server and retry route generation, then script generation. Confirm
   each error leaves the current inputs or selected route available for retry.
5. Test fallback disabled, then set `ENABLE_PARK_FALLBACK=true`, restart the
   server, and repeat the same search. With fallback disabled, returned places
   must have `primary_type` `city_park` or `garden`; with it enabled, generic
   `park` candidates may supplement those results.
