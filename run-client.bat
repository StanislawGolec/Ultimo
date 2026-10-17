@echo off
title Ultimo Client
echo ===================================================
echo             URUCHAMIANIE KLIENTA ULTIMO
echo ===================================================

if not exist "target\classes\pl\ultimo\client\TerminalClientApp.class" (
    echo [INFO] Kompilacja projektu Java 21...
    if not exist "target\classes" mkdir "target\classes"
    javac -d target/classes src/main/java/pl/ultimo/core/model/*.java src/main/java/pl/ultimo/core/state/*.java src/main/java/pl/ultimo/core/engine/*.java src/main/java/pl/ultimo/protocol/*.java src/main/java/pl/ultimo/server/config/*.java src/main/java/pl/ultimo/server/metrics/*.java src/main/java/pl/ultimo/server/net/*.java src/main/java/pl/ultimo/server/room/*.java src/main/java/pl/ultimo/server/*.java src/main/java/pl/ultimo/client/*.java
    if errorlevel 1 (
        echo [BLAD] Blad kompilacji!
        pause
        exit /b %errorlevel%
    )
)

java -cp target/classes pl.ultimo.client.TerminalClientApp %*
pause
