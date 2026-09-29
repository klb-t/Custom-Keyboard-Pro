# IO Matrix — current work and recovery point

Updated: 2026-09-29 (UTC).
Repository: `klb-t/Custom-Keyboard-Pro`.
Active work branch: `feat/goal-assistant-2026-09-29`.

## Current implementation: first goal-assistant and reactive-light slice

Work branch: `feat/goal-assistant-2026-09-29`, stacked on
`feat/conversation-capture-2026-09-29` at `b3db2c8663bb64e57429df92869f1a8231f6bac6`.
Keep the capture branch and existing APK intact; no merge/release is implied.
Read [GOAL_ASSISTANT_IMPLEMENTATION.md](GOAL_ASSISTANT_IMPLEMENTATION.md).

Runtime source now includes structured goal proposals from the configured model,
strict bounded import, typed prerequisite/DAG assessment, reviewed execution through
canonical `Verbs`/`Performer`, evidence-bound approvals, explicit outcome states,
on-device push-to-talk and the ACTION_ASSIST/RoleManager activity entry. Six audited
verbs are executable by this first host; the rest remain visible capability gaps.
This is not an arbitrary-code runner or a completed autonomous API-discovery agent.

The separate explicit colour-organ panel composes microphone RMS, the existing
Matrix smoothing stage, level-to-colour mapping, a latest-value queue and a Yeelight
LAN music-mode sink. It queries only the selected private IPv4 address and validates
advertised capabilities. This is level-reactive output, not beat detection or support
for every Wi-Fi bulb. No implicit recording, external server or background session.
Both hosts stop/invalidate on pause. Stop does not claim rollback of applied effects.

Validation: **78/78 standalone JVM goal/light tests** and **51/51 existing capture
regressions** passed. A host syntax/type smoke compile used signature stand-ins,
NOT the Android SDK. Eight new Robolectric JSON-codec tests are committed but were
NOT executed here. Full Gradle build, lint, complete suite, actual model, microphone,
assistant-role/device and physical-bulb validation remain open. No new APK, signing,
SDK/dependency change or verified-release claim. Preserve 4.4.2.

Next: run the repository-version Gradle suite/build/lint, then real device and bulb
checks; inspect failures before extending. Further assistant hosts, researched API
catalogues, reusable light profiles, streaming graph discovery and full voice-session
service integration remain explicit follow-up work, not silently completed features.

## Product direction clarified: universal capabilities and voice assistant

Read [UNIVERSAL_ASSISTANT.md](UNIVERSAL_ASSISTANT.md) before extending the roadmap.
The user explicitly wants all special functions, special permissions and advanced
phone capabilities in scope, plus a voice assistant that analyses arbitrary goals,
required APIs, permissions and feasibility, composes a plan, executes it and checks
the result. External devices are included: the concrete example is turning a Wi-Fi
LED bulb into a music-responsive colour organ. Do not narrow this to predefined
voice phrases, a finite command list or only currently implemented adapters.

The requirement is now linked from `AGENTS.md` and `README.md`. At `b3db2c8` that checkpoint was
**documentation only**: no new planner, voice-service host, bulb adapter, permission,
APK or test pass is claimed. Existing `Abilities`, `Wires`, Matrix transforms and
network streams are groundwork, not proof that the full assistant exists. Preserve
the immediate capture acceptance gates below; the new target does not erase them.

## Current task: conversation capture and accessibility-tree inspection — draft PR #3

Work branch: `feat/conversation-capture-2026-09-29`, based on the active branch's
`9b76a39b4fbbfb98e961eeeb225091381b7983eb`. Its changes are inherited by the
current goal-assistant work branch above; do not continue on `main`. The initial capture checkpoint was `5566d824ec336c3a9841db63775bf5608a2715e6`.
The follow-up adds non-visible provider-node capture, bounded read-only full-window
inspection, node semantics/actions, API-guarded expanded state, temporary extended
accessibility-tree flags, show-on-screen and nested-panel scrolling. Genuine
EXPAND actions precede caption heuristics. Toggle clicks require a freshly checked,
recognized collapsed control; no blind coordinate taps or generic custom actions.

Browser helper v2 adds a read-only DOM inspector and an explicit option to read
already-present text in collapsed native/recognized ARIA disclosures. Hidden form
values, independently hidden content and uncreated payloads are not harvested.
Exports distinguish exposed DOM from collapsed-disclosure DOM and preserve raw
frames/revisions. This remains a standalone script, not a mobile browser extension.

Validation performed for this follow-up: **51 standalone Kotlin/JVM regressions**,
**16 adapter control-flow checks using deterministic fake Android classes**, and
**43 offline Chromium fixture checks** passed. The adapter test doubles do NOT
validate Android SDK/API compatibility, Binder, UI, permissions or real apps.
**No full Gradle/Android SDK compile, lint, full project suite, phone test or real
chatbot acceptance ran here. No new APK or CI success is claimed. Keep 4.4.2.**

