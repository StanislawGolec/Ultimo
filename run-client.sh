#!/usr/bin/env bash
set -e

echo "==================================================="
echo "            URUCHAMIANIE KLIENTA ULTIMO            "
echo "==================================================="

if [ ! -f "target/classes/pl/ultimo/client/TerminalClientApp.class" ]; then
    echo "[INFO] Kompilacja projektu Java 21..."
    mkdir -p target/classes
    javac -d target/classes $(find src/main/java -name "*.java")
fi

java -cp target/classes pl.ultimo.client.TerminalClientApp "$@"
