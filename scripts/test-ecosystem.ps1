# Ultimo Polyglot Cross-Ecosystem Integration Test (PowerShell)
$ErrorActionPreference = "Stop"

Write-Host "=======================================================" -ForegroundColor Cyan
Write-Host "Ultimo Polyglot Cross-Ecosystem Integration Test" -ForegroundColor Cyan
Write-Host "=======================================================" -ForegroundColor Cyan

cmd /c "dir /s /b src\main\java\*.java > sources_main.txt && javac -encoding UTF-8 -d target\classes @sources_main.txt && del sources_main.txt && dir /s /b src\test\java\*.java > sources_test.txt && javac -encoding UTF-8 -cp target\classes;.tools\junit-platform-console-standalone-1.10.2.jar -d target\test-classes @sources_test.txt && del sources_test.txt"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "Executing EcosystemIntegrationTest and ChaosResilienceSimulationTest..." -ForegroundColor Yellow
java "-Djdk.tracePinnedThreads=full" -jar .tools/junit-platform-console-standalone-1.10.2.jar execute --class-path "target/classes;target/test-classes" --select-class pl.ultimo.server.net.EcosystemIntegrationTest --select-class pl.ultimo.server.net.ChaosResilienceSimulationTest

if ($LASTEXITCODE -ne 0) {
    Write-Error "Ecosystem integration tests failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "=======================================================" -ForegroundColor Green
Write-Host "Ecosystem Integration Verification PASSED" -ForegroundColor Green
Write-Host "=======================================================" -ForegroundColor Green
