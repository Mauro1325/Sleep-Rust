#!/usr/bin/env bash
# Offline harness for the PlayerRevive death bridge and pure Java state/configuration logic.
# It does NOT exercise the Minecraft/Forge event wiring, packets or rendering; test those on a real server.
set -euo pipefail
cd "$(dirname "$0")"
MAIN=../../main/java/com/example/persistentbodies
OUT=$(mktemp -d)
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi

mkdir -p "$OUT/bridge" "$OUT/bad" "$OUT/logic"
"${JAVAC[@]}" -d "$OUT/bridge" $(find stubs replica oldbridge -name '*.java') tests/BridgeTests.java tests/RetryPolicyTests.java "$MAIN/compat/PlayerReviveBridge.java" "$MAIN/logic/DeathRetryPolicy.java"
"${JAVAC[@]}" -d "$OUT/bad" $(find stubs replica_bad -name '*.java') tests/ApiMissingTest.java "$MAIN/compat/PlayerReviveBridge.java"
"${JAVAC[@]}" -d "$OUT/logic" "$MAIN/logic/DownedRegistry.java" "$MAIN/logic/ReviveCostRule.java" "$MAIN/logic/DeathRetryPolicy.java" tests/RetryPolicyTests.java tests/ReviveCostTests.java tests/DownedRegistryTests.java

for spec in "bridge RetryPolicyTests" "bridge BridgeTests" "bad ApiMissingTest" "logic RetryPolicyTests" "logic ReviveCostTests" "logic DownedRegistryTests"; do
  set -- $spec
  echo "######## $2"; java -cp "$OUT/$1" "$2"
done
echo "ALL HARNESS SUITES PASSED"
