@echo off
setlocal
echo =======================================================
echo Ultimo Polyglot Cross-Ecosystem Integration Test
echo =======================================================

echo Compiling sources...
call cmd /c "dir /s /b src\main\java\*.java > sources_main.txt && javac -encoding UTF-8 -d target\classes @sources_main.txt && del sources_main.txt && dir /s /b src\test\java\*.java > sources_test.txt && javac -encoding UTF-8 -cp target\classes;.tools\junit-platform-console-standalone-1.10.2.jar -d target\test-classes @sources_test.txt && del sources_test.txt"
if %ERRORLEVEL% neq 0 (
    echo Compilation failed!
    exit /b 1
)

echo Executing EcosystemIntegrationTest and ChaosResilienceSimulationTest...
java "-Djdk.tracePinnedThreads=full" -jar .tools/junit-platform-console-standalone-1.10.2.jar execute --class-path "target/classes;target/test-classes" --select-class pl.ultimo.server.net.EcosystemIntegrationTest --select-class pl.ultimo.server.net.ChaosResilienceSimulationTest
if %ERRORLEVEL% neq 0 (
    echo Ecosystem integration test failed!
    exit /b 1
)

echo =======================================================
echo Ecosystem Integration Verification PASSED
echo =======================================================
