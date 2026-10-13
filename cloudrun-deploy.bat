@echo off
setlocal
set SERVICE_NAME=ultimo-server
if "%GCP_REGION%"=="" set GCP_REGION=europe-west1

echo ==========================================================
echo       Deploying Ultimo Server to Google Cloud Run         
echo ==========================================================

where gcloud >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Google Cloud SDK ('gcloud') is not installed or not in PATH.
    echo Install it from: https://cloud.google.com/sdk/docs/install
    pause
    exit /b 1
)

echo [INFO] Building and deploying '%SERVICE_NAME%' to region '%GCP_REGION%'...
call gcloud run deploy %SERVICE_NAME% ^
    --source . ^
    --region %GCP_REGION% ^
    --allow-unauthenticated ^
    --port 8080 ^
    --memory 512Mi ^
    --cpu 1 ^
    --min-instances 0 ^
    --max-instances 2 ^
    --timeout 3600 ^
    --session-affinity

if %errorlevel% equ 0 (
    echo ==========================================================
    echo [SUCCESS] Deployment completed! Your Ultimo server is live.
    echo Open the printed Service URL in your browser to play!
    echo ==========================================================
)
pause
