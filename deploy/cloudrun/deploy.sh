#!/usr/bin/env bash
# One-shot Cloud Run setup + deploy for the Compose preview server.
#
# Usage:
#   PROJECT_ID=my-proj REGION=us-central1 deploy/cloudrun/deploy.sh
#
# What it does (idempotent):
#   1. Enables the required APIs.
#   2. Creates an Artifact Registry Docker repo (if missing).
#   3. Creates the `compose-preview-token` secret with a fresh random token (if missing).
#   4. Submits the Cloud Build pipeline, which builds the image and deploys it.
#   5. Prints the service URL and a ready-to-open, token-bearing render link.
#
# Run from the repo root. Needs: gcloud (authenticated), an active billing account.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
REPO="${REPO:-compose-preview}"
SERVICE="${SERVICE:-compose-preview}"
SECRET="${SECRET:-compose-preview-token}"

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "==> Enabling APIs"
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com

echo "==> Ensuring Artifact Registry repo '${REPO}' in ${REGION}"
gcloud artifacts repositories describe "${REPO}" --location "${REGION}" >/dev/null 2>&1 || \
  gcloud artifacts repositories create "${REPO}" \
    --repository-format=docker --location="${REGION}" \
    --description="compose-preview images"

echo "==> Ensuring secret '${SECRET}'"
if ! gcloud secrets describe "${SECRET}" >/dev/null 2>&1; then
  gcloud secrets create "${SECRET}" --replication-policy=automatic
  TOKEN="$(openssl rand -hex 24)"
  printf '%s' "${TOKEN}" | gcloud secrets versions add "${SECRET}" --data-file=-
  echo "    created a new random token (stored in Secret Manager)"
fi

# Let the Cloud Run runtime service account read the secret.
PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
RUNTIME_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
gcloud secrets add-iam-policy-binding "${SECRET}" \
  --member="serviceAccount:${RUNTIME_SA}" \
  --role=roles/secretmanager.secretAccessor >/dev/null

echo "==> Building + deploying via Cloud Build"
gcloud builds submit \
  --config deploy/cloudrun/cloudbuild.yaml \
  --substitutions="_REGION=${REGION},_REPO=${REPO},_SERVICE=${SERVICE}"

URL="$(gcloud run services describe "${SERVICE}" --region "${REGION}" --format='value(status.url)')"
TOKEN="$(gcloud secrets versions access latest --secret="${SECRET}")"

echo
echo "==> Deployed: ${URL}"
echo "    Open the preview index:   ${URL}/?token=${TOKEN}"
echo "    (Keep the token secret — it is the only gate on this public endpoint.)"
