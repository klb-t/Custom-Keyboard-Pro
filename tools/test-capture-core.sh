#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# Only the annotation is supplied for this standalone runner. The test bodies use
# Kotlin check(), not a mock of JUnit assertions. Gradle uses the real JUnit dependency.
cat > "$tmp/Test.kt" <<'KOTLIN'
package org.junit
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
KOTLIN
mkdir -p "$root/build/capture-checks"
bash "$root/tools/run-standalone-kotlin.sh" "$tmp/tests.jar" RunCaptureTestsKt \
  "$root/build/capture-checks/export-fixture.json" \
  "$root"/app/src/main/java/com/example/core/capture/*.kt \
  "$root"/app/src/test/java/com/example/core/capture/*.kt \
  "$root/tools/RunCaptureTests.kt" "$tmp/Test.kt"
