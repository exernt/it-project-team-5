# Password-reset emulator testing

This procedure exercises the password-reset email and Android deep-link flow
without a domain or an external email provider. The server writes a complete
email to an ignored local directory, and a helper opens its link in the running
Android emulator. Reset credentials are never written to application logs.

## Prerequisites

- Python 3.13 server environment and PostgreSQL configured as in the project
  README.
- Android Studio, an Android emulator, and `adb` available on `PATH`.
- The debug app installed on the emulator.

## Configure local email capture

In `WalkingMeditationServer-main/.env`, use:

```dotenv
EMAIL_BACKEND=file
EMAIL_CAPTURE_DIRECTORY=var/dev-mail
PASSWORD_RESET_FROM_EMAIL=Walking Meditation <no-reply@localhost>
PASSWORD_RESET_LINK_BASE=walkingmeditation://password-reset
PASSWORD_RESET_REQUEST_COOLDOWN_SECONDS=60
```

The `var/dev-mail` directory is ignored by Git because its `.eml` files contain
live local reset credentials.

## Start the application

From `WalkingMeditationServer-main`:

```powershell
alembic -c database/alembic.ini upgrade head
uvicorn main:app --host 127.0.0.1 --port 8000 --reload --reload-exclude "venv/*"
```

In another terminal, build and install the Android debug app:

```powershell
cd ..\WalkingMeditationMobileApp-main
.\gradlew.bat installDebug
```

Start the installed app in the emulator. The default debug server URL,
`http://10.0.2.2:8000`, reaches the host server from the Android emulator.

## Run the successful reset flow

1. Register a test account in the app, then return to the login screen.
2. Select **Forgot password**, enter the test account's email, and submit.
3. Confirm the app displays **Check your email**. It must not display a token
   input field.
4. Confirm the server console contains only a backend and opaque message ID for
   the email. It must not contain the recipient, token, link, or email body.
5. From `WalkingMeditationServer-main`, open the newest captured email in the
   emulator:

   ```powershell
   python scripts/open_latest_reset_email.py
   ```

6. Confirm the emulator opens the new-password screen directly.
7. Enter and confirm a new password of at least eight characters.
8. Confirm the app returns to login, the old password fails, and the new
   password succeeds.

The helper parses the `.eml` file locally and passes its custom-scheme link to
`adb`; it does not print the reset credential.

## Resend and negative checks

### Cooldown

Immediately select **Resend email**. The UI should retain the same generic
message, and the server should not create a second `.eml` file during the
configured cooldown. After 60 seconds, resend again and confirm a new capture
appears.

### Unknown email

Request a reset for an unregistered address. The app should show the same
**Check your email** screen, but the server should not create an `.eml` file.

### Invalid external link

Run:

```powershell
adb shell am start -W -a android.intent.action.VIEW -d "walkingmeditation://password-reset#token=short" com.example.walking_meditation
```

The app should reject the link and return to the reset-request screen.

### Single use and replacement

After successfully using a link, invoke the helper again and try another
password change. The server should reject the already-used token. Requesting a
new reset after the cooldown also invalidates any older unused reset link.

## Clean up

Captured messages contain temporary credentials. Delete them when testing is
finished:

```powershell
Remove-Item -Path .\var\dev-mail\*.eml
```

Do not switch `EMAIL_BACKEND` to `ses` for emulator testing. SES, DNS, verified
Android App Links, and production-domain setup are intentionally outside this
local procedure.
