# Integration and recovery — 2026-10-02

## Recovered evidence

Read the remote repository branches, all stacked pull requests, checked-in recovery
notes and feature contracts. Prior-conversation search recovered the Sept 27–Oct 2
IO Matrix continuation, goal-assistant patch and overnight checkpoint context. This
is a recovery from available evidence; unavailable/unwritten transcript fragments
and child-agent sessions cannot be certified as a complete archive.

| Preserved source branch | Recovered head | Decision |
|---|---|---|
| `claude/keyboard-app-all-features-78fkrd` | `9b76a39` | Existing Android/Matrix/clipboard/sync development included. |
| `feat/conversation-capture-2026-09-29` | `b3db2c8` | Capture and accessibility experiments included. |
| `feat/goal-assistant-2026-09-29` | `badd252` | Reviewed goal assistant and reactive-light implementation included. |
| `feat/ai-task-profiles-2026-10-01` | `654a422` | Typed persisted profiles included. |
| `feat/phone-workspace-2026-09-30` | `dd739d1` | Canonical recovered combined source. |
| `main` | `8e8aa9f` | Licenses, ecosystem note and repository hygiene preserved. |

All implementation branch heads are ancestors of `dd739d1`; they do not require
cherry-pick copies or squashing. The former `main` diverged at `d1dd5f9` with eight
main-side and 62 development-side commits. A normal merge reconciles licenses and
scaffold cleanup, retaining both histories and every original commit. Further
changes are ordinary small commits. No force update, commit deletion, branch deletion
or rewriting of earlier releases is needed.

## Interrupted work resolved by this integration

The final combined source gate had conflicting historical messages (960/961 tests,
OOM, low disk, a later artifact). A new exact-source Android gate passed: 974/974 tests, intact lint zero errors
(121 warnings), and signed APK 4.5.1. The current receipt supersedes earlier
claims only for its fingerprinted Android inputs.

The voice service already relayed speech into the goal activity. The integration
adds a shared reusable agent lifecycle and tests for text/voice proposal ownership,
reviewed execution, cancellation and verification. The separate ecosystem prototype
is an optional adapter boundary, not an implicitly deployed external service.

Documentation now has a current index, architecture and a single current recovery
entry. Earlier handoffs and interruption notes remain in `docs/history`; experiment
mechanisms and evidence limits are indexed in `docs/experiments`.

## Unavailable evidence and physical acceptance

Historical OpenRouter accounting is retained, but its raw ledger/output package was
not found in the source tree or available named artifacts. No extra paid calls were
made. Planning-quality claims remain unverified until that package is recovered.
Physical Android/OEM behavior, assistant-role/microphone lifecycle, Google account
sync and real Yeelight acceptance require their actual environments. The broad
universal discovery/automation product target is retained as future work.
