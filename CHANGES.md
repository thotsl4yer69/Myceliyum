# Changes — completion & stabilization pass

## Prediction engine — real per-cell environment (accuracy upgrade)

Previously, only **observation density** varied between grid cells; every
climate/habitat factor was computed once for the map centre and applied
uniformly, so the map only looked "hot" where records clustered. Several
genuinely per-cell, real-data inputs were added so scores reflect the actual
landscape:

- **Elevation fitness** — real ground elevation per cell (Open-Meteo's free,
  no-key elevation API, batched ≤100 points/request) vs a species altitude band.
- **Terrain moisture** — local slope + concavity from neighbouring cells'
  elevations (gentle slopes / concave hollows score up; exposed highs down).
- **Slope aspect** — south/east-facing slopes (cooler, moister in the Southern
  Hemisphere) favoured, derived from the elevation gradient across the cell.
- **Canopy/forest proximity** — distance to mapped woods/forest/parks/reserves
  from OpenStreetMap's Overpass API (free, no key), a strong signal for
  mycorrhizal and wood-rotting fungi.
- **Soil moisture** — real 0–7 cm volumetric soil moisture (Open-Meteo hourly)
  blended into the background-moisture factor, replacing rainfall totals alone.

Factor weights rebalanced to sum to 1.0 (evidence 0.24, season 0.16, rain 0.12,
**canopy 0.12**, terrain 0.08, habitat 0.08, elevation 0.06, temperature 0.06,
**aspect 0.04**, moisture 0.03, moon 0.01). Every external fetch degrades
gracefully (neutral score) so prediction never breaks offline. New pure helpers
(`elevationFitness`, `terrainMoistureScore`, `slopeAspectMoistureScore`,
`soilMoistureFitness`, `canopyProximityScore`) are unit-tested in `MycoMathTest`.

---

This document records the changes made to take Mycelium Mapper from its original
Google AI Studio export to a buildable, cleaned-up project on stable tooling.

> **Not yet compile-verified.** These edits were made by code review. An Android
> SDK wasn't available in the environment where they were written, so the
> project has **not** been compiled or run here. Please do a Gradle sync + build
> in Android Studio and address any version nudges (see the note at the end).

---

## 1. Build setup

