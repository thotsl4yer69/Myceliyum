# Prediction Pipeline (as built)

This document describes the **current, shipped** hotspot prediction pipeline end
to end: what data feeds it, how a cell's score is combined, the spatial
resolution, and the exact code seams where each stage lives. It is a companion
to `PREDICTION_ENGINE_V2.md` (which is the *roadmap*/design intent); this file is
the *as-built* reference for reviewing or refactoring specific sections.

All file references are `path:line` against the repo at time of writing.

---

## 1. Entry points

Two public suspend functions on `FungiRepository` produce the map:

| Function | File:line | Produces |
|----------|-----------|----------|
| `generateHotspots(species, centerLat, centerLng, radiusKm, forceRefresh)` | `app/.../data/repository/FungiRepository.kt:1110` | Single-species hotspot grid |
| `generateMultiSpeciesHotspots(centerLat, centerLng, radiusKm, forceRefresh)` | `FungiRepository.kt` | Aggregate (whole-catalogue) grid |
| `deepSearchCell(species, parentCell, parentRadiusKm, …)` | `FungiRepository.kt` | Fine ~15 m sub-grid drilled into one overview cell (single-species). See `PREDICTION_ENGINE_V2.md` §7. |

Both grid functions are thin callers of a shared private `runSpeciesGrid(...)`, so
the broad overview and the Deep-Search drill-down score through the *identical*
pipeline (just a different cell size / extent / `terrainSpacingM`).

Both return `List<HotspotCell>`; the `MapScreen` composable renders each cell as
a coloured square with a tap-through factor breakdown. The UI trigger is the
search/radius control in `app/.../ui/screens/MapScreen.kt` (radius slider
`1f..30f` km, `MapScreen.kt:805`) via `FungiViewModel`.

---

## 2. End-to-end flow

```
 user picks species + center + radius
              │
              ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ 1. EVIDENCE        getObservations()  → iNat + ALA + GBIF     │  network, Room-cached
 │                    + user sightings (Room)                    │
 │                    aggregate also folds in area-wide Fungi    │
 ├─────────────────────────────────────────────────────────────┤
 │ 2. WEATHER         getDetailedWeather() → Open-Meteo 45 days  │  one call at center
 ├─────────────────────────────────────────────────────────────┤
 │ 3. GLOBAL FACTORS  season, rainTrigger, temp, moisture,       │  pure (MycoMath)
 │                    moon, habitat, hostGroups                  │  computed once
 ├─────────────────────────────────────────────────────────────┤
 │ 4. GRID            enumerate in-radius cells (adaptive ~250m) │
 ├─────────────────────────────────────────────────────────────┤
 │ 5. PER-CELL DATA   fetchElevations()  → Open-Meteo (≤100/req) │  batched + cached
 │                    fetchEnvLayers()   → EE backend (≤500/req) │  batched + cached
 │                    (OSM fallback if backend unset/empty)      │
 ├─────────────────────────────────────────────────────────────┤
 │ 6. SCORE EACH CELL evidence kernel + terrain + canopy + soil  │  15-factor sum
 │                    + twi + riparian + hostTree + moisture     │  × penalty × gate
 ├─────────────────────────────────────────────────────────────┤
 │ 7. CLASSIFY        finalScore → 5 tiers + factor strings      │
 └─────────────────────────────────────────────────────────────┘
              │
              ▼
        List<HotspotCell> → MapScreen
```

Stages 1–3 are computed once per request; stages 5–6 run per cell (5 batched up
front, 6 in the scoring loop `FungiRepository.kt:1211`).

---

## 3. Data sources

Base URLs are wired in `app/.../MyceliumApplication.kt` (DI). The Earth Engine
backend is **optional** — it is only built when `BuildConfig.BACKEND_BASE_URL`
is set (`MyceliumApplication.kt:103`); without it the app runs keyless on the
OSM fallback.

### 3a. Observation evidence (presence records)

| Source | Endpoint | Used for | Code |
|--------|----------|----------|------|
| **iNaturalist** | `api.inaturalist.org/v1/observations` (per-species, research-grade) + `taxon_name=Fungi` area query | citizen-science presence | `INaturalistApi.kt`, `getObservations()` |
| **ALA** (Atlas of Living Australia) | `biocache-ws.ala.org.au/ws/occurrences/search?fq=kingdom:Fungi` | AU herbarium/observation records | `getALAObservations()` `FungiRepository.kt:474` |
| **GBIF** | `api.gbif.org/v1/occurrence/search?kingdomKey=5` (per-species bbox + area-wide) | global museum/herbarium specimens | `getGBIFObservations()` `:518` |
| **User sightings** | local Room DB | first-hand georeferenced finds (highest trust) | `dao.getAllUserSightings()` |

Records are fetched in parallel with retry (`retryIO`), deduped, written to Room
(`dao.insertObservations`), and degrade to **stale cache** offline
(`FungiRepository.kt:459`). GBIF/ALA dates are reconstructed from `year`/`month`
when no full `eventDate` exists (`parseYearMonth` `:557`).

