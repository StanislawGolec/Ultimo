@echo off
title Ultimo Server
echo ===================================================
echo             URUCHAMIANIE SERWERA ULTIMO
echo ===================================================

if not exist "target\classes\pl\ultimo\server\ServerApp.class" (
    echo [INFO] Kompilacja projektu Java 21...
    if not exist "target\classes" mkdir "target\classes"
    javac -d target/classes src/main/java/pl/ultimo/core/model/*.java src/main/java/pl/ultimo/core/state/*.java src/main/java/pl/ultimo/core/engine/*.java src/main/java/pl/ultimo/protocol/*.java src/main/java/pl/ultimo/server/config/*.java src/main/java/pl/ultimo/server/net/*.java src/main/java/pl/ultimo/server/room/*.java src/main/java/pl/ultimo/server/*.java src/main/java/pl/ultimo/client/*.java
    if errorlevel 1 (
        echo [BLAD] Blad kompilacji!
        pause
        exit /b %errorlevel%
    )
)

echo [INFO] Serwer nasluchuje na porcie 8080...
java -cp target/classes pl.ultimo.server.ServerApp %*
pause
