# IO Matrix — current work and recovery point

Updated: 2026-09-28 (Europe/Amsterdam).
Repository: `klb-t/Custom-Keyboard-Pro`.
Branch: `claude/keyboard-app-all-features-78fkrd`.

## Last verified release

4.1 / version code 5, commit `5204084393cb05262af0f26a59e5c051d3a32972`.
GitHub Actions run `36347371623` passed compile, tests, build and publication.
Local validation: 658 tests, 657 passed, one existing ClipStore skip; lint zero
errors, 67 reviewed warnings. See `HANDOFF_2026-09-27.md` for implemented scope.

## Current user instruction

The ChatGPT client stopped accepting messages for a long period. The user updated
the app and recovered this conversation. Preserve progress outside the chat,
reduce blocking operations, and continue the original IO Matrix work. The cause
of the client freeze is unknown; do not claim that changing this repository fixes it.

## Current next steps

1. Add an explicit encrypted vault backup/restore workflow so device loss does not
   make the new vault unusable as a long-term store. Keep standard cryptography,
   authenticated access, private input, bounded parsing and review-before-import.
2. Implement notification access as an actual optional source with shared settings,
   privacy defaults and profiles. Permission alone is not an integration.
3. Run the full existing suite, new focused regressions and Android lint, publish
   the next APK, and record exact results here. Keep the signing identity unchanged.

No executable changes for these next steps have been verified yet.

## Recovery procedure

Clone the branch above; read `AGENTS.md`, this file and the relevant feature docs.
Compare the remote head before publishing. Do not repeat completed features or
overwrite parallel changes. Check the latest Actions run and release target; a
successful upload of source is not proof that its APK passed tests. If a previous
attempt failed, read its concrete diagnostics before changing code.

Still needs device validation: notification shade/IME lifecycle, hardware keys,
device authentication, browser Autofill and actual cloud DocumentsProviders.
The broad future scope in the handoff remains explicit; do not present it as done.
