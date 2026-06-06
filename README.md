# Mycelium Mapper

A personal field-research tool for mycology in Victoria, Australia. It combines a
bundled species reference catalogue, live iNaturalist observations, and
Open-Meteo weather/habitat signals to suggest probable fruiting hotspots — all
backed by an offline-first Room database.

> ⚠️ **Field notice:** This is a personal research tool, **not** a consumption or
> identification authority. Never eat wild mushrooms based on this app.

## Features

- **Taxa catalogue** — searchable/filterable species reference (habitat, season, spore print).
- **Hotspot predictor** — grid model scoring fruiting likelihood from observations + weather.
- **Sightings logbook** — log finds with a real camera voucher photo, GPS, and notes; export to CSV.
- **Offline-first** — observations are cached locally with a 24h TTL.

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Room · Retrofit/Moshi · Coil · `TakePicture`/FileProvider · Robolectric + Roborazzi.

## Build & run

**Prerequisites:** Android Studio (or the Android SDK + JDK 17). The repo includes
a Gradle wrapper, so no separate Gradle install is needed.

```bash
# Run the unit tests
./gradlew testDebugUnitTest

# Build a debug APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android-ci.yml`) runs the tests and a debug build on every PR.

## Deploying to a real phone

### Quick install (debug build)

1. Enable **Developer options → USB debugging** on the device and connect it.
2. Install directly:
   ```bash
   ./gradlew installDebug
   ```
   or build `assembleDebug` and `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### Release build

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Release signing is driven by environment variables. **If they're not set, the
release APK is automatically debug-signed** so it still installs on a device for
testing (it just can't be uploaded to the Play Store). To produce an
upload-ready, release-signed build, generate a keystore once:

```bash
keytool -genkey -v -keystore my-upload-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

then set:

| Variable          | Meaning                                           |
|-------------------|---------------------------------------------------|
| `KEYSTORE_PATH`   | path to your `.jks` (default `my-upload-key.jks`) |
| `STORE_PASSWORD`  | keystore password                                 |
| `KEY_PASSWORD`    | key password                                      |
| `KEY_ALIAS`       | key alias (default `upload`)                       |

```bash
KEYSTORE_PATH=$PWD/my-upload-key.jks STORE_PASSWORD=... KEY_PASSWORD=... \
  ./gradlew assembleRelease
```

For a Play Store upload bundle use `./gradlew bundleRelease` (produces an `.aab`).

> The keystore and `.env` are git-ignored — never commit signing material.

### Release build in CI

The **Android Release Build** workflow (`workflow_dispatch`, run it from the
Actions tab) produces a downloadable release APK artifact. To have CI sign it
with your release key, add these repository secrets:

- `RELEASE_KEYSTORE_BASE64` — `base64 -w0 my-upload-key.jks`
- `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`

Without them the workflow still builds a debug-signed, installable APK.

## Daily improvement pass

This repo is maintained by a daily automated review (usability, accuracy, UX).
To run it on a schedule, configure a scheduled trigger in the Claude Code web app
pointing at this repository.
