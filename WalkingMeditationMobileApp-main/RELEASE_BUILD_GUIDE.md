# Android Release Build Guide

This guide records the two supported release-distribution branches for the
Walking Meditation Android app:

1. Google Play distribution using an Android App Bundle (AAB).
2. Direct distribution using a signed Android Package (APK).

Both branches begin with the same release baseline. Choose the distribution
branch before creating the long-lived signing setup.

## Current project state

- Application ID and namespace: `com.example.walking_meditation`.
- Version: `versionCode = 1`, `versionName = "1.0"`.
- The committed `SERVER_BASE_URL` points to an AWS `/dev` stage.
- Release builds reject cleartext HTTP; debug builds allow it for local emulator
  development.
- Release code/resource optimization is currently disabled.
- No release signing configuration is defined in `app/build.gradle.kts`.
- `APP_LINK_HOST` defaults to `app-links.invalid` when it is not supplied.
- `MAPS_API_KEY` defaults to an empty value when it is not supplied.

A release build created without addressing those points is not a
distribution-ready build.

## Shared release baseline

### 1. Confirm the application identity

The `applicationId` is the identity Android uses for installation and updates.
Generating a release build does not lock it, but changing it after users have
installed the app creates a separate app that cannot update the old one.

Decide whether `com.example.walking_meditation` is the permanent ID before the
first distribution intended to receive updates. If it changes, also update:

- the Maps Android-app restriction;
- `ANDROID_APP_PACKAGE_NAME` on the server;
- the package name in `/.well-known/assetlinks.json`;
- tests, scripts, and device-management rules that refer to the old ID.

The Java/Kotlin `namespace` can be refactored independently. It is the
`applicationId` that defines the installed app identity.

### 2. Set the release version

Increment `versionCode` for every distributed update. `versionName` is the
human-readable version shown to users.

An update must use the same application ID and an accepted signing certificate.
Otherwise Android treats it as a different app or requires the old app to be
uninstalled, which removes its local data.

### 3. Supply release configuration

Provide the following as external Gradle properties rather than committing
environment-specific values or credentials:

```powershell
$env:ORG_GRADLE_PROJECT_SERVER_BASE_URL = "https://production.example.com"
$env:ORG_GRADLE_PROJECT_MAPS_API_KEY = "android-restricted-maps-key"
$env:ORG_GRADLE_PROJECT_APP_LINK_HOST = "production.example.com"
```

Requirements:

- `SERVER_BASE_URL` must be the intended HTTPS backend, without an accidental
  `/dev` stage unless the artifact is explicitly a test release.
- `MAPS_API_KEY` must be an Android-restricted key, not the backend Google key.
- `APP_LINK_HOST` is a hostname only, without a scheme or path.
- The backend `PASSWORD_RESET_LINK_BASE` must use the same HTTPS host.

Gradle properties become values inside the APK/AAB. They are configuration, not
a place to store server secrets. An Android Maps key is extractable from the app
and must be protected by Android application restrictions and API restrictions.

### 4. Configure certificate-dependent services

The Maps key restriction requires:

- the final application ID; and
- the SHA-1 fingerprint of the certificate that signs the installed APK.

Android App Links require the server's `/.well-known/assetlinks.json` response
to contain:

- the final application ID; and
- the SHA-256 fingerprint of the certificate that signs the installed APK.

The server reads these from `ANDROID_APP_PACKAGE_NAME` and
`ANDROID_APP_SHA256_CERT_FINGERPRINTS`.

### 5. Run release checks

```powershell
cd WalkingMeditationMobileApp-main
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat clean testReleaseUnitTest lintRelease
```

After signing, test the actual release artifact on a physical device against the
intended backend. Exercise login/refresh/logout, password reset, Maps and Places,
route generation, narration download/playback, study invitations, questionnaires,
and researcher/admin authorization.

Debug and release currently share an application ID but use different signing
certificates. A release APK cannot replace the installed debug APK. Uninstall the
debug app first, or introduce a `.debug` application-ID suffix before relying on
side-by-side installation.

## Branch A: Google Play AAB

### Signing model

Use Play App Signing:

- The team holds an upload key and signs the AAB submitted to Play.
- Google holds the app-signing key and signs APKs delivered to devices.
- Losing an upload key can be recovered through Play's upload-key reset process.

The certificate on users' installed APKs is the Play app-signing certificate,
not normally the local upload certificate. Therefore use the Play app-signing
certificate SHA-1 for the Maps restriction and its SHA-256 for Digital Asset
Links.

