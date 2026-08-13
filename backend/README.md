# Myceliyum — Environmental Layers Backend

**Optional Cloud Run / Google Earth Engine service for higher-fidelity environmental context.**

> **Security status:** suitable for private development when used with the documented lightweight token mode. The static `X-Api-Token` is **not production authentication for a distributed Android app**, because any value compiled into an APK is extractable. Public Myceliyum CI/release builds intentionally leave this backend disabled.

## Why a backend exists

The useful security boundary is keeping the **Google Cloud / Earth Engine service identity server-side**.

A service-account private key must never be bundled into an Android application. The backend instead runs on Cloud Run under its attached runtime service account and uses Application Default Credentials, so the app never needs the underlying GCP credential.

```text
Android client
     │ HTTPS
     ▼
Cloud Run service
     │ runtime identity / ADC
     ▼
Google Earth Engine
```

That solves the service-account-key problem. It does **not** automatically solve public client authentication or abuse control.

## Current private-development mode

`backend/main.py` can require a static `X-Api-Token` when `BACKEND_TOKEN` is configured on the server.

For a **private/local development APK**, the same token can be supplied through local build configuration so the application can call the backend.

This is intentionally described as an **abuse deterrent**, not a secret-authentication scheme:

- the token is recoverable from a distributed APK;
- copying the APK can expose the token;
- the token does not identify an individual user/device;
- rotating the token invalidates every client built with the old value;
- it does not replace quotas, budgets, rate limiting or server-side authorisation.

Public GitHub CI and release workflows therefore do **not** inject `BACKEND_TOKEN` into distributed builds.

## One-time Earth Engine setup

Use a dedicated Google Cloud project/runtime service account with only the permissions the service requires. The repository's current implementation expects Earth Engine access plus the service-usage permissions needed by `ee.Initialize(project=...)`.

Example project binding pattern:

```bash
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:YOUR_SA@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/earthengine.viewer"

gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:YOUR_SA@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/serviceusage.serviceUsageConsumer"
```

Re-check the exact current Google Cloud/Earth Engine permission requirements before production deployment rather than treating this historical example as permanent platform documentation.

## Private development deployment

The included script makes the insecure-by-design client mode explicit:

```bash
cd backend
GCP_PROJECT=your-project-id \
SERVICE_ACCOUNT=your-sa@your-project-id.iam.gserviceaccount.com \
PRIVATE_CLIENT_MODE=I_UNDERSTAND \
./deploy.sh
```

The script:

- generates a fresh random backend token unless one is supplied;
- deploys the service under the runtime service account;
- enables the unauthenticated Cloud Run route required by this simple token mode;
- requires an explicit acknowledgement before doing so;
- does not recommend committing or hardcoding the token.

For a private local Android build, keep values in ignored local configuration:

```properties
BACKEND_BASE_URL=https://your-private-development-service.example/
BACKEND_TOKEN=your-development-token
```

Once an APK containing the token leaves your control, assume the token is obtainable.

## Production/public deployment requirements

Do not expose a materially billable backend to arbitrary public clients using only a static APK token.

Before enabling this backend in a public build, design and test controls appropriate to the actual service, such as:

- authenticated users and short-lived server-issued credentials;
- server-side authorisation;
- device/app attestation where appropriate;
- request/rate limits;
- per-user or per-principal quotas;
- abuse detection and audit logging;
- Cloud/project budget alerts and hard operational limits where available;
- a clear token/session revocation strategy.

The exact solution depends on the intended public product and threat model; no claim is made that one mechanism above is sufficient by itself.

## Smoke testing a private deployment

Keep the token in the current shell or another secret store rather than pasting it into committed files:

```bash
export BACKEND_URL='https://your-service.example'
export BACKEND_TOKEN='your-private-development-token'

curl -s -X POST "$BACKEND_URL/env-grid" \
  -H "X-Api-Token: $BACKEND_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"points": [[-37.8136, 144.9631], [-37.8386, 145.3524]]}'
```

Representative response shape:

```json
{
  "landcover": [50, 10],
  "canopy": [3.1, 78.4],
  "ndvi": [0.21, 0.74],
  "water_dist": [180.0, null],
  "soil_ph": [5.8, 6.3],
  "soil_sand": [42, 31],
  "soil_moisture": [0.27, 0.19],
  "twi": [7.4, 5.1],
  "forest_type": [2, 1]
}
```

Values above are response-shape examples, not guaranteed live measurements.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | liveness/status |
| `POST` | `/env-grid` | environmental values for up to the server's configured point limit |

The implementation currently aligns result arrays with the submitted point order and allows individual environmental layers to fail soft to `null` where possible.

## Repository security references

- [`../SECURITY.md`](../SECURITY.md) — current client/signing/credential policy
- [`deploy.sh`](deploy.sh) — explicit private-development deployment path
- [`main.py`](main.py) — server implementation and token check

## Portfolio boundary

The meaningful engineering work here is **keeping privileged cloud credentials server-side and integrating an optional environmental data service with a graceful client fallback**. The repository does not claim the current token mode is a production-grade public API security design.
