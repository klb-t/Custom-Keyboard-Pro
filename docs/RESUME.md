# IO Matrix — current work and recovery point

Updated: 2026-09-29 (UTC).
Repository: `klb-t/Custom-Keyboard-Pro`.
Branch: `claude/keyboard-app-all-features-78fkrd`.

## Current task: conversation capture — feature branch, not released

Work branch: `feat/conversation-capture-2026-09-29`, based on the active branch's
`9b76a39b4fbbfb98e961eeeb225091381b7983eb`. The user requested native selection or
scrolling screen capture, automatic expansion of visible reasoning/tool details,
and a copyable/exportable conversation structure, including a browser alternative.

Implemented source: a bounded platform-free capture state machine/archive,
conservative disclosure policy, Android accessibility driver, consent/stop/review,
text/JSON exports, canonical action profile and Quick Settings tile; plus a local
DOM helper that preserves exposed structure and message IDs. Existing single-screen,
TTS/page-reader, clipboard/sync and release mechanisms are left intact.

Validation: 24 standalone Kotlin/JVM regression methods and 24 offline Chromium
fixture checks passed. The Android adapter/UI and full Gradle suite have NOT been
built/run here; no device/live-chatbot validation or new APK is claimed. Keep 4.4.2.
See `CONVERSATION_CAPTURE.md` for exact limits, invocation and remaining gates.
Next: build/lint/full unit suite with the repository SDK, fix actual diagnostics,
then device-test native apps and browsers before merging/publishing. Do not confuse
raw exposed UI snapshots with guaranteed-complete internal conversation records.

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
