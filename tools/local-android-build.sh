#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/local-android-env.sh
if [[ $# -eq 0 ]]; then
    # Release compiler/test memory before lint and dexing. These are still the
    # complete gates; only scheduling changes on a constrained development host.
    gradle :app:compileDebugKotlin :app:testDebugUnitTest --max-workers=1 --no-daemon --console=plain --no-configuration-cache
    gradle :app:lintDebug --max-workers=1 --no-daemon --console=plain --no-configuration-cache
    exec gradle :app:assembleDebug --max-workers=1 --no-daemon --console=plain --no-configuration-cache
fi
exec gradle "$@" --no-daemon --console=plain --no-configuration-cache
