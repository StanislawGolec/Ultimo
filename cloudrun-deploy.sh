#!/usr/bin/env bash
set -e

SERVICE_NAME="${SERVICE_NAME:-ultimo-server}"
REGION="${GCP_REGION:-europe-west1}"

echo "=========================================================="
echo "      Deploying Ultimo Server to Google Cloud Run         "
echo "=========================================================="

if ! command -v gcloud &> /dev/null; then
    echo "[ERROR] Google Cloud SDK ('gcloud') is not installed or not in PATH."
    echo "Install it from: https://cloud.google.com/sdk/docs/install"
    exit 1
fi

echo "[INFO] Building and deploying '$SERVICE_NAME' to region '$REGION'..."
gcloud run deploy "$SERVICE_NAME" \
    --source . \
    --region "$REGION" \
    --allow-unauthenticated \
    --port 8080 \
    --memory 512Mi \
    --cpu 1 \
    --min-instances 0 \
    --max-instances 2 \
    --timeout 3600 \
    --session-affinity

echo "=========================================================="
echo "[SUCCESS] Deployment completed! Your Ultimo server is live."
echo "Open the Service URL in your browser to play instantly!"
echo "=========================================================="
