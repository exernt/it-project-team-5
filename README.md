# Walking Meditation

This repository contains:

- `WalkingMeditationMobileApp-main`: a Java Android application.
- `WalkingMeditationServer-main`: a FastAPI service for routes, meditation scripts, and text-to-speech.

## What works without API keys

The Android project builds and its local unit tests run without credentials. Route search is disabled with a clear message until `MAPS_API_KEY` is configured. The FastAPI code's pure unit tests also run without credentials, and TTS is loaded lazily only when `/tts` is first called.

Live route and script generation still require Google Maps and OpenAI credentials.

## Server setup (Windows PowerShell)

Requirements:

- Python 3.13
- ffmpeg on `PATH` for `/tts`
- Sufficient disk space for the Qwen TTS model (30 GB is recommended by the existing deployment notes)

```powershell
cd WalkingMeditationServer-main
py -3.13 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
Copy-Item .env.example .env
```

Populate `.env` when credentials are available:

```dotenv
GOOGLE_MAPS_API_KEY=
OPENAI_API_KEY=
APP_API_TOKEN=
POSTGRES_DB=walking_meditation
POSTGRES_USER=walking_app
POSTGRES_PASSWORD=walking_local
POSTGRES_PORT=5432
DATABASE_URL=postgresql+asyncpg://walking_app:walking_local@localhost:5432/walking_meditation
```

`DATABASE_URL` is optional until database-backed features are added. When it is
set, the server creates an async PostgreSQL connection pool. The database must
already exist and the configured user must be able to connect to it.

Start the repository's local PostgreSQL 18 database with Docker:

```powershell
docker compose up -d database
docker compose ps
```

The Compose database binds only to `127.0.0.1` and stores its data in the named
`postgres_data` volume. The example password is for local development only;
production deployment must use a separately managed secret and database.

Start the local server:

```powershell
uvicorn main:app --host 127.0.0.1 --port 8000
```

Check the heartbeat at `http://127.0.0.1:8000/`. With PostgreSQL configured,
`http://127.0.0.1:8000/health/database` returns `{"database":"ready"}` after
running a live `SELECT 1` query. The first `/tts` request loads the Qwen model
and can take substantially longer than later requests.

Apply all committed database migrations from the server directory:

```powershell
alembic upgrade head
```

Run the credential-free server unit tests:

```powershell
$env:PYTHONPATH = (Resolve-Path .).Path
python -m unittest discover -s tests -v
```

## Android setup

Requirements:

- Android Studio with Android SDK 36 installed
- JDK 17 or the JDK bundled with the current Android Studio
- An Android 10 (API 29) or newer emulator/device

Open `WalkingMeditationMobileApp-main` in Android Studio and let Gradle sync. The default debug server is `http://10.0.2.2:8000`, the Android emulator alias for the host computer.

Credentials and URLs are Gradle properties. To configure them for a PowerShell session without writing secrets into the repository:

```powershell
$env:ORG_GRADLE_PROJECT_MAPS_API_KEY = "your-android-restricted-key"
$env:ORG_GRADLE_PROJECT_SERVER_BASE_URL = "http://10.0.2.2:8000"
$env:ORG_GRADLE_PROJECT_SERVER_ACCESS_TOKEN = "the-same-value-as-APP_API_TOKEN"
```

`APP_API_TOKEN` is optional for local keyless development. Set it in non-local deployments; protected app endpoints then require the matching `X-App-Token` header.

For a physical device, use an HTTPS development endpoint or a host address reachable by the device. Release builds reject cleartext HTTP.

Build and test from the command line:

```powershell
cd WalkingMeditationMobileApp-main
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

## End-to-end local test

1. Start the FastAPI server on port 8000.
2. Export `ORG_GRADLE_PROJECT_MAPS_API_KEY` and launch the app in an Android emulator.
3. Select source/destination suggestions, enter available time and a meditation purpose, and request routes.
4. Choose a baseline or park route to generate the XML meditation.
5. Download narration audio using the floating download button.
6. When downloads finish, the button changes to Play. Start playback and walk along the selected route; narration points are distributed across its route polyline.

Google/OpenAI/TTS integration behavior cannot be tested until the corresponding credentials and model dependencies are available.