Store the upload keystore and credentials outside the repository. For repeatable
CI builds, read them from protected CI secrets or external Gradle properties.

### Build

Until a Gradle signing configuration is added, use Android Studio:

1. Select **Build > Generate Signed Bundle/APK**.
2. Select **Android App Bundle**.
3. Select the `app` module and the upload keystore.
4. Select the `release` build type.
5. Generate and verify the signed AAB.

With an externalized signing configuration in place:

```powershell
.\gradlew.bat bundleRelease
```

The output is normally under `app/build/outputs/bundle/release/`.

### Play release checklist

- Enable Play App Signing and record both upload and app-signing fingerprints.
- Upload the signed AAB.
- Complete store, privacy, data-safety, content-rating, and testing requirements.
- Confirm the uploaded bundle has the expected application ID, version, backend
  URL, Maps key, and app-link host.
- Test an APK installed by Play, because Play signs the delivered APK with the
  app-signing key.

## Branch B: Direct signed APK

### Signing model

The team owns the app-signing key and directly signs every distributed APK.
There is no separate upload key and no Play-managed recovery.

Use the same signing key for the lifetime of this application ID. If the key is
lost, a newly signed APK cannot update existing installations. Keep encrypted,
access-controlled backups in at least two controlled locations and document key
ownership and recovery.

Use this self-managed release certificate's SHA-1 for the Maps restriction and
its SHA-256 for Digital Asset Links.

Do not commit the keystore, passwords, or a signing-properties file. Before
creating a keystore inside or near the working tree, add at least these ignore
patterns:

```gitignore
*.jks
*.keystore
keystore.properties
```

### Build

Until a Gradle signing configuration is added, use Android Studio:

1. Select **Build > Generate Signed Bundle/APK**.
2. Select **APK**.
3. Create or select the long-lived app-signing keystore.
4. Select the `release` build type.
5. Generate the signed APK.

With an externalized signing configuration in place:

```powershell
.\gradlew.bat assembleRelease
```

The output is normally under `app/build/outputs/apk/release/`.

Verify the exact artifact before distribution:

```powershell
apksigner verify --verbose --print-certs app-release.apk
```

Record the artifact SHA-256 checksum and distribute the APK and checksum over
HTTPS or an authenticated device-management channel.

### Installation and updates

For controlled testing:

```powershell
adb install app-release.apk
adb install -r app-release-next.apk
```

For an update to retain app data, it must have:

- the same application ID;
- the same accepted signing certificate; and
- an appropriately incremented `versionCode`.

Without Play, the project must define how users learn about, obtain, and install
updates. Options include a managed-device platform, an authenticated download
portal, or a documented manual update process. Do not implement an in-app APK
updater without a separate threat model and signature-verification design.

Users installing manually may have to authorize the browser or file manager to
install unknown apps. Android developer-verification requirements for apps
distributed outside Play are also rolling out by region, so re-check the Android
Developer Console requirements before external distribution.

## Optimization decision

Release optimization is currently disabled with `isMinifyEnabled = false`.
This is valid for an initial controlled release but produces a larger,
unobfuscated artifact. If optimization is enabled, test reflection-based Gson
serialization and every release flow, and retain the generated mapping files for
crash diagnosis.

With Android Gradle Plugin 9.3+, the current optimization DSL is:

```kotlin
buildTypes {
    release {
        optimization {
            enable = true
        }
    }
}
```

Optimization is independent of the Play-versus-APK distribution choice.

## Security gates before external distribution

- Production password-reset links use verified HTTPS App Links; the custom
  `walkingmeditation://` reset scheme is development-only.
- Study invitation links are scoped to the app's HTTPS host rather than matching
  arbitrary HTTP/HTTPS hosts.
- Release APK/AAB configuration rejects cleartext traffic.
- No production credentials are tested over a physical-device HTTP connection.
- The Maps key is restricted by package, signing certificate, and required APIs.
- The signing keystore and credentials are absent from Git and build artifacts.
- Android lint, release unit tests, and signed-artifact smoke tests pass.
- Backend authentication, authorization, rate limits, billing safeguards, and
  public HTML endpoints have passed their release security review.

## Official references

- [Sign your app](https://developer.android.com/studio/publish/app-signing)
- [Prepare your app for release](https://developer.android.com/studio/publish/preparing)
- [How Android app updates work](https://developer.android.com/google/play/app-updates)
- [Configure Android App Links](https://developer.android.com/training/app-links/configure-assetlinks)
- [Android developer verification](https://developer.android.com/developer-verification)
