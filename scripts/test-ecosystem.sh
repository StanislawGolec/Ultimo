#!/usr/bin/env bash
set -euo pipefail

echo "======================================================="
echo "Ultimo Polyglot Cross-Ecosystem Integration Test"
echo "======================================================="

mkdir -p target/classes target/test-classes

echo "Compiling sources..."
find src/main/java -name "*.java" > sources_main.txt
javac -encoding UTF-8 -d target/classes @sources_main.txt
rm -f sources_main.txt

find src/test/java -name "*.java" > sources_test.txt
javac -encoding UTF-8 -cp "target/classes:.tools/junit-platform-console-standalone-1.10.2.jar" -d target/test-classes @sources_test.txt
rm -f sources_test.txt

echo "Executing EcosystemIntegrationTest, ChaosResilienceSimulationTest, and MultiAgentGameplayIntegrationTest..."
java -Djdk.tracePinnedThreads=full -jar .tools/junit-platform-console-standalone-1.10.2.jar execute \
  --class-path "target/classes:target/test-classes" \
  --select-class pl.ultimo.server.net.EcosystemIntegrationTest \
  --select-class pl.ultimo.server.net.ChaosResilienceSimulationTest \
  --select-class pl.ultimo.server.net.MultiAgentGameplayIntegrationTest

echo "======================================================="
echo "Ecosystem Integration Verification PASSED"
echo "======================================================="
