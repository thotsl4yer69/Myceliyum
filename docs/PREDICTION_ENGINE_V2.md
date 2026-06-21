# Myceliyum — Prediction Engine v2 (ecology-aware, data-driven)

> Status: design / proposal. Nothing here is wired up yet. This document is the
> plan for moving from the current hand-tuned heuristic to an ecology-aware,
> learned model, and a catalogue of the data sources that feed it.

## 1. Why v2

The current engine (`FungiRepository.generateHotspots` / `MycoMath`) is a
**hand-tuned weighted sum** of factor scores, multiplied by a season/rain
penalty and a habitat gate:

```
score = Σ wᵢ·factorᵢ  ×  seasonRainPenalty  ×  habitatGate
```

It is a reasonable v1, but it has three structural ceilings:

1. **It doesn't learn.** The weights are guesses. It can't discover that, say,
   soil moisture matters 3× more than air temperature for *Lactarius deliciosus*
   in temperate pine plantations.
2. **It has no host-tree model.** Most prized edibles are *mycorrhizal* — bound
   to specific trees (pine, oak, eucalyptus, birch). We model "is there canopy",
   not "is there the *right* tree". This is the single biggest accuracy gap.
3. **It takes snapshots, not dynamics.** Fruiting is a *response over time* to a
   rain → moisture → temperature sequence. We sample one rainfall number, not
   the antecedent moisture trajectory.

v2 fixes all three: a per-species **species distribution model (SDM)** trained
on historical fruiting records against real environmental covariates, served
from the existing Cloud Run backend.

## 2. Data sources catalogue ("where else can we gather data")

Grouped by role. **Key?** = does it need an API key / account.
Most of the high-value sources are **free** or reached through Earth Engine
(which the backend already integrates).

### 2.1 Occurrence records — the training *labels*

| Source | What | Key? | Value |
|---|---|---|---|
| iNaturalist *(in use)* | Dated, geolocated, photo-verified fungal obs | No | ★★★ Core labels |
| GBIF | Global aggregator (herbaria, ALA, MycoPortal…), millions of fungal records | Free acct (optional) | ★★★ Biggest label pool |
| Atlas of Living Australia *(in use)* | AU records | No | ★★★ (AU) |
| Mushroom Observer | Community obs with rich metadata + data dumps | No | ★★ |
| MyCoPortal | ~5M North-American fungal records | via GBIF | ★★ |
| GBIF/UNITE (DNA) | ITS-sequenced occurrences (ground-truth IDs) | Free | ★ Quality labels |

### 2.2 Host trees / vegetation — the #1 ecological covariate

| Source | What | Key? | Value |
|---|---|---|---|
| GBIF / ALA plant occurrences | Tree **species** locations → mycorrhizal host presence | Free | ★★★ Highest single lever |
| iNaturalist plant obs | Co-located host trees | No | ★★ |
| OSM `natural=tree`, `genus=*`, `species=*` | Free tree tags (patchy) | No | ★ |
| EE tree-species products (e.g. EU tree species, US FIA) | Modelled species maps (region-dependent) | EE | ★★ where available |

### 2.3 Moisture & climate — the *trigger*

| Source | What | Key? | Value |
|---|---|---|---|
| Open-Meteo *(in use)* | Free historical + forecast, soil moisture/temp | No | ★★★ |
| ERA5-Land (Copernicus) | Gold-standard hourly reanalysis: soil moisture, soil temp, precip | Free acct (CDS) **or** via EE | ★★★ Best moisture history |
| NASA SMAP | Satellite soil moisture | EE / Earthdata | ★★ |
| NASA POWER | Free daily meteorology + solar, no key | No | ★★ |
| Google Weather API *(key available)* | Hyperlocal current/forecast | Yes | ★★ Incremental |
| Google Pollen API *(key available)* | Tree-pollen index → weak host-tree proxy | Yes | ★ Experimental |

### 2.4 Soil

| Source | What | Key? | Value |
|---|---|---|---|
| SoilGrids (ISRIC) | Global pH, organic carbon, texture @250 m | No / EE | ★★★ Separates acid- vs lime-loving spp |
| OpenLandMap | Soil + land via EE | EE | ★★ |
| TERN / national soil grids | Higher-res regional soil | varies | ★★ (regional) |

### 2.5 Terrain

| Source | What | Key? | Value |
|---|---|---|---|
| Copernicus DEM / SRTM / NASADEM (EE) | Elevation → slope, aspect, **Topographic Wetness Index** (where water pools) | EE | ★★★ TWI is a real moisture proxy |
| Open-Meteo elevation *(in use)* | Point elevation | No | ★★ |

### 2.6 Land cover / greenness / disturbance

