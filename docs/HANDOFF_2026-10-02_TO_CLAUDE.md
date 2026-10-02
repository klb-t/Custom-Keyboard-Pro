# IO Matrix — handoff to Claude, 2026-10-02

## Read first

The current state is `RESUME.md`; developer invariants are `../AGENTS.md`.
`README.md` indexes feature contracts, `ARCHITECTURE.md` maps code boundaries,
`INTEGRATION_2026-10-02.md` records recovery/merge decisions and
`experiments/README.md` records reproducibility and unavailable evidence.
Root `../ECOSYSTEM.md` remains the canonical high-level ecosystem note.

## Integrated source

The former main and recovered `dd739d1` development history are joined by a normal
merge. Capture, goal assistant, phone workspace, AI profiles, viewport/settings,
clipboard recovery, sync, vault and Matrix changes remain as original incremental
commits. Preserve branches and older releases. Do not cherry-pick duplicates or
squash/rewrite this history. The new Android version is 4.5.1 / version code 12,
package `com.aistudio.qwertykey.xyzabc`, with the existing debug signer for updates.

### Voice and local agent

`app/src/main/java/com/example/core/assistant/GoalAgent.kt` is the shared local
controller. `assistant/GoalAssistantActivity.kt` adapts Android speech, text,
model planning, imports, lifecycle and UI to it. Speech and text carry explicit
input provenance. A transcript remains editable; it creates no planner request,
approval or device effect by itself.

Planner turns belong to one controller, goal and revision. Editing, new speech,
pause and Stop revoke them. The response parser is still `GoalJson` with bounded
strict JSON, decoded duplicate-name rejection and canonical schema. Alternatives
are detached, checked and bound to the locally entered goal before a plan is selected.

`GoalSession` remains responsible for frozen catalogue/review ownership, current
prerequisite evidence, dependencies and single-use execution tickets. `GoalAgent`
delegates review, begin, completion and observed effects to it. The Android host
uses `GoalCatalogue`, `GoalPerformer` and the existing `Verbs`/`Performer`; there is
no second action executor. Verified readback and user-confirmed observations stay
distinct. Cancel does not fabricate rollback or confirmed failure after dispatch.

`GoalAgentTest` adds ten lifecycle cases; `GoalSessionTest` retains thirteen
approval/cancellation cases. The standalone harness now includes both classes.
The full Gradle suite passed, including the three shared-format bridge cases.

### Existing ecosystem-agent interoperability

`tools/agent_bridge.py` registers `iomatrix.propose_plan` as an actual Tool in the
separate Loom `AgentRuntime` prototype. It validates and exchanges existing v1
plan definitions. The Android import already feeds `GoalJson` and the same local
controller/review path. Import transfers no permissions, approval, phone-execution
receipt or device identity. See `ECOSYSTEM_AGENT_BRIDGE.md` for concrete CLI usage.

The reference source is ChatADHD commit
`157762ef60be8b14fe75c6a9c922d6df7ffe97a1`, branch
`gpt/ecosystem-agent-research-2026-10-01`. Select the runtime explicitly; do not
copy it into IO Matrix or treat it as a deployed cloud service. Twelve Python tests
passed with the actual selected runtime, including receipt replay, input/session
binding and unavailable-capability denial. Three `EcosystemAgentBridgeTest` cases
use the same fixture with the actual production Android parser.

Current interoperability is an in-process Python proposal Tool and explicit file
exchange. Authenticated remote phone transport, observations returning to the agent,
remote cancellation and VM deployment are open extensions. A runtime's completed
proposal receipt is not a completed phone task.

## Validation

Full combined Android gate **PASS**: production/test compilation passed; **974/974
unit tests**, zero failures/errors/skips; intact lint **0 errors / 121 warnings**;
signed APK **4.5.1 / code 12**, 21,470,495 bytes. Its SHA-256 is
`96cbdceb9ff7f664ac2bab87c0bcf23c1f73d2a03c32b2572aa02001cb85ed4a`.
The debug certificate matches previous updates. Build logs are retained alongside
the receipt at `validation/2026-10-02/`. No physical-device test is implied.

The authoritative machine receipt is `validation/2026-10-02-integration-gate.json`.
The gate fingerprints all Android build/test inputs before and after execution;
documentation-only follow-ups do not recertify a changed implementation.

Independent focused checks: 101/101 goal/session/agent/light JVM tests; 51/51
capture JVM tests; 16/16 platform-adapter test-double assertions; 43/43 original
offline browser fixture assertions on local Chromium; 7/7 evaluator guards.
The browser acceptance used a temporary Node Playwright bridge because Python
Playwright was unavailable; test bodies and semantics were unchanged. These checks
are mechanisms/fixtures, not a physical-phone or real-app result.

Use the exact checked-in build versions and `tools/local-android-build.sh`.
Run compile/full unit, lint and assembly sequentially on constrained hosts. Do not
disable lint, skip test classes, downgrade dependencies or invent a CI success.
Standalone scripts require Kotlin/JVM; browser fixtures require Playwright/Chromium.

## Remaining concrete work and evidence limits

1. Install the newly verified APK on an unlocked physical Android phone. Validate
   assistant role/gesture, installed on-device recognizer, microphone attribution,
   screen lock/pause/Stop, permission revocation, rotation/insets and keyboard input.
2. Verify actual application accessibility/browser capture, rich-image paste,
   Keystore/Autofill, two-device Google Drive sync and physical Yeelight behavior.
   No device, Google project provisioning or bulb was claimed in this recovery.
3. Restore the missing OpenRouter evaluation ledger/raw first responses before any
   more billed calls. Historical notes record 45 baseline + 12 comparisons, total
   USD1.098135722, inside the existing cumulative USD2 cap. Empty/truncated responses
   are failures/unknowns; a later replay cannot replace the missing original outputs.
   Current recovery made zero paid provider requests and used no paid CI.
4. Extend the optional ecosystem transport only against an explicit, tested runtime
   contract. Keep local device authority, freshness, review and outcome evidence.
5. General API discovery, privileged phone bridges, additional devices and streaming
   compositions remain the long-term target in `UNIVERSAL_ASSISTANT.md`; absence of
   an adapter is a visible integration gap, not proof that a goal is impossible.

Available branches, recovery notes and prior-conversation search were inspected.
Unavailable/unwritten fragments and vanished child-agent sessions cannot be certified
as a full transcript archive. Earlier evidence stays in `history/` and Git commits;
use the current recovery entry instead of an old branch-specific instruction.
