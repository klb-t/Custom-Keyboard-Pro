#!/usr/bin/env bash
# Source this file before a local build. It never changes repository dependencies.
IO_REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export IO_TOOLCHAIN_ROOT="${IO_TOOLCHAIN_ROOT:-$(dirname "$IO_REPOSITORY_ROOT")/android-toolchain}"
export JAVA_HOME="$IO_TOOLCHAIN_ROOT/jdk-17.0.20.1+1"
export ANDROID_HOME="$IO_TOOLCHAIN_ROOT/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$IO_TOOLCHAIN_ROOT/gradle-home"
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -x "$IO_TOOLCHAIN_ROOT/gradle-9.7.1/bin/gradle" || ! -f "$ANDROID_HOME/platforms/android-36.1/android.jar" ]]; then
    printf '%s\n' 'Exact local Android toolchain missing; see docs/LOCAL_ANDROID_BUILD.md.' >&2
    return 1 2>/dev/null || exit 1
fi
export PATH="$JAVA_HOME/bin:$IO_TOOLCHAIN_ROOT/gradle-9.7.1/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# Runtime proxy ports are per exec invocation; persistent Gradle daemons cannot reuse them.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    read -r IO_PROXY_HOST IO_PROXY_PORT < <(python3 - <<'PY'
import os, urllib.parse
u = urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
if u.hostname and u.port:
    print(u.hostname, u.port)
PY
)
    if [[ -n "${IO_PROXY_HOST:-}" && -n "${IO_PROXY_PORT:-}" ]]; then
        export GRADLE_OPTS="-Dhttp.proxyHost=$IO_PROXY_HOST -Dhttp.proxyPort=$IO_PROXY_PORT -Dhttps.proxyHost=$IO_PROXY_HOST -Dhttps.proxyPort=$IO_PROXY_PORT -Dhttp.nonProxyHosts=localhost|127.*"
    fi
fi
# Keep ordinary TLS verification. Import only the CA supplied by this runtime.
if [[ -f "${CODEX_PROXY_CERT:-}" ]] && ! "$JAVA_HOME/bin/keytool" -list -alias codex-network-proxy -cacerts -storepass changeit >/dev/null 2>&1; then
    "$JAVA_HOME/bin/keytool" -importcert -alias codex-network-proxy -file "$CODEX_PROXY_CERT" -cacerts -storepass changeit -noprompt >&2
fi
mkdir -p "$GRADLE_USER_HOME/init.d"
cat > "$GRADLE_USER_HOME/init.d/runtime-network-proxy.gradle" <<'GRADLE'
// Robolectric's Maven SDK downloader runs inside a forked Test JVM.
allprojects {
    tasks.withType(Test).configureEach {
        ['http.proxyHost', 'http.proxyPort', 'https.proxyHost', 'https.proxyPort', 'http.nonProxyHosts'].each { key ->
            def value = System.getProperty(key)
            if (value != null) systemProperty(key, value)
        }
    }
}
GRADLE
