#!/usr/bin/env bash
#
# Deploy the Myceliyum Earth Engine environmental-layers backend for PRIVATE
# development/testing.
#
# IMPORTANT SECURITY BOUNDARY
# ---------------------------
# This script uses an unauthenticated Cloud Run endpoint plus a static
# X-Api-Token as a lightweight abuse deterrent. If that token is compiled into
# an Android APK it is extractable. This mode is therefore NOT production
# authentication and must not be used as the only control protecting a public,
# sensitive or materially billable backend.
#
# Public Myceliyum CI/release builds intentionally do not receive this token.
# For a real public backend, design client-safe authentication/authorisation and
# add rate limits, quotas/budgets and abuse monitoring before enabling it.
#
# Prereqs (one-time, see README.md for detail):
#   1. gcloud CLI installed and authenticated.
#   2. Earth Engine API enabled and the project registered for Earth Engine.
#   3. A runtime service account with the required Earth Engine/service-usage
#      permissions. Do not export a service-account private key into the app.
#
# Usage:
#   cd backend
#   GCP_PROJECT=my-project \
#   SERVICE_ACCOUNT=my-sa@my-project.iam.gserviceaccount.com \
#   PRIVATE_CLIENT_MODE=I_UNDERSTAND \
#   ./deploy.sh
#
# Optional overrides:
#   REGION        (default australia-southeast1)
#   SERVICE       (default myceliyum-env)
#   BACKEND_TOKEN (default: a fresh random token)
set -euo pipefail

: "${GCP_PROJECT:?Set GCP_PROJECT=your-project-id}"
: "${SERVICE_ACCOUNT:?Set SERVICE_ACCOUNT=your-sa@your-project.iam.gserviceaccount.com}"
: "${PRIVATE_CLIENT_MODE:?Set PRIVATE_CLIENT_MODE=I_UNDERSTAND after reading backend/README.md}"

if [ "$PRIVATE_CLIENT_MODE" != "I_UNDERSTAND" ]; then
  echo "ERROR: PRIVATE_CLIENT_MODE must be exactly I_UNDERSTAND." >&2
  echo "This deployment mode exposes Cloud Run unauthenticated and relies only" >&2
  echo "on an extractable static client token as a lightweight deterrent." >&2
  exit 1
fi

REGION="${REGION:-australia-southeast1}"
SERVICE="${SERVICE:-myceliyum-env}"
BACKEND_TOKEN="${BACKEND_TOKEN:-$(openssl rand -hex 32)}"

echo "Deploying PRIVATE/DEVELOPMENT backend '$SERVICE' to '$GCP_PROJECT' ($REGION) ..."
echo "WARNING: the static client token is not production authentication."

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
echo " PRIVATE DEVELOPMENT DEPLOYMENT"
echo " URL: ${URL}/"
echo
echo " Keep the token out of Git. For a private local Android build only, place"
echo " BACKEND_BASE_URL and BACKEND_TOKEN in local.properties or local env."
echo " Never treat that token as secret after distributing the APK."
echo "======================================================================"
echo
echo "Smoke test (token intentionally not echoed into shell history by this script):"
echo "  export BACKEND_URL='${URL}'"
echo "  # Use the BACKEND_TOKEN value from the current shell/environment"
echo "  curl -s -X POST \"$BACKEND_URL/env-grid\" \\"
echo "    -H \"X-Api-Token: \$BACKEND_TOKEN\" -H \"Content-Type: application/json\" \\"
echo "    -d '{\"points\": [[-37.8136, 144.9631]]}'"
