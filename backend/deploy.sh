#!/usr/bin/env bash
#
# Turnkey deploy for the Myceliyum Earth Engine env-layers backend.
#
# Prereqs (one-time, see README.md for detail):
#   1. gcloud CLI installed and `gcloud auth login` done.
#   2. Earth Engine API enabled on the project, and the project registered
#      for Earth Engine at https://code.earthengine.google.com/.
#   3. A service account with BOTH `roles/earthengine.viewer` (or broader) and
#      `roles/serviceusage.serviceUsageConsumer` — the latter is required for
#      ee.Initialize(project=...) to call the Service Usage API, e.g.:
#        gcloud projects add-iam-policy-binding "$GCP_PROJECT" \
#          --member="serviceAccount:$SERVICE_ACCOUNT" \
#          --role="roles/serviceusage.serviceUsageConsumer"
#
# Usage:
#   cd backend
#   GCP_PROJECT=my-project \
#   SERVICE_ACCOUNT=my-sa@my-project.iam.gserviceaccount.com \
#   ./deploy.sh
#
# Optional overrides: REGION (default australia-southeast1),
#                     SERVICE  (default myceliyum-env),
#                     BACKEND_TOKEN (default: a fresh random token).
#
# On success it prints the service URL and the API token — put BOTH into the
# Android build (either as GitHub Actions secrets BACKEND_BASE_URL /
# BACKEND_TOKEN, or hardcoded in app/build.gradle.kts).
set -euo pipefail

: "${GCP_PROJECT:?Set GCP_PROJECT=your-project-id}"
: "${SERVICE_ACCOUNT:?Set SERVICE_ACCOUNT=your-sa@your-project.iam.gserviceaccount.com}"
REGION="${REGION:-australia-southeast1}"
SERVICE="${SERVICE:-myceliyum-env}"
BACKEND_TOKEN="${BACKEND_TOKEN:-$(openssl rand -hex 16)}"

echo "Deploying '$SERVICE' to project '$GCP_PROJECT' ($REGION) as $SERVICE_ACCOUNT ..."

gcloud run deploy "$SERVICE" \
  --source . \
  --project "$GCP_PROJECT" \
  --region "$REGION" \
  --service-account "$SERVICE_ACCOUNT" \
  --set-env-vars "GCP_PROJECT=$GCP_PROJECT,BACKEND_TOKEN=$BACKEND_TOKEN" \
  --allow-unauthenticated

URL="$(gcloud run services describe "$SERVICE" \
  --project "$GCP_PROJECT" --region "$REGION" \
  --format='value(status.url)')"

echo
echo "======================================================================"
echo " Deployed. Wire these into the Android build:"
echo "   BACKEND_BASE_URL = ${URL}/"
echo "   BACKEND_TOKEN    = ${BACKEND_TOKEN}"
echo "======================================================================"
echo
echo "Smoke test:"
echo "  curl -s -X POST \"$URL/env-grid\" \\"
echo "    -H \"X-Api-Token: $BACKEND_TOKEN\" -H \"Content-Type: application/json\" \\"
echo "    -d '{\"points\": [[-37.8136, 144.9631]]}'"
