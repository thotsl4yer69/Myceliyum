# Myceliyum

**Offline-first Android field research for mycology in Victoria, Australia**

[![Status](https://img.shields.io/badge/status-application%20prototype-blue)](PROJECT_STATUS.md)
![Android](https://img.shields.io/badge/Android-Kotlin%20%2B%20Compose-green)
![Data](https://img.shields.io/badge/data-offline--first-blue)

> **Maturity: Application prototype / active.** Myceliyum combines local reference data, public observations, environmental context, mapping and a private field log. See [PROJECT_STATUS.md](PROJECT_STATUS.md) for the evidence boundary.

## What it is

Myceliyum is a personal field-research tool for mycology in Victoria. It combines a bundled species reference catalogue, live iNaturalist observations and weather/habitat signals from Open-Meteo to surface **candidate areas for field investigation**, while keeping the user's own sightings in an offline-first local logbook.

> **Field notice:** This is a research aid, not an identification or consumption authority. Never eat wild fungi based on this app's suggestions or predictions.

## Why it is useful as an engineering project

This repository is a complete native-mobile product rather than a single API demo. It brings together:

- Kotlin + Jetpack Compose (Material 3);
- Room for local persistence and offline-first data;
- Retrofit + Moshi + OkHttp for API integration;
- osmdroid / OpenStreetMap mapping and hotspot overlays;
- Play Services Location;
- camera capture through Android platform/FileProvider flows;
- release, signing and CI-oriented build tooling;
- optional AI/geocoding/backend integrations without making them mandatory for the core application.

## Data flow

```text
 Bundled species data ─┐
 iNaturalist ──────────┼──> field context ──> map / candidate zones
 Open-Meteo ──────────┘                         │
                                                │
 Device location ───────────────────────────────┤
                                                ▼
                                      private local logbook
                                             (Room)
```

The app is intended to help organise field research. It does not turn correlations in public/environmental data into scientifically validated fruiting forecasts.

## Run locally

**Prerequisites:** Android Studio (Ladybug / 2024.2 or newer) with JDK 17.

1. Open Android Studio and choose **Open**, then select this project directory.
2. Let Gradle sync. On first sync Android Studio regenerates the Gradle wrapper JAR if it is not present. From a terminal you can instead run `gradle wrapper --gradle-version 8.11.1` once if you have a system Gradle.
3. Run the **app** configuration on an emulator or Android device.

The core app needs no private API keys. iNaturalist, Open-Meteo and OpenStreetMap/Overpass provide the main public data paths.

Optional entries in `local.properties` (git-ignored; see `.env.example`) unlock additional integrations:

- `ANTHROPIC_API_KEY` — optional AI Identify/vision/chat experiments;
- `GOOGLE_API_KEY` — optional Google Geocoding for map labels;
- `BACKEND_BASE_URL` / `BACKEND_TOKEN` — optional Earth Engine/backend layers; without this path the app falls back to free OSM-derived data where supported.

## Website and releases

- The marketing site under `docs/` is configured for Cloudflare Workers deployment.
- The project includes automation for generating a rolling debug APK from `main`.
- See `docs/DEPLOY.md` for the deployment and release-signing workflow.

Treat rolling/debug artifacts as development builds, not as a claim of store/public-production release readiness.

## Signing

### Debug

The project uses a stable debug-signing workflow so successive development APKs can update one another instead of receiving a different CI-generated signature on each run.

### Release

Provide a release keystore and the documented `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` environment values. Release signing is skipped when these values are not configured.

Do not commit real release credentials or signing secrets.

## Build matrix

The project targets a stable Android toolchain around Android Gradle Plugin 8.7.x, Kotlin 2.0.x and `compileSdk` / `targetSdk` 35. See `CHANGES.md` for the exact repository history and migrations from earlier tooling.

## Evidence boundary

Myceliyum is an **application prototype**, not a scientific, medical or food-safety authority. Claims about species likelihood, habitat or fruiting conditions should remain decision-support/research language unless independently validated.

Optional AI services are integrations with external models; use of those services is not represented as authorship of their underlying models or research.

## Development provenance

Myceliyum is an authored MAZLABZ application developed with AI coding agents as part of the normal engineering workflow. AI supports implementation, research, refactoring and debugging; product definition, architecture, integration and verification remain the project owner's responsibility.

## Portfolio significance

Myceliyum is one of the clearest general-audience examples in this account of an idea being carried across an entire application stack:

**native Android · Kotlin · Compose · local data · maps · location · APIs · release tooling**
