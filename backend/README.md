# Mycelium Mapper — environmental layers backend

A tiny Cloud Run service that exposes Google Earth Engine layers (land cover,
tree-canopy %, NDVI, distance to surface water) to the app, so the
**service-account key never ships in the APK**. Earth Engine runs server-side under the service account's own
identity.

## Why a backend (read this)

A GCP **service-account private key grants project-wide access**. Bundling it
in a distributed Android app is unsafe — APKs are trivially decompiled and the
key extracted, letting anyone run up Earth Engine / compute costs on your
project. This service keeps the credential on Google's infrastructure: when
deployed to Cloud Run *with the service account attached*, it authenticates via
Application Default Credentials and **no key file is needed at all**. Delete any
key you exported.

## One-time Earth Engine setup

1. Enable the **Earth Engine API** on the project (you already have it).
2. Register the project for Earth Engine: https://code.earthengine.google.com/
   (or `earthengine` CLI), and grant the service account access:
   ```bash
   gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
     --member="serviceAccount:YOUR_SA@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
     --role="roles/earthengine.viewer"
   ```

## Deploy (no key file)

```bash
cd backend
# Optional shared-secret the app will send as X-Api-Token:
TOKEN=$(openssl rand -hex 16)

gcloud run deploy myceliyum-env \
  --source . \
  --project YOUR_PROJECT_ID \
  --region australia-southeast1 \
  --service-account YOUR_SA@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --set-env-vars "GCP_PROJECT=YOUR_PROJECT_ID,BACKEND_TOKEN=$TOKEN" \
  --allow-unauthenticated

echo "Backend token (put in local.properties as BACKEND_TOKEN): $TOKEN"
```

The command prints a service URL. Put both into the app's `local.properties`:

```
BACKEND_BASE_URL=https://myceliyum-env-XXXX-ts.a.run.app/
BACKEND_TOKEN=<the token printed above>
```

> Prefer to lock it down further? Deploy without `--allow-unauthenticated` and
> have the app attach a Google ID token. The shared token is the simpler guard.

## Test before wiring the app

```bash
curl -s -X POST "$URL/env-grid" \
  -H "X-Api-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"points": [[-37.8136, 144.9631], [-37.8386, 145.3524]]}' | jq
# → {"landcover":[50,10], "canopy":[3.1, 78.4], "ndvi":[0.21, 0.74],
#    "water_dist":[180.0, null],     # metres to nearest water; null = >2 km / no data
#    "soil_ph":[5.8, 6.3], "soil_sand":[42, 31],   # surface soil pH / sand mass %
#    "soil_moisture":[0.27, 0.19],   # 14-day mean volumetric soil water (m³/m³)
#    "twi":[7.4, 5.1]}               # topographic wetness index (higher = wetter)
```

The `soil_*` and `twi` fields are the v2 ecology layers (see
`docs/PREDICTION_ENGINE_V2.md`). Each is sampled independently and guarded, so a
failure in any one returns nulls for that layer only — the core layers above are
never affected.

## Endpoints

| Method | Path        | Body / Result |
|--------|-------------|---------------|
| GET    | `/health`   | `{"status":"ok"}` |
| POST   | `/env-grid` | `{"points":[[lat,lng],...]}` (≤600) → `{"landcover":[...],"canopy":[...],"ndvi":[...],"water_dist":[...],"soil_ph":[...],"soil_sand":[...],"soil_moisture":[...],"twi":[...]}` aligned 1:1 |
