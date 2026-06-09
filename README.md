# Mycilliyums

A personal field-research tool for mycology in Victoria, Australia. It combines a
bundled species reference catalogue, live iNaturalist observations, and
weather/habitat signals (Open-Meteo) to suggest probable fungal fruiting zones,
and lets you keep a private, offline-first logbook of your own sightings.

> ⚠️ **Field notice:** This is a research aid, not an identification or
> consumption authority. Never eat wild fungi based on this app's predictions.

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- Room (offline cache + user sightings)
- Retrofit + Moshi + OkHttp (iNaturalist & Open-Meteo APIs)
- osmdroid (OpenStreetMap tiles + hotspot overlays)
- Play Services Location, CameraX-free system camera capture via FileProvider

## Run locally

**Prerequisites:** Android Studio (Ladybug / 2024.2 or newer) with JDK 17.

1. Open Android Studio and choose **Open**, then select this project directory.
2. Let Gradle sync. On first sync Android Studio regenerates the Gradle
   wrapper JAR if it isn't present. (From a terminal you can instead run
   `gradle wrapper --gradle-version 8.11.1` once, if you have a system Gradle.)
3. Run the **app** configuration on an emulator or device.

No API keys are required — both the iNaturalist and Open-Meteo APIs used here
are public and keyless.

### Signing

- **Debug:** uses Android's standard auto-generated debug keystore
  (`~/.android/debug.keystore`). Nothing needs to be checked in.
- **Release:** provide a keystore and set the `KEYSTORE_PATH`, `STORE_PASSWORD`,
  and `KEY_PASSWORD` environment variables. Release signing is skipped
  automatically when those variables aren't set, so debug builds always work.

## Build matrix

This project targets stable tooling (Android Gradle Plugin 8.7.x, Kotlin 2.0.x,
`compileSdk`/`targetSdk` 35). See `CHANGES.md` for the full version list and the
history of how it was migrated off the original AI Studio canary configuration.