- **Added the Gradle wrapper** (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.properties`) pinned to **Gradle 8.11.1**.
  - The binary `gradle/wrapper/gradle-wrapper.jar` could **not** be written with
    text tooling. Android Studio regenerates it automatically on first sync. If
    you build from a terminal first, run once:
    `gradle wrapper --gradle-version 8.11.1` (needs a system Gradle), or just
    open the project in Android Studio.
- **Fixed the missing debug keystore blocker.** The old `debug` build type
  referenced `${rootDir}/debug.keystore`, which is `.gitignore`d and absent
  (only `debug.keystore.base64` was shipped). The `debugConfig` signing config
  was removed; the debug build now uses Android's standard auto-generated
  keystore (`~/.android/debug.keystore`). No checked-in keystore is needed.
- **Release signing** now only activates when `STORE_PASSWORD` is set, so debug
  builds never fail on a missing release keystore.

## 2. Down-tiered to stable tooling (off the AI Studio canary)

The export targeted **AGP 9.1.1 / `compileSdk 36`** with the new
`compileSdk { release(36) { ... } }` DSL — only available on canary Android
Studio. Moved to a current stable matrix:

| Item | Was | Now |
|------|-----|-----|
| Android Gradle Plugin | 9.1.1 | 8.7.3 |
| Gradle | (no wrapper) | 8.11.1 |
| compileSdk / targetSdk | 36 | 35 |
| Kotlin | 2.2.10 | 2.0.21 |
| KSP | 2.3.5 | 2.0.21-1.0.28 |
| Compose BOM | 2024.09.00 | 2024.12.01 |
| core-ktx | 1.18.0 | 1.15.0 |
| activity-compose | 1.10.1 | 1.9.3 |
| navigation-compose | 2.8.9 | 2.8.5 |
| Room | 2.7.0 | 2.6.1 |
| Retrofit / converter | 2.12.0 | 2.11.0 |
| OkHttp / logging | 4.10.0 | 4.12.0 |
| Moshi | 1.15.2 | 1.15.1 |
| Robolectric | 4.16.1 | 4.14.1 |
| foojay resolver | 1.0.0 | 0.8.0 |

- **Added the `org.jetbrains.kotlin.android` plugin.** AGP 9 applied Kotlin
  implicitly via its built-in support; stable AGP 8.x does not, so the base
  Kotlin plugin is now applied explicitly (plus `kotlinOptions { jvmTarget }`).
- Updated `ExampleRobolectricTest` `@Config(sdk = [36])` → `[35]` to match
  `compileSdk` and Robolectric 4.14.1's supported SDK.

## 3. Real camera capture (was mocked)

The "photo voucher" in **Add Sighting** previously assigned a hardcoded Unsplash
URL. It now performs a real capture:

- Added a `FileProvider` (`${applicationId}.fileprovider`) to the manifest and
  `res/xml/file_paths.xml`.
- `AddSightingDialog` now uses `ActivityResultContracts.TakePicture()`, writing
  the full-resolution image into the app's internal `voucher_photos/` directory
  and storing the resulting `content://` URI on the `UserSighting`. Coil renders
  it in the list/detail views (already URI-capable).
- Permission is checked before launching; the existing camera-permission
  rationale flow still gates the dialog.
- Minor: the **Save** button is disabled until a species is selected.

## 4. Removed leftover template bits

- Removed the unused **Gemini / secrets** plumbing: the `secrets` Gradle plugin,
  its `secrets { }` block, and the `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`
  entry in `metadata.json`. (Firebase AI was already commented out.)
- Removed dependencies that aren't referenced anywhere in the source:
  **Firebase BOM/AI, CameraX (camera2/core/lifecycle/view), Accompanist
  Permissions, DataStore Preferences, and Roborazzi** (plugin + libs; no
  screenshot test exists). This also shrank the version surface to down-tier.
- Removed `buildConfig = true` (no `BuildConfig` usage in the code).
- Rewrote `README.md` for the current state (no API keys needed; both
  iNaturalist and Open-Meteo are public/keyless).
- Added `MycoMath` (season-window + haversine helpers) and routed the
  repository's private copies through it, then added `MycoMathTest` with real
  unit tests (normal & year-wrapping season windows; haversine zero + a known
  Melbourne→Dandenong distance).

### Safe to delete (couldn't remove without a shell)
- `.env.example` — now unused (secrets plugin removed).
- `debug.keystore.base64` — no longer referenced (debug uses the default keystore).
- `app/src/test/screenshots/greeting.png` — orphaned Roborazzi baseline.
- `.build-outputs/app-debug.apk` — stale prebuilt artifact.

## Verification still to do on your machine
1. Open in Android Studio (Ladybug/2024.2+ with JDK 17) and let Gradle sync.
2. If a specific library version doesn't resolve against compileSdk 35, nudge it
   (these pins are a best-effort stable set, not build-verified here).
3. Run `./gradlew testDebugUnitTest` to exercise `MycoMathTest`.
4. Run the app; test Add Sighting → camera capture on a device/emulator with a
   camera.

---

# Round 2 — usability pass

A second pass after the first build succeeded, addressing four themes. Still
edited by code review (Android not compiled here), so a sync + run is needed.

## A. Made "fake" features actually work
- **Persistence with DataStore.** Added `androidx.datastore.preferences` and a
  new `SettingsStore`. Measurement units, map style, and the safety disclaimer
  acknowledgement now persist across launches. The disclaimer no longer reappears
  every cold start. The `FungiViewModel` exposes these as persisted `StateFlow`s
  with `setMeasureUnits()`, `setMapTheme()`, and `acceptSplashNotice()`.
- **Map style switch is real.** The Map view now changes basemaps:
  *Topographic* → OpenTopoMap tiles, *Standard Street* → standard OSM, *Dark* →
  standard OSM with osmdroid colour inversion (a genuine dark map). Old
  decorative-only labels were replaced with these three working styles.
- **Metric/Imperial conversion applies.** The Hotspots screen now converts the
  search-radius readout (km↔mi) and the microclimate panel (mm↔in, °C↔°F)
  based on the setting.

## B. Fixed misleading bits
- **Home location label** is now reverse-geocoded from the current map centre
  instead of the hardcoded "Dandenong Ranges Sclerophyll Area".
- **Dropped the false "encrypted" claim** in the sightings banner; it now
  accurately says entries are stored privately on-device and never uploaded.
- **Honest iNaturalist states.** The Home feed no longer shows an infinite
  spinner; it shows a real "no observations found" state once a fetch completes
  empty, and only spins while actually loading.
- **Robust coordinate parsing.** iNaturalist responses are now parsed from
  `geojson` coordinates as well as `latitude`/`longitude` and the `location`
  string, so pins populate reliably.

## C. Expanded the species catalogue (5 → 15)
Added 10 accurate Victorian/SE-Australian species with conservative,
safety-aware descriptions, including the deadly **Amanita phalloides (Death
Cap)**, Sulphur Tuft, Shaggy Ink Cap, Turkey Tail, Oyster Mushroom, Australian
Honey Fungus, Pixie's Parasol, Anemone Stinkhorn, Graceful Parasol, and Archer's
Cortinar. New entries use empty `imageUrls` (the UI degrades to a placeholder)
rather than mismatched stock photos — you can drop in real reference images
later. JSON validated (15 objects, consistent schema).

## D. Improved the prediction model
Reworked the hotspot scoring in `FungiRepository.generateHotspots` to be
spatially meaningful and honest:
- Local evidence now uses a **Gaussian proximity kernel** (σ ≈ 800 m) combined
  with the existing 365-day recency decay, so cells near actual observations
  form real peaks instead of a flat grid.
- Score is now **multiplicative**: `environment (season × weather) × (0.15 +
  0.85 × local-evidence)`. With no nearby records a cell reads low ("no evidence
  here") rather than a misleading uniform medium.
- Contributing-factor text was rewritten to describe inputs plainly (record
  counts, seasonal/condition suitability) instead of inventing precise additive
  percentages, and now states it is a heuristic estimate, not a guarantee.

### New manual checks
- Toggle Metric/Imperial and confirm the Hotspots radius + weather readouts change.
- Switch map styles in Settings and confirm the basemap changes (incl. dark).
- Force-close and reopen: the disclaimer should appear only the first time.

---

# Round 3 — pre-build verification audit

Static audit of every changed file (no Android SDK/Gradle/Kotlin in the review
environment, so this was code review, not a compile). One significant fix came
out of it:

## Critical runtime fix: Moshi could not parse any Kotlin class
The app built a **default** `Moshi` (in `MyceliumApplication` for Retrofit, and
in `FungiRepository` for asset seeding). Moshi refuses to reflectively
(de)serialize Kotlin classes unless you register `KotlinJsonAdapterFactory`
(moshi-kotlin) or annotate each class with `@JsonClass(generateAdapter=true)`.
None of the data classes (`Species`, the iNaturalist/Open-Meteo DTOs) were
annotated, so:
- `seedDatabase()` would throw while parsing `species.json` → caught by its
  try/catch → **species table left empty** → empty catalogue, no hotspot species.
- Network parsing would throw → caught → silently no live observations/weather.

Fix: both `Moshi.Builder()` instances now `.add(KotlinJsonAdapterFactory())`.
`moshi-kotlin` was already a declared dependency, so no new libraries. This is
strictly safe — if parsing somehow worked before, it still works.

## Reviewed and considered consistent
- Settings refactor (DataStore-backed `StateFlow`s, setters, factory wiring).
- Camera capture (`TakePicture` + FileProvider authority `${applicationId}`,
  `file_paths.xml` mapping to `filesDir/voucher_photos`).
- Map tile switching (`tileSourceForTheme`, osmdroid `INVERT_COLORS` dark mode).
- Unit conversion, Home reverse-geocode + honest empty state, geojson parsing,
  hotspot scoring rewrite, and the expanded `species.json` (validated: 15 objects).

## Residual risks to watch on first sync/run (can't compile-check here)
- `TilesOverlay.INVERT_COLORS` — standard osmdroid 6.x dark-mode idiom; verify
  the dark style renders.
- osmdroid `OpenTopo` tile source name — present in 6.1.20; confirm topo tiles load.
- Codegen vs. reflection: `moshi-kotlin-codegen` (KSP) is still declared but now
  unused (no `@JsonClass`); harmless. Can be removed later if desired.

---

# Round 4 — attempted in-sandbox build (partial)

I attempted a full Android build inside the sandbox (no Android SDK initially —
I installed JDK 21, Gradle 9.3.1, Android SDK platforms/android-35 and
build-tools/36). Got the project through dependency resolution and ~24 build
tasks (configure, resources, manifest processing, KSP). One real fix surfaced:

## Bug found and fixed: Room 2.6.1 incompatible with Kotlin 2.2 / KSP 2.3
KSP failed during `:app:kspDebugKotlin` with
`java.lang.IllegalStateException: unexpected jvm signature V`. This is a known
incompatibility between Room's annotation processor and KSP 2 / Kotlin 2.2 in
Room 2.6.x. Bumped Room back up to **2.7.0** (its original version before my
earlier down-tier). KSP then ran clean and is cached as `FROM-CACHE`.

This would have failed on the Windows build as well the moment a clean
`assembleDebug` actually exercised KSP — it's a real fix, not a sandbox quirk.

## Why I couldn't finish the APK in here
After KSP completed, `:app:compileDebugKotlin` (with the Compose compiler
plugin and ~24 source files) consistently exceeded the sandbox's 45-second
per-command hard ceiling. Each bash call is in its own PID namespace, so I
couldn't background gradle and poll. Net: KSP and earlier tasks are verified;
the Kotlin compile and downstream tasks (dex, package, APK) are unverified in
the sandbox but should run normally on a real machine.

---

# Round 5 — peak-season usability

The map was likely showing very few hotspots even with rich data, because the
old algorithm ran on one species at a time and required local evidence to
escape Low tier. The catalogue is now 15 species and it's autumn — the map
should reflect that. Changes:

## Aggregate "All species" hotspot mode (default)
- New `FungiRepository.generateMultiSpeciesHotspots()` aggregates iNaturalist
  records and user sightings across every catalogued species, with diversity
  (how many distinct species are nearby) tracked per cell.
- New `FungiViewModel.isAllSpeciesMode` (default `true`) and
  `setAllSpeciesMode(...)` toggle.
- Map screen has a new **Combine all species** switch above the species
  selector; flipping it off restores single-species focus.
- The selector is disabled and shows "All species (combined)" while aggregate
  mode is on.
- Pins now respect the mode: in aggregate mode the map skips per-species pins
  (the cells already encode combined evidence); Home still shows pins from the
  currently selected species.

## Tuned defaults for peak season
- Default search radius **5 km → 10 km**; slider max **20 → 30 km**.
- Single-species baseline floor lifted from `0.15` to `0.20` so good-condition
  cells aren't pinned to Low when local evidence is sparse.
- Aggregate mode uses a `0.25` baseline and a slower saturation curve (more
  evidence sources contributing), so the map is honestly busier when many
  species are fruiting at once.

## Tier vocabulary and colour reset
- The alarming red **High** tier is gone. Tiers now use the existing forest
  palette: mint `#4DDFAC` (promising), chanterelle gold `#E8C86B` (possible),
  dim sage `#6B8775` (quiet). Mint reads as "good fungal habitat", not "danger".
- Tier labels renamed from "High / Medium / Low Probability" to
  **Promising / Possible / Quiet** throughout the map view, hotspot list, and
  detail card.
- Worst Cold-War-terminal copy softened: "MICROCLIMATE RESEARCH SIGNALS" →
  "Microclimate (past 30 days)", "PROBE THIS SECTOR" → "Centre on this spot",
  "Reconstructing environmental layers…" → "Combining observations and
  weather…", "BIO-ANOMALY HOTSPOTS" → "Promising and possible hotspots nearby".
- Mockups for the two paired design directions (dark "Bioluminescent night" /
  light "Quiet modernist") were used as the target identity for colour and
  copy choices; full design pass is a future round.

---

# Round 6 — full design pass

The two-direction approach (A dark / C light) is now actually the design system,
not just a mockup. Every screen has been swept end-to-end.

## Theme system
- New palette in `Color.kt` for both directions: `NeonMint`, `ChanterelleGold`,
  `MossSpruce`, `DimSage`, `DeepForestVoid`, `CharcoalSpruce`, `MediumSpruce`,
  `SageOutline` for A; `DeepForestGreen`, `WarmHoney`, `SoftMoss`,
  `WarmOffWhite`, `PaperWhite`, `SoftStone`, `WarmStoneOutline` for C.
- `Theme.kt` rewritten with proper Material 3 schemes for both: Direction A
  drives `DarkColorScheme`, Direction C drives `LightColorScheme`.
- `Typography` no longer forces `Monospace` on `labelSmall`/`labelMedium`; those
  are now sans-serif at sensible sizes. Monospace remains in deliberate spots
  (coordinates, weather numbers).

## Theme switcher (System / Light / Dark)
- Persisted to `SettingsStore` (`appTheme` key, default `System`).
- New "Theme" card at the top of Settings, with a short blurb describing the
  two directions, and three radio options.
- `MainActivity` reads the preference and selects light or dark scheme; "System"
  follows `isSystemInDarkTheme()`.

## Voice & copy — every screen rewritten
Stripped ALL-CAPS terminal labels and forced monospace across Home, Search,
Detail, Sightings (incl. all dialogs), Settings, Splash, and the Map drawer.
Highlights of the rewrite:

- "MYCELIUM MAPPER · Victoria Field Mycology Terminal" → "Mycelium Mapper ·
  Victoria field guide"
- "CURRENT FIELD GRID STATION" → "Your area"
- "YOUR RECENT FIELD LOGS / VIEW ALL" → "Your recent finds / See all"
- "iNATURALIST FIELD TRACKS" → "Recent iNaturalist observations"
- "FIELD IDENTIFICATION FILTERS" → "Filters"; "Macrohabitat Type" → "Habitat";
  "Season Active Month" → "Fruiting month"; "Spore Print Color" → "Spore print
  colour"; "RESET FILTERS / APPLY FILTERS" → "Reset / Apply"
- "FOUND X SCIENTIFIC REFERENCE TAXA" → "X species"
- "SPECIMEN DESCRIPTION / ECOLOGICAL SIGNALS / FRUITING CALENDAR / CRITICAL
  FIELD COMPARISON (LOOK-ALIKES) / FIELD WORK RESEARCH NOTES" → "Description /
  Habitat & substrate / Fruiting calendar / Look-alikes to watch for / Notes"
- "FORECAST" button → "Find on map"
- "FAMILY: X • GENUS: Y" → "Family X · Genus Y"
- `FeatureDetailRow` no longer uppercases or monospaces its label
- "FIELD WORK SIGHTINGS / Personal Voucher Logbook" → "Sightings / Your private
  field log"
- "Personal Mycology Log is Empty" → "No sightings yet"; "LOG FIRST DISCOVERY"
  → "Log first sighting"
- Privacy banner reads naturally: "Stored privately on this device — nothing is
  uploaded"
- Add-sighting dialog: "ADD FIELD LOG SIGHTING" → "Log a sighting"; sections
  "VOUCHER TAXON ID / GPS SITE COORDINATES / REPRESENTATIVE HABITAT NOTES /
  SIGHTING PHOTO VOUCHER" → "Species / Location / Notes / Photo"
- "TAP TO TAKE DIGITAL VOUCHER" → "Tap to take a photo"; "✓ VOUCHER PHOTO
  ACTIVE" → "Photo attached"; "SAVE FIELD RECORD" → "Save sighting"
- Detail dialog: "VOUCHER SPECIMEN INFO / FIELD WORK RESEARCH ANNOTATIONS /
  DELETE SIGHTING" → "Sighting details / Notes / Delete sighting"
- Rationale dialogs: "Voucher Photo Permission / ALLOW CAMERA / SKIP PHOTO
  VOUCHER" → "Camera access / Allow camera / Skip photo"; "Location Services
  Required / ALLOW LOCATION Access / NO THANKS" → "Location access / Allow
  location / Not now"
- Toasts likewise (e.g. "Specimen logged successfully!" → "Sighting saved.")
- Settings: "FIELD RESEARCH TERMINAL SETTINGS / System Preferences & Purge
  Ports" → "Settings / Preferences and stored data"; "METEOROLOGY MEASUREMENT
  UNITS / TOPOGRAPHICAL MAP FIELD STYLE / LOCAL ROOM DATA STORAGE (TTL) /
  EXPORT SIGHTINGS DATABASE" → "Units / Map style / Cached data / Export
  sightings"; corresponding buttons sentence-cased
- Export dialog "CSV DATASET EXPORT PROGRESS" → "CSV saved", body rewritten
- Splash: "MYCOLOGICAL FIELD NOTICE / I CERTIFY & UNDERSTAND" → "Before you
  start / I understand"
- Map drawer: "MAPPING REGION / SEARCH RADIUS / COORD SITE WORKSPACE OVERRIDE
  / Lat Override / Lng Override / Hotspots Registry / Model Confidence Score /
  ECOLOGICAL CONSTITUENT ANALYSIS" → "Area / Radius / Manual coordinates /
  Latitude / Longitude / Hotspot list / Likelihood score / Why this score";
  preset and detail toasts rewritten plainly

## What's still cosmetic vs. structural
Cosmetic changes only above — no behaviour changed in this round. The
typography refresh and theme switch both honour the existing widget structure,
so the underlying flow (species seeding, hotspot computation, sighting capture,
CSV export, permissions) is unaffected.

---

# Round 7 — placeholder and skeleton audit

Comprehensive sweep for stub data, stock images, TODO comments, and template
leftovers; plus another build attempt.

## Removed placeholders
- **Unsplash stock photos in `species.json`** — the original five entries
  (Fly Agaric, Sub, Ghost Fungus, Spectacular Rustgill, Orange Pore Fungus)
  carried generic Unsplash images that weren't actually photos of those
  species. Replaced all five `imageUrls` arrays with `[]` so the UI shows the
  same neutral placeholder icon used by the ten newly-added species — no more
  visually misleading stand-ins.
- **Hardcoded Unsplash fallback in `HomeScreen.ObservationItemRow`** — the
  iNaturalist row thumbnail used a hardcoded stock URL when an observation had
  no photo. Now shows a `FilterVintage` icon on a neutral surface; same
  treatment applied to `SearchScreen.SpeciesItemCard`.
- **AGP template scaffolding test** — `ExampleUnitTest.kt`'s `2 + 2 = 4` was
  replaced with a real (if small) sanity check on `MycoMath.haversineMeters`;
  the substantive coverage lives in `MycoMathTest`.
- **AGP template `data_extraction_rules.xml` / `backup_rules.xml`** — the
  generated TODO comments were replaced with concise declarations that
  explain what's backed up.
- **`.env.example`** — replaced its stale `GEMINI_API_KEY=MY_GEMINI_API_KEY`
  contents with a short note that the file is unused (the Secrets plugin is
  gone, and both APIs the app calls are keyless).
- **"Photo Voucher Mock" comment** in `SightingsScreen` retitled to "Photo
  capture" — the capture is real now.

## Build verification — what did and didn't work
Another sandbox build attempt, picking up the toolchain installed earlier:

- **Resolved a path-mismatch error** in KSP/Room from mixing Windows-built
  caches with Linux-built ones: pointed Gradle at a sandbox-local
  `--project-cache-dir` so the Windows-owned `.gradle/` is bypassed. KSP then
  ran cleanly and is cached.
- **Confirmed Room 2.7.0** works with KSP 2.3 / Kotlin 2.2.10 — no more
  "unexpected jvm signature V" error.
- **`:app:compileDebugKotlin` still exceeds the sandbox 45-second per-bash
  ceiling** as it did last time. Kotlin compile with the Compose plugin over
  the project's source set needs more than that uninterrupted, and processes
  don't survive bash exit. Net: unchanged from the last sandbox attempt —
  KSP and earlier tasks verified; Kotlin compile and downstream still need a
  real-machine build to confirm.

## Static verification done in lieu of compile
- Grepped the whole project for `TODO/FIXME/XXX/placeholder/stub/mock/dummy/
  Lorem/example.com/unsplash` — only matches were Compose-text-field
  placeholder hints (legitimate use) and a single template TODO already
  removed above.
- Confirmed every newly-introduced symbol resolves: `Icons.Default.DarkMode`
  (extended icons, used elsewhere), `TilesOverlay.INVERT_COLORS` (standard
  osmdroid idiom), `isSystemInDarkTheme` (imported in `MainActivity` and
  `Theme.kt`), the `appTheme` StateFlow + setter + collection in Settings.
- Verified the removed colours from `Color.kt` (`ActiveHighDanger`,
  `ActiveMedCaution`, `ActiveLowEco`) have zero remaining references.
