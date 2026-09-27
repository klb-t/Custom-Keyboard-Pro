# Device sync — no application server (4.4)

Open **Phone tools → Device sync**. Choose clipboard history, images/screenshots
and/or portable settings. Sync is explicit and reviewed, not background/real-time.
Use the same strong passphrase on every phone. It is never saved in Android instance
state; leaving the activity clears the UI, pending preview and Google access token.
A native private text field suppresses learning, copy/cut, Autofill and content capture.

## Transports

* **Encrypted file** works without a registered Google app: choose Export file in
  the system picker (Drive or another installed DocumentsProvider can be used),
  enter the passphrase, Preview, Write. Import that file on the other phone, Preview,
  Apply. Export back to exchange later changes. It is a manual exchange, not a
  watched file or automatic folder sync.
* **Google Drive app-data** requests only `drive.appdata`, through Google Identity's
  Android AuthorizationClient. Account selection is explicit; the verified Drive
  account email appears before Preview/Apply. Files are hidden app data; app access
  does not expose general Drive contents. No IO Matrix server, service account,
  client secret, refresh token or Google password is required in the APK.
* Google runtime setup is **not provisioned or verified by this repository**. Enable
  Drive API in a Google Cloud project, configure OAuth consent/audience, register an
  Android client with the exact APK package and SHA-1 shown in the app. While testing,
  add the intended Google account as a test user. Use one OAuth app/project across
  devices. The package/signing identity is preserved by existing CI. Google Play
  services and the actual consent/account restrictions still require phone testing.
  The connection screen provides the setup link and a file-transfer fallback.

## Mechanism and policies

Snapshots contain individually identified records: `clip:<stable-id>` and
`setting:<canonical-key>`. Each revision is a Lamport counter plus device ID;
wall-clock differences do not select winners. Concurrent equal counters use the
lexical device ID. Same revision with different content is rejected. Merge is
commutative, associative and idempotent for well-formed replicas. A joining phone's
settings defaults have revision zero so existing shared choices win the preview.

Manual clipboard deletions and later local purges create durable tombstones. An old
phone cannot resurrect an entry by uploading its old snapshot. Explicit Restore is
a new revision. Incoming deletion moves existing local entries to recoverable Trash.
Each device writes only its own Drive snapshot, avoiding read/overwrite loss across
devices. Rerun sync to receive another phone's simultaneous update. Tombstones are
not silently expired; at the record safety limit sync stops and preserves files.

The clipboard limit selects recent NEW entries on each capture; already shared data
and tombstones persist in merged state. Images copy bytes, never local paths or URI
grants. Per-image default 2 MiB (expert 1–8), 7 MiB aggregate new-image selection,
12 MiB clear snapshot / 16 MiB JWE envelope, 10,000 records, 32 remote snapshots and
64 MiB total remote download per run. Oversized/unsupported new local images remain
local. Growing merged history can hit the envelope limit; it fails before applying,
instead of pruning deletion knowledge and risking resurrection. A UI for inspecting
and resetting an entire sync group is future work; never delete its files silently.

## Privacy and failures

Settings projection uses the same canonical portable-profile allowlist: API keys,
provider/account data, opaque commands, local URIs and sync preferences are excluded.
Vault/password/card records are not part of sync (use the separate authenticated
vault backup workflow). Explicitly copied clipboard text may itself contain sensitive
information, so the entire payload is encrypted before leaving the device.

Vault backups and sync now share one bounded standard-JWE implementation, with
separate purpose headers: PBES2-HS512+A256KW/A256GCM, random 32-byte salt, 220,000
PBKDF2-HMAC-SHA512 iterations. Unsupported algorithms/compression/headers, hostile
work factors and oversized input are rejected before mutation. Local sync state is
also JWE-encrypted in noBackup storage. Passphrase loss is not recoverable by Google.

Preview reads/merges only. Apply re-captures current data and rejects stale settings,
clipboard edits made after review, or changed scope. Affected rows are compared
again under the database transaction after image/crypto staging, so intervening
pins/edits/deletes cannot be overwritten. Previously shared entries are still
tracked when they fall outside the recent-new-entry selection limit. Image bytes and both encrypted snapshots are prepared before DB
writes; Room clipboard writes are transactional. Settings and the sync state are
separate durable stores, so an interrupted apply may need an idempotent retry. An
upload failure can occur after local merge has succeeded: the UI says that local
changes remain and the previous remote files are preserved. Cancelled network calls
are closed; all disk/network/crypto work runs outside the typing thread. Existing
Google snapshots are never deleted by this version.

Not included: continuous background sync, vault/credentials, learned dictionary,
usage/model/browser history, conflict-history UI, cryptographic device revocation
or key rotation. "History" here means IO Matrix's clipboard history, not access to
other applications' conversation histories.

## Validation

Pure/Room regressions cover merge convergence, tombstones and restore, new-device
settings bootstrap, stale transfers, repeated import, preview immutability,
wrong-passphrase preservation, hostile paths/duplicate identities, credential
exclusion and purpose-bound JWE. Existing vault backup regression tests must still
pass after crypto/UI extraction. Google API access, two-phone grant/account matching,
actual Drive permission revocation, device interruption and OEM IME behavior require
manual acceptance; CI must not be reported as proof of them.

Primary API references (checked 2026-09-28):
- https://developer.android.com/identity/authorization
- https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder
- https://developers.google.com/workspace/drive/api/guides/appdata
