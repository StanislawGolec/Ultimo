#!/usr/bin/env bash
set -e

echo "==================================================="
echo "            URUCHAMIANIE SERWERA ULTIMO            "
echo "==================================================="

if [ ! -f "target/classes/pl/ultimo/server/ServerApp.class" ]; then
    echo "[INFO] Kompilacja projektu Java 21..."
    mkdir -p target/classes
    javac -d target/classes $(find src/main/java -name "*.java")
fi

echo "[INFO] Serwer nasłuchuje na porcie 8080..."
java -cp target/classes pl.ultimo.server.ServerApp "$@"