### 3b. Weather & terrain (Open-Meteo, keyless)

| Layer | Endpoint | Detail | Code |
|-------|----------|--------|------|
| Daily rain / temp | `api.open-meteo.com/v1/forecast` | 45 past days: `precipitation_sum`, `temperature_2m_max/min` | `getDetailedWeather()` `:582` |
| Soil moisture | same call, `hourly=soil_moisture_0_to_7cm` | last ~168 h mean | `:599` |
| Elevation | `api.open-meteo.com/v1/elevation` | ≤100 coords/call, per-cell | `fetchElevations()` `:698` |

> Note: weather (rain/temp/moisture) is sampled **once at the search center** and
> applied to every cell. Only soil moisture is partially per-cell (blended with
> the EE 14-day layer, §3c). Elevation **is** per-cell.

### 3c. Environmental layers (Earth Engine backend → `POST /env-grid`)

Cloud Run Flask service (`backend/main.py`), Earth Engine via service-account
ADC. Request cap **600 points**; the app chunks at **500** (`fetchEnvLayers()`
`:903`). Each layer group is independently `try/except`-guarded — a failure
nulls only that column, never the core stack. Response arrays align 1:1 with
input points.

| Field | EE dataset | Native res | Sample scale | Role in scoring |
|-------|-----------|-----------|--------------|-----------------|
| `landcover` | ESA WorldCover v200 2021 | 10 m | 30 m | canopy score + **habitat gate** |
| `canopy` | Hansen GFC 2023 `treecover2000` (%) | 30 m | 30 m | canopy score |
| `ndvi` | Sentinel-2 SR median, last 120 d, cloud <40% | 10 m | 30 m | canopy score + gate veto |
| `water_dist` | JRC Global Surface Water `occurrence≥20`, `fastDistanceTransform` | 30 m | 30 m | riparian |
| `soil_ph` | OpenLandMap pH-H₂O (÷10) | 250 m | 90 m | soil |
| `soil_sand` | OpenLandMap sand wfraction (%) | 250 m | 90 m | soil (drainage) |
| `twi` | MERIT Hydro `ln(upa / tan slope)` | 90 m | 90 m | topographic wetness |
| `soil_moisture` | ERA5-Land daily, 14-day mean vol. water | ~11 km | 10 km | per-cell moisture blend |
| `forest_type` | Copernicus 100 m Proba-V leaf-type (2019) | 100 m | 100 m | **host-tree** match |

### 3d. OSM fallback (only when EE backend is unset/empty)

| Layer | Endpoint | Code |
|-------|----------|------|
| Canopy/green features | Overpass `overpass-api.de` (woods/forest/parks) | `fetchCanopyFeatures()` `:737` |
| Land-use polygons | Overpass | `fetchLandUsePolygons()` `:810` → `classifyLandCell()` `:872` (GREEN/BUILT/NEUTRAL) |

### 3e. Other (not part of scoring)

Google Geocoding (place search), ArcGIS/OpenTopoMap (map tiles), Anthropic API
(photo-ID screen), GitHub releases (in-app update check). Species catalogue +
GBIF backbone search power the field guide (`GBIFApi.searchSpecies`).

---

## 4. Score combination

Per cell, in the scoring loop (`FungiRepository.kt:1211–1366`):

```
finalScore = clamp01( Σ wᵢ·factorᵢ  ×  penaltyMultiplier  ×  habitatGate )
```

### 4a. Weighted sum — 15 factors (weights sum to 1.0)

| Factor | w | Source / function |
|--------|---|-------------------|
| evidence | 0.21 | kernel over records: `quality × source × recency × spatial`, saturates ~4 (`MycoMath.qualityWeight/sourceWeight/recencyWeight/spatialKernel`) |
| season | 0.14 | `MycoMath.seasonalFitness` (circular day-of-year vs window peak) |
| rainTrigger | 0.11 | `MycoMath.rainfallTriggerScore` (≥20 mm/2-day event 10–21 d ago, opt ~15.5 d) |
| canopy | 0.08 | `MycoMath.richCanopyScore` (0.40 canopy + 0.35 landcover + 0.25 NDVI) |
| habitat | 0.08 | `habitatDiversityScore × speciesHabitatWeight` |
| temperature | 0.06 | `temperatureFitness` (species °C band) |
| **hostTree** | 0.05 | `hostTreeMatchScore(forest_type, hostGroups)` (P2) |
| terrain | 0.05 | `terrainMoistureScore` (slope + concavity from neighbour elevations) |
| elevation | 0.05 | `elevationFitness` (species altitude band) |
| soil | 0.04 | `richSoilScore` (0.6 pH + 0.4 drainage) |
| moisture | 0.03 | 0.5 area-moisture + 0.5 EE 14-day soil water |
| twi | 0.03 | `twiWetnessScore` |
| riparian | 0.03 | `riparianScore` (distance to water) |
| aspect | 0.03 | `slopeAspectMoistureScore` (S/E-facing favoured, S. hemisphere) |
| moon | 0.01 | `moonFruitingScore` (folklore) |

