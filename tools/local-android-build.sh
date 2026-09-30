#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/local-android-env.sh
if [[ $# -eq 0 ]]; then
    set -- :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
fi
exec gradle "$@" --no-daemon --console=plain --no-configuration-cache