Read `ACCESSIBILITY_TREE_CAPTURE.md` and `CONVERSATION_CAPTURE.md` for invocation,
precise source boundaries, test commands and remaining gates. Next: build/lint/full
suite with the repository SDK, fix diagnostics, then inspect real native/browser
chatbot trees and validate lazy content, panels, selection and lifecycle on devices.
Do not equate an accepted UI action, a local scroll boundary or an empty subtree
with complete conversation capture. Source publication is not APK validation.

## Last verified release

4.4.2 / version code 10, source commit `f2fc6348975450dd0f3fb0a8877bfa34292aad00`.
[GitHub Actions run 36360084060](https://github.com/klb-t/Custom-Keyboard-Pro/actions/runs/36360084060)
passed compile, tests, Android lint, APK build and publication. Test reports contain
698 tests: 697 passed, zero failures/errors, one existing ClipStore skip. These are
automated checks, not evidence of physical-device or Google account acceptance.

[Installable APK](https://github.com/klb-t/Custom-Keyboard-Pro/releases/download/build-f2fc6348975450dd0f3fb0a8877bfa34292aad00/custom-keyboard-f2fc634.apk)
is 20,975,533 bytes, with GitHub asset SHA-256
`62b1c2383c4086059ec9e24f621f23ac214a41d282aeb8d7b5a0261c6710624a`.
The rolling release preserves previous assets; commit-specific releases are immutable checkpoints.
The package/signing identity is unchanged. Later documentation-only commits do not
change this APK's source. See the feature documents below for scope.

## Earlier workflow instruction (preserved)

The ChatGPT client stopped accepting messages for a long period. The user updated
the app and recovered this conversation. Preserve progress outside the chat,
reduce blocking operations, and continue the original IO Matrix work. The cause
of the client freeze is unknown; do not claim that changing this repository fixes it.

## Completed implementation

* The original 4.1 takeover covers canonical expert controls/profiles, contextual
  customization, tile/lock/shortcut sessions, model-first setup, a SAF media hub and
  an authenticated vault/Autofill. See `HANDOFF_2026-09-27.md` for its exact scope.
* Vault backup/restore and safer release publication passed the 4.2 gate. Backups
  use bounded standard JWE and authenticated, reviewed import; target trust must be
  rebound on the receiving device. See `VAULT_BACKUP.md`.
* Clipboard bulk deletion requires confirmation of a snapshot, preserves pins and
  later copies, and moves entries to persistent Trash with restore. Images and
  screenshots have owned bytes, bounded previews and native rich-content paste.
  Failed DB cleanup preserves referenced image files. See `CLIPBOARD_WORKSPACE.md`.
* Cursor geometry uses screen transforms and actual window insets. Cursor/layout/
  navigation changes trigger coalesced checks; movable elements avoid the focused
  field, safe-area bounds and other panels, with a fade fallback when space runs out.
* Explicitly reviewed encrypted sync shares clipboard history, supported images and
  portable settings through a file or Google Drive app-data, without an IO Matrix
  server. It handles concurrent revisions, stale previews and deletion markers.
  Room 4→5 keeps manual deletion intent after Trash expiry. Automatic age/capacity
  cleanup stays local by default; expert policy can explicitly propagate it.
  See `DEVICE_SYNC.md` for limits, setup and acceptance gates.

All the source above passed the 4.4.2 gate. Sync is manual, not continuous background
sync. History means clipboard history, not other applications' conversation history.

## Remaining acceptance and extension work

1. Install the published APK on a phone and validate ChatGPT Android field avoidance,
   rotation/split screen, gesture/three-button navigation, image paste, shade return
   and shortcut-session lifecycle. No phone/emulator was connected for this work.
2. Provision the Android OAuth app with the exact package/signing SHA-1 shown in
   Device sync, enable Drive API/consent and test account grants on two phones.
   This Google project is not provisioned or verified here; no client ID was invented.
   Encrypted file exchange is available without that setup. Test interruption and
   permission revocation before treating the Drive path as device-validated.
3. Validate real device authentication, browser Autofill and granted cloud
   DocumentsProviders. Preserve the documented boundaries around secrets and
   destination trust.
4. Broad extensions remain explicit in the original handoff: real notification and
   playback-capture adapters, provider-specific cloud adapters where SAF is
   insufficient, passkeys/payment integration and sync group/key management.
   Do not enable permissions or present these planned integrations as implemented.

## Recovery procedure

Clone the branch above; read `AGENTS.md`, this file and the relevant feature docs.
Compare the remote head before publishing. Do not repeat completed features or
overwrite parallel changes. Check the latest Actions run and release target; a
successful upload of source is not proof that its APK passed tests. If a previous
attempt failed, read its concrete diagnostics before changing code.

Still needs device validation: notification shade/IME lifecycle, hardware keys,
device authentication, browser Autofill and actual cloud DocumentsProviders.
The broad future scope in the handoff remains explicit; do not present it as done.
