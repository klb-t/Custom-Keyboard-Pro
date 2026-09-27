# IO Matrix — current work and recovery point

Updated: 2026-09-28 (Europe/Amsterdam).
Repository: `klb-t/Custom-Keyboard-Pro`.
Branch: `claude/keyboard-app-all-features-78fkrd`.

## Last verified release

4.4 / version code 8, commit `67c2e9b4b5d2ba0782a59598480066a89b869fcf`.
GitHub Actions run `36358399823` passed compile, tests, lint, build and publication.
The rolling release preserves previous assets; commit-specific releases are immutable checkpoints.
See `HANDOFF_2026-09-27.md` and `VAULT_BACKUP.md` for scope.

## Current user instruction

The ChatGPT client stopped accepting messages for a long period. The user updated
the app and recovered this conversation. Preserve progress outside the chat,
reduce blocking operations, and continue the original IO Matrix work. The cause
of the client freeze is unknown; do not claim that changing this repository fixes it.

## Current next steps

1. Add an explicit encrypted vault backup/restore workflow so device loss does not
   make the new vault unusable as a long-term store. Keep standard cryptography,
   authenticated access, private input, bounded parsing and review-before-import.
2. User's 00:31 follow-up: prevent one-tap clipboard-history deletion, include
   screenshots as clipboard content, and fix keyboard/editor/navigation-bar
   occlusion (reported in the ChatGPT Android app). Keep monitoring viewport changes.
3. Design and implement feasible multi-device sharing of history, clipboard and
   settings without an application server, initially through the same Google
   account. Handle concurrent edits and account setup explicitly; do not invent an
   OAuth client ID or claim Google integration before the actual grant works.
4. Implement notification access as an optional source after these concrete user
   regressions. Permission alone is not an integration.
5. Run the full existing suite, new focused regressions and Android lint, publish
   the next APK, and record exact results here. Keep the signing identity unchanged.

Vault backup/restore and safer release publication passed the 4.2 gate.
Clipboard recovery, image ingress and workspace geometry passed the 4.3 CI gate;
physical device checks remain. See `CLIPBOARD_WORKSPACE.md`.
4.4 adds manually reviewed, encrypted device sync via file and Google Drive app-data.
4.4 passed the full gate: 685 tests, zero failures/errors, one existing skip, Android
lint, APK build and publication. The content-capture API guard is corrected to API 30.
4.4.1 adds the missing rich-content paste path for screenshot/image receivers and
an explicit native-paste fallback setting; its final gate is pending.
Google account consent cannot be verified here; the app
shows actual package/signing identity and setup steps, and does not invent a client ID.
Read `DEVICE_SYNC.md` for data/merge/privacy limits and precise acceptance gates. New code uses Nimbus JOSE 10.10 with PBES2-HS512+A256KW/A256GCM,
strict input/work-factor bounds and explicit merge choices. UI locks on leaving.

## Recovery procedure

Clone the branch above; read `AGENTS.md`, this file and the relevant feature docs.
Compare the remote head before publishing. Do not repeat completed features or
overwrite parallel changes. Check the latest Actions run and release target; a
successful upload of source is not proof that its APK passed tests. If a previous
attempt failed, read its concrete diagnostics before changing code.

Still needs device validation: notification shade/IME lifecycle, hardware keys,
device authentication, browser Autofill and actual cloud DocumentsProviders.
The broad future scope in the handoff remains explicit; do not present it as done.
