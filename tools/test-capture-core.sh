#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
command -v kotlinc >/dev/null || { echo 'kotlinc is required' >&2; exit 1; }
command -v java >/dev/null || { echo 'Java is required' >&2; exit 1; }
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
kotlinc "$root"/app/src/main/java/com/example/core/capture/*.kt \
  "$root"/app/src/test/java/com/example/core/capture/*.kt \
  "$root/tools/RunCaptureTests.kt" "$tmp/Test.kt" -include-runtime -d "$tmp/tests.jar"
java -jar "$tmp/tests.jar" "$root/build/capture-checks/export-fixture.json"
