# Experiments and reproducibility

The development commits remain reachable from `main` and the preserved work branches.
This index separates reproducible mechanisms from unavailable provider evidence.

| Work | Source checkpoint | Reproduction | Evidence boundary |
|---|---|---|---|
| Accessibility conversation capture | `5566d82`, `aa3adf7` | `tools/test-capture-core.sh`, `python3 tools/test-capture-adapter.py`, `python3 tools/test_browser_capture.py` | JVM mechanisms, fake platform adapter and offline browser fixtures; real application/device capture remains separate. |
| Reviewed goals and reactive light | `badd252` | `tools/test-goal-core.sh`, complete Gradle unit suite | Typed plans, prerequisites, review/receipt binding and level-to-colour pipeline; physical microphone/bulb acceptance separate. |
| Scoped keyboard/phone/voice host | `d4d8c4e`, `c75e41c` | `tools/local-android-build.sh` | Earlier exact gate in `../validation/2026-09-30-core-gate.json`; does not certify later changes. |
| Task profiles, strict JSON and clipboard recovery | `654a422`, `1aa502c`, `cdc6c5f`, `d7a861b`, `dd739d1` | Complete Gradle unit/lint/assembly gate | Integrated source; current gate is recorded in the new validation receipt. |
| Real OpenRouter planning experiment | `tools/evaluate-goal-ai.py` + `GoalEvaluationBridgeTest` | `../GOAL_AI_EVALUATION.md` | Historical recovery notes report 45 baseline + 12 comparison calls, USD 1.098135722; raw ledger and final production-validation outputs were not recovered. No certified quality score or automatic paid replay. |
| Shared voice/text agent lifecycle | `core/assistant/GoalAgent.kt` | `GoalAgentTest`, standalone goal tests and full Android gate | Local agent lifecycle with frozen review and stale-result rejection; no cloud deployment implied. |

No failed/unknown experiment is promoted as a successful result. The raw first responses,
request ledger, costs, prompts, provider metadata and production parser results must be
present together before a live model comparison is accepted. The missing historical
ledger cannot be reconstructed from a quoted total or a later synthetic replay.

Do not commit provider credentials, screen/clipboard data, real device inventories or
private handoff uploads. Saved fixtures use synthetic inputs. Model evaluations do not
execute device effects.
