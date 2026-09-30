# Local Android verification (2026-09-30)

GitHub Actions minutes are currently exhausted. A real local Android toolchain is
available in this session; it does not use signature stand-ins or consume Actions
minutes. The declared project SDK, Gradle, plugins and dependencies are unchanged.

## Installed toolchain

Transient root: `/workspace/scratch/3e6594abf2c4/android-toolchain`.

* Temurin JDK 17.0.20.1+1, including `javac` (not just a Java runtime).
* Gradle 9.7.1, matching `.github/workflows/build-debug.yml`.
* Google Android command-line tools 19.0.
* SDK platform `android-36.1`, revision 1, API 36.1 / extension 20.
* Android build-tools 36.0.0 and 36.1.0, plus platform-tools.

The JDK archive SHA-256 was checked against its official checksum:
`3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e`.
The Gradle archive SHA-256 was checked against its official checksum:
`acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`.
The command-line-tools archive SHA-1 was checked against Google's repository XML:
`5fdcc763663eefb86a5b8879697aa6088b041e70`.

The scratch root, downloads, caches and `local.properties` are not portable source
artifacts and can disappear during workspace maintenance. Reinstall using the
same official versions if they are missing. Do not infer validation of a later
checkout merely from the existence of an earlier build directory.

## Official bootstrap sources

For a wiped workspace, run the reproducible installer (Python 3.11+ and curl):

```sh
python3 tools/bootstrap-android.py --accept-licenses
```

It verifies the pinned checksums before extracting official archives, restores
executable modes, installs only the declared SDK packages and preserves normal TLS
verification. `--root` selects another toolchain directory; use that same path as
`IO_TOOLCHAIN_ROOT` when building. The license flag applies to this development
installation. The installer does not build the app or certify a test result.
It can be rerun after an interrupted download. Previous project APKs are untouched.

* JDK: `https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz`
  and the adjacent `.sha256.txt` file.
* Gradle: `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip`
  and the adjacent `.sha256` file.
* Command-line tools: `https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip`.
  Validate against `https://dl.google.com/android/repository/repository2-1.xml`.

Extract the JDK and Gradle to the toolchain root. Extract Google's tools to
`sdk/cmdline-tools/latest` (the `bin/sdkmanager` file must be below that directory).
Python ZIP extraction does not preserve executable modes: restore them on Gradle
and the command-line tool scripts. Install the platform/build-tools packages above
using `sdkmanager`; preserve normal TLS and archive validation.

In this execution environment the network proxy address/port is specific to each
exec session. Derive it from that invocation's `HTTPS_PROXY`; do not hard-code the
port or use the inherited unavailable `browser-proxy:8889` value. Import the
runtime-provided proxy CA (`CODEX_PROXY_CERT`) into the downloaded JDK trust store
when HTTPS is intercepted; do not disable certificate verification. Use
`--no-daemon` so a later invocation does not reuse a daemon tied to an expired
proxy session.

Robolectric fetches its SDK jars in a forked test JVM. The local
`gradle-home/init.d/runtime-network-proxy.gradle` forwards the current invocation
proxy system properties into Gradle `Test` tasks. Without this local setup, ordinary
JVM tests run but Robolectric class setup fails to fetch `android-all-instrumented`;
that is an environment failure, not a passed or failed Android behavior check.
The init script only forwards `http.proxyHost`, `http.proxyPort`, `https.proxyHost`,
`https.proxyPort` and `http.nonProxyHosts`; it does not substitute dependencies.

## Verification commands

The checked-in `tools/local-android-env.sh` restores this local environment.
`tools/local-android-build.sh` runs the full gate by default or accepts task arguments.
From the repo:

```sh
source tools/local-android-env.sh
gradle :app:compileDebugKotlin --no-daemon --console=plain --no-configuration-cache
gradle :app:testDebugUnitTest --no-daemon --console=plain --no-configuration-cache
gradle :app:lintDebug --no-daemon --console=plain --no-configuration-cache
gradle :app:assembleDebug --no-daemon --console=plain --no-configuration-cache
```

Equivalent full gate: `tools/local-android-build.sh`.
The successful frozen-source gate used a runtime-only 6 GiB Gradle heap on this
9.7 GiB host, leaving the checked-in Gradle configuration unchanged:

```sh
tools/local-android-build.sh :app:compileDebugKotlin :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebug \
  '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

For a focused forced test run, use task-scoped `--rerun`; `--rerun-tasks` needlessly
rebuilds every resource/compiler prerequisite.

`JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME` and `PATH` point
to the root above. The ephemeral `local.properties` points to its SDK. Logs are
saved in `android-toolchain/logs`; long commands run as resumable exec sessions.
Do not run several Gradle builds concurrently against the same build directory.

Test results: `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
Lint report: `app/build/reports/lint-results-debug.xml`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
A successful Android compile, tests, lint and APK are separate gates. Report their
actual outcomes and the exact source revision; local setup alone passes none of
them. The existing debug keystore must be used for an update-compatible APK.
Physical-device, real-account and hardware acceptance remain separate checks.
