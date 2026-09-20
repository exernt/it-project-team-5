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
| `OPENAI_API_KEY` | Meditation script generation |
| `APP_API_TOKEN` | Optional shared secret checked against the mobile app's `X-App-Token` header (see [Authentication](#authentication) below) |
| `DATABASE_URL` | PostgreSQL connection string (defaults match the local Docker Compose setup) |
| `JWT_SECRET_KEY` | Signs access tokens — generate a real random value per environment, never reuse the placeholder |
| `JWT_ALGORITHM` | Defaults to `HS256` |
| `ACCESS_TOKEN_TTL_MINUTES` | Access token lifetime, default 15 |
| `REFRESH_TOKEN_TTL_DAYS` | Refresh token lifetime, default 30 |

---

## Authentication

The server exposes account and session endpoints under `/auth`:

| Endpoint | Purpose |
|---|---|
| `POST /auth/register` | Create an account (email + password) |
| `POST /auth/login` | Exchange email + password for an access token and a refresh token |
| `POST /auth/refresh` | Exchange a refresh token for a new access/refresh pair (rotates the refresh token) |
| `POST /auth/logout` | Revoke a refresh token |
| `POST /auth/password-reset/request` | Request a password reset token (emailed in production; logged to the server console in local development) |
| `POST /auth/password-reset/confirm` | Complete a password reset with the token and a new password |
| `POST /auth/register-researcher` | Upgrade the logged-in account to a researcher account pending admin verification |
| `POST /auth/admin/researchers/{user_id}/verify` | Verify a researcher account (admin only) |

This is separate from the `APP_API_TOKEN`/`X-App-Token` mechanism described in the Android setup section above — `X-App-Token` is a single shared secret gating access to the app's own routing/script/narration endpoints, unrelated to which user is logged in. Bearer login is currently used for researcher registration, admin verification, and study creation; the routing/script/narration endpoints are not associated with a logged-in user.

Details, worked PowerShell examples, and manual test steps for each of these live in `READList/`.

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

The study owner can edit its name or duration with
`POST /studies/{study_id}/edit`. The request names one editable column and its
new value. The value must use the column's JSON type (`name` is a string and
`duration` is a positive integer):

```json
{
  "column": "duration",
  "value": 21
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
closed, rejects the join with a conflict. A user can have only one active
registration for each study. On first registration, the server assigns the
participant to the smaller of the study's control and experimental groups,
choosing control when the groups are tied.

A participant can withdraw from a study they've joined with
`POST /studies/{study_id}/leave`. This records a withdrawal timestamp rather
than deleting the registration; leaving a study twice, or leaving one you were
never registered for, is rejected. Sending another `join` request for the same
study afterward reactivates the existing registration rather than creating a
new one — the participant's original group assignment is preserved.

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

The study owner can add a questionnaire with
`POST /studies/{study_id}/setquestion`. Questionnaire IDs start at 1 and
increment independently within each study. The temporary request format is:

```json
{
  "title": "Weekly check-in"
}
```

The questionnaire is stored as JSON so the payload can be expanded once its
final format is decided.

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
