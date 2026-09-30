#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then
  printf '%s\n' 'Usage: tools/goal-eval-bridge.sh /absolute/evaluation-directory' >&2
  exit 2
fi
[[ "$1" = /* ]] || { printf '%s\n' 'Evaluation directory must be absolute.' >&2; exit 2; }
mkdir -p "$1/responses"
IO_GOAL_EVAL_DIR="$1" "${IO_GRADLE_BIN:-gradle}" :app:testDebugUnitTest \
  --rerun --tests com.example.assistant.GoalEvaluationBridgeTest \
  --no-daemon --console=plain --no-configuration-cache