| Source | What | Key? | Value |
|---|---|---|---|
| ESA WorldCover *(backend)* | 10 m land-cover classes | EE | ★★★ |
| Hansen canopy *(backend)* | Tree-canopy % | EE | ★★ |
| Sentinel-2 NDVI *(backend)* | Greenness | EE | ★★ |
| Dynamic World (Google/WRI) | Near-real-time 10 m land cover | EE | ★★ Fresher than WorldCover |
| MODIS / FIRMS burned area | Recent fire → **pyrophilous fungi** (morels!) | EE | ★★ Niche but high-precision |

### 2.7 Keys / accounts worth creating (all free)

- **GBIF account** — higher download limits + host-tree occurrences.
- **NASA Earthdata** — SMAP, POWER, MODIS.
- **Copernicus CDS** — ERA5-Land (or just reach it through Earth Engine).
- **Earth Engine service account** — already required by `backend/`; unlocks
  SoilGrids, ERA5, SMAP, DEM/TWI, Dynamic World, burned-area in one place.

> Takeaway: **most of the high-value data needs no new paid key** — it's free or
> already reachable via Earth Engine. The Google keys you have (Weather, Pollen,
> Elevation) are *incremental*, not the foundation.

## 3. v2 architecture

```
              ┌─────────────────────────── Cloud Run backend (extend backend/) ──────────────────────────┐
  app  ──►    │  /env-grid  (existing: landcover, canopy, ndvi, water_dist)                               │
  grid        │  /features  (NEW: + soil pH/texture, ERA5 soil-moisture lag, TWI, host-tree density,      │
  points      │              burned-area age, phenology index)  — aligned 1:1 with points                 │
              │  /predict   (NEW: per-species probability from the trained SDM)                           │
              └──────────────────────────────────────────────────────────────────────────────────────────┘
                        ▲                                                   ▲
                        │ Earth Engine (SoilGrids, ERA5, DEM, DW, FIRMS)    │ model artifact (per-species)
                        │                                                   │
              ┌─────────┴───────────────── offline training pipeline ──────┴──────────┐
              │ labels: iNat/GBIF/ALA fruiting records (presence) + background pts     │
              │ features: the same EE layers sampled at each record's date+location    │
              │ model: gradient-boosted trees / MaxEnt per species (or multi-species)  │
              │ output: compact model artifacts deployed with the backend             │
              └───────────────────────────────────────────────────────────────────────┘
```

Key idea: **features are computed at the observation's date**, not today — so the
model learns the *temporal* relationship (rain 14 days ago → fruiting now), which
the current snapshot engine cannot.

## 4. The model

- **Type:** start with per-species **gradient-boosted trees** (handles mixed
  numeric/categorical, non-linear, robust to missing data) or **MaxEnt**
  (presence-background, the classic SDM). Both are light to train and serve.
- **Labels:** research-grade fruiting records = presence; sampled background
  points (same region/season distribution) = pseudo-absence.
- **Features per record:** soil moisture at lags (0/7/14/21 d), soil temp, API
  (antecedent precipitation index), VPD, NDVI, canopy %, land-cover class, soil
  pH/texture, elevation, slope, aspect, TWI, **host-tree density within 200 m**,
  days-since-fire, day-of-year (phenology), recent-nearby-record density.
- **Output:** calibrated probability per cell per species; aggregate = max/þnoisy-or
  across in-season species. Calibration matters so the legend % is honest.
- **Validation:** spatial block cross-validation (not random) to avoid leakage;
  report AUC/precision per species; keep a held-out region.

## 5. Phased rollout

- **P0 — Deploy the backend** *(blocks everything; you run `backend/deploy.sh`)*.
  Unlocks all Earth Engine data in one place.
- **P1 — Richer feature layers** (no model yet): add SoilGrids pH/texture,
  ERA5/SMAP soil-moisture **lags**, DEM-derived **TWI**, Dynamic World land
  cover to `/env-grid`. Feed them into the *existing* weighted engine for an
  immediate accuracy bump while v2 is built.
- **P2 — Host-tree layer** — ingest GBIF/ALA tree occurrences; compute per-cell
  host-tree density for each species' known associates. Biggest single jump.
- **P3 — Trained SDM** — build the offline training pipeline, train per-species
  models on iNat/GBIF/ALA history, serve via `/predict`. App switches from the
  weighted sum to model probabilities. Keep the heuristic as a fallback.
- **P4 — Dynamics & niche signals** — phenology learned from records, burned-area
  for morels, forecast-driven "fruiting soon" (rain just fell → predict +10 d).

## 6. Risks & honest caveats

- **Label bias:** observation records cluster near cities/trails. Must correct
  with spatial thinning + background sampling, or the model just predicts "near
  people".
- **Earth Engine quotas/latency:** per-request grid sampling is fine; the
  training pull is heavy and should be batched/exported.
- **Per-species data sparsity:** rare species won't have enough records — fall
  back to genus-level or the heuristic.
- **Compute home:** the model trains offline and serves as a small artifact; it
  does **not** run in the APK. Keeps the app thin.
- **Honesty of output:** probabilities must be calibrated; never present a learned
  score as certainty. Foraging IDs remain the user's responsibility.
