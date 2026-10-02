#!/usr/bin/env bash
# Run unchanged JVM test bodies using an installed compiler or the pinned Gradle bundle.
set -euo pipefail
[[ $# -ge 4 ]] || { printf '%s\n' 'Usage: run-standalone-kotlin.sh JAR MAIN OUTPUT SOURCE...' >&2; exit 2; }
IO_TEST_JAR="$1"
IO_TEST_MAIN="$2"
IO_TEST_OUTPUT="$3"
shift 3
command -v java >/dev/null
if command -v kotlinc >/dev/null; then
    kotlinc "$@" -include-runtime -d "$IO_TEST_JAR"
    java -cp "$IO_TEST_JAR" "$IO_TEST_MAIN" "$IO_TEST_OUTPUT"
else
    IO_TEST_REPO="$(cd "$(dirname "$0")/.." && pwd)"
    IO_TEST_LIB="${IO_TOOLCHAIN_ROOT:-$(dirname "$IO_TEST_REPO")/android-toolchain}/gradle-9.7.1/lib"
    shopt -s nullglob
    IO_TEST_STDLIB=("$IO_TEST_LIB"/kotlin-stdlib-*.jar)
    IO_TEST_COMPILER=("$IO_TEST_LIB"/kotlin-compiler-embeddable-*.jar)
    [[ ${#IO_TEST_STDLIB[@]} -eq 1 && ${#IO_TEST_COMPILER[@]} -eq 1 ]] || {
        printf '%s\n' 'Install kotlinc or run the pinned Android bootstrap; set IO_TOOLCHAIN_ROOT if needed.' >&2
        exit 1
    }
    java -Xmx512m -cp "$IO_TEST_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect -classpath "${IO_TEST_STDLIB[0]}" "$@" -d "$IO_TEST_JAR"
    java -cp "$IO_TEST_JAR:${IO_TEST_STDLIB[0]}" "$IO_TEST_MAIN" "$IO_TEST_OUTPUT"
fi
