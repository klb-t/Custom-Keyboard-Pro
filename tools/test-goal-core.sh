#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/goal-checks
mkdir -p "$out"
# Only the annotation is a shim. Production JVM mechanisms and test bodies run unchanged.
cat > "$out/Test.kt" <<'KOTLIN'
package org.junit
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test
KOTLIN
bash tools/run-standalone-kotlin.sh "$out/tests.jar" RunGoalTestsKt "$out/light-requests.json" \
  app/src/main/java/com/example/core/assistant/*.kt app/src/main/java/com/example/core/devices/*.kt \
  app/src/test/java/com/example/core/assistant/*Test.kt app/src/test/java/com/example/core/devices/ReactiveLightTest.kt \
  "$out/Test.kt" tools/RunGoalTests.kt