Weights live in a `factorWeights` map (`FungiRepository.kt:1308`); the aggregate
function mirrors them as an inline weighted sum (`:1626`).

### 4b. Penalty multiplier (soft season/rain floor)

```
penaltyMultiplier = 0.3 + 0.7 · min(season, rainTrigger + 0.2)      # 0.3 … 1.0
```
Caps a cell when badly out of season or dry, so historical records alone can't
make a dead season look productive (`FungiRepository.kt:1350`).

### 4c. Habitat gate (multiplicative land-cover veto, 0.05–1.0)

`MycoMath.habitatGate(landcover, ndvi, speciesId)` — tree/wetland pass (~1.0);
built-up 0.12, water 0.05, bare 0.18; `NDVI < 0.05` hard-caps at 0.20. Psilocybe
keeps a floor on urban-mulch/pasture. This is what stops cities/roads/car parks
(where citizen records cluster) from ranking high (`MycoMath.kt:546`).

### 4d. Tiers

`classifyTier` (`MycoMath.kt:658`): Excellent ≥0.80, VeryGood ≥0.60,
Promising ≥0.40, Possible ≥0.20, else Unlikely.

### 4e. Neutral-when-absent principle

Every optional layer returns a **neutral** mid-score (≈0.5–0.6) when its data is
missing, so a failed fetch or an un-deployed backend never *penalises* a cell —
it just removes that factor's discriminating power. (e.g. `hostTreeMatchScore`
returns 0.55–0.6 with no forest data; `soilScore` 0.6; `twiScore` 0.5.)

---

## 5. Resolution, batching & caching

### 5a. Adaptive grid

```
cellMeters    = max(250.0, radiusKm·1000 / 60)     # ≥250 m, grows past ~15 km radius
latStep       = cellMeters / 111_000
lngStep       = cellMeters / (111_000 · cos(centerLat))
latRangeSteps = ceil(radiusKm·1000 / cellMeters)   # ≤ ~60 per axis
```
(`FungiRepository.kt:1163` single, `:1520` aggregate.) Typical searches (≤15 km)
get ~250 m cells; very large radii grow the cell so cell count — and EE cost —
stays bounded (~60 steps/axis → ≤ ~11k in-circle cells).

### 5b. Caching

- `elevCache` + `envCache` (`ConcurrentHashMap`), keyed by `gridKey()` which
  snaps to a global grid at `0.00225° lat / 0.00285° lng` (~250 m, ≤ smallest
  cell so adjacent cells never collide) (`FungiRepository.kt:625`).
- Disk-persisted as TSV (`elev_cache.tsv`, `env_cache.tsv`, `CACHE_MAX 8000`),
  loaded lazily (`ensureCachesLoaded`), so revisiting a region is instant/free.
- `env_cache.tsv` is forward/backward compatible: rows with ≥5 fields load core
  layers; ≥9 add soil/moisture/twi; ≥10 add forest_type (`:655`).

### 5c. Degradation ladder

1. EE backend configured + reachable → full per-cell layers.
2. Backend unset or whole grid fails → OSM canopy/land-use fallback
   (`fetchEnvLayers` returns null → `canopy`/`landPolys` path).
3. Elevation fetch fails → neutral terrain (0.5/0.6).
4. Weather fetch fails → Victorian-autumn defaults (`getDetailedWeather` catch).
5. Observations fail → stale Room cache (offline-first).

---

## 6. Refactor seams (where to change things)

| To change… | Edit |
|------------|------|
| Factor weights / add a factor | `factorWeights`/`factorScores` map `FungiRepository.kt:1308` **and** the aggregate inline sum `:1626` (keep Σ = 1.0) |
| A scoring curve | the relevant pure function in `util/MycoMath.kt` (unit-tested in `app/src/test/.../MycoMathTest.kt`) |
| Cell size / grid extent | `cellMeters`/`latStep`/`lngStep` `:1163` & `:1520`; cache snap in `gridKey()` `:625` |
| Add an EE layer | `backend/main.py` (guarded block + response key) → `EnvLayersApi.kt` field → `EnvCell`/`EnvLayers`/cache TSV → consume in both scoring loops |
| Species niche params (temp/elev/host) | `MycoMath.speciesTempRange/speciesElevationBand/speciesHabitatWeight/hostGroupsFor` |
| Habitat gate behaviour | `MycoMath.habitatGate` `:546` |

---

## 7. Known limitations (see PREDICTION_ENGINE_V2.md for the roadmap)

- **No validation/backtest** — weights are hand-tuned and unfalsified.
- **Evidence is sampling-biased** (records cluster where people go); the gate
  removes built-up cells but not in-forest trail bias.
- **Correlated factors double-count** "forest" (canopy/host/ndvi/landcover) and
  "moisture" (twi/terrain/aspect/riparian/moisture).
- **Weather is center-sampled**, not per-cell (fine ≤5 km, weak at 30 km).
- **Most curves are generic**, not species-specific, though the catalogue text
  contains the ecology to make them so.
- `forest_type` is genus-*group* only (needle/broad); AU NVIS/EVC would give
  real community-level hosts.
