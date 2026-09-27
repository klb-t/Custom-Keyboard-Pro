# Local vault and Android Autofill

## Implemented scope

The vault stores login profiles and payment-card profiles locally. Login CSV imports accept
Chrome/Firefox (`username`, `password`, `url`) and Bitwarden (`login_username`,
`login_password`, `login_uri`) columns. CSV is read through the system document picker,
bounded to 1 MiB / 1000 entries, previewed, and written only after explicit confirmation.
No persistent document permission or plaintext temporary file is created. The original CSV
belongs to the user and is not deleted automatically. Imports carry no trusted Autofill
binding: the user selects an installed app/browser before any imported secret can be filled.

An entry's optional Autofill binding consists of the installed package name, the complete
set of current SHA-256 signing certificates, and an exact HTTPS origin for web forms.
The editor offers an installed-app picker, explicit fingerprint confirmation and an expert
package-name field. Signing-key changes deliberately require a new user-approved binding.
There is no inferred native-app ↔ website relationship and no wildcard/subdomain matching.

Android 8+ login Autofill and Android 8+ native card Autofill use the platform service.
HTTPS browser filling requires Android 9+ and a trustworthy browser that supplies web
scheme/domain and field hints correctly. The selected browser is explicitly trusted by
the user and pinned to its signing certificate. This is not a browser attestation service
or Digital Asset Links implementation; a malicious browser explicitly trusted by the
user can misreport its page origin. WebView clients are trusted under the same rule.

For every fill, a new system credential challenge is required. The user then selects an
entry matching the requested kind, package, signers and exact HTTPS origin. Requests are
single-use, expire after two minutes, and are cancelled with the platform request.
Field IDs and origin metadata remain in process memory; mutable Intent extras are not
trusted. The signing identity is checked again before a populated response is released.
The service never receives a decrypted vault or advertises `SaveInfo` for background capture.

The parser recognizes explicit username/current-password and card number/name/separate
expiry-month/expiry-year hints. It rejects multiple origins, mixed native/web candidates,
duplicate roles, new-password fields, unverified HTTP, excessive tree size/depth, and
mixed login/card candidates. Unlabelled forms, multiple forms and some checkout layouts
therefore intentionally receive no suggestion. No broad accessibility fallback exists.

Card details fill only into a bound merchant/app after explicit selection. CVV/PIN are not
fields in the storage model and are never stored or filled. Card expiry dropdowns and
combined expiry-date formats are unsupported. Filling does not submit a form, authorize a
payment, provision NFC credentials, or implement banking/payment rails. The settings page
links to Android's contactless-payment settings for existing wallet providers.

## Storage and UI boundary

* Android Keystore AES-256-GCM; a fresh system-generated IV per write and version header as
  authenticated additional data. The key requires device-credential authentication with a
  60-second hardware-enforced validity window. There is no fallback to an unauthenticated key.
* Ciphertext lives in `Context.noBackupFilesDir` as an `AtomicFile`; Android backup and device
  transfer do not include this directory. There is no vault data in general settings exports.
* Missing/invalidated keys, tampered ciphertext and unknown format versions fail closed;
  existing bytes are preserved. Generation checks prevent concurrent unlocked windows from
  silently overwriting each other's edits. No reset/recovery action silently replaces a key.
* The dedicated, non-exported activity locks on `onStop` and after the configured foreground
  timeout (15–60 seconds, default 60). Security bounds and origin/certificate checks are fixed.
  No plaintext singleton, saved-state restoration, log output or network export is used.
* `FLAG_SECURE`, overlay hiding where supported, obscured-touch rejection, and Autofill/content
  capture exclusion protect the vault UI. Secret inputs use `BasicSecureTextField`, so reveal
  does not enable Copy/Cut/drag. All entry inputs are marked private to the IO Matrix IME and
  use the password input type, including labels and usernames.
* JVM strings and Compose state cannot provide guaranteed memory zeroization. Mutable byte
  buffers are cleared where possible; closing a session releases references. This does not
  defend against a rooted device, a compromised app process, or a malicious trusted IME or
  accessibility service. Hardware-backed key availability depends on the device.

Changing/removing the device screen lock, key invalidation, uninstalling or clearing app
data may make the local vault unrecoverable. [Encrypted backup/restore](VAULT_BACKUP.md) now supports recovery into an accessible/new vault with a separate passphrase. There is no automatic cloud sync,
forgotten-passphrase recovery, passkey provider, browser database extraction, direct third-party
vault access or standalone-manager API synchronization in this implementation. Android's
conventional Autofill service is user-selected; enabling IO Matrix may replace the currently
selected manager. CSV import and system settings are the supported interoperability paths.

## Verification

`VaultPolicyTest` covers exact-origin rejection, package/signer mismatch, quoted/multiline CSV,
supported export headers, unbound imports, malformed CSV atomic rejection, bounded inputs,
card validation and displayed-origin consistency. `VaultCodecTest` covers credential/pin
round-trip and rejection of duplicate IDs and unknown schema versions. `VaultFormParserTest`
uses synthetic Android structures to check login/card field selection, CVV exclusion, HTTPS
inheritance and rejection of mixed origins, HTTP, ambiguous roles and mixed entry kinds. These are policy and
serialization checks, **not** evidence of device Keystore or browser Autofill compatibility.

Before relying on this vault for real credentials, run device/instrumentation validation:

1. Create a secure device lock; cancellation, wrong PIN, backgrounding, timeout and process
   recreation must keep/relock the vault. Verify the configured shorter timeout as well as
   the hardware 60-second maximum.
2. Inspect the app sandbox after save/import: only ciphertext in `no_backup`, no vault in
   settings/backup/cache/logs. Tamper ciphertext and verify that unlock fails without rewriting.
3. Try native login/card forms and Chrome/Firefox forms that expose platform hints. Confirm
   that user authentication and explicit entry selection occur, and filling never submits.
4. Reject a different package, changed signer, subdomain, HTTP page, mixed-origin iframe,
   new-password form, cancelled/expired request, wrong kind, and unbound imported profile.
5. Verify Copy/Cut/drag remain unavailable for revealed secrets, screenshots/overlays are
   blocked, and the selected IME does not learn/send vault fields to AI or record macros.
6. Exercise locked-device restart, background CSV picker/reauthentication, malformed imports,
   concurrent sessions, key invalidation, and keyboard-only/talkback navigation.

Physical-device validation has not been performed in this environment. Treat browser support
as capability-dependent until tested, and retain an independent copy in an established manager.

## Platform references checked

* [Build autofill services](https://developer.android.com/identity/autofill/autofill-services)
* [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
* [KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
* [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager)
* [BasicSecureTextField](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/package-summary#BasicSecureTextField(androidx.compose.foundation.text.input.TextFieldState,androidx.compose.ui.Modifier,kotlin.Boolean,androidx.compose.foundation.text.input.InputTransformation,androidx.compose.ui.text.TextStyle,androidx.compose.foundation.text.KeyboardOptions,androidx.compose.foundation.text.input.KeyboardActionHandler,kotlin.Function2,androidx.compose.foundation.interaction.MutableInteractionSource,androidx.compose.ui.graphics.Brush,androidx.compose.foundation.text.input.TextFieldDecorator,androidx.compose.foundation.text.input.TextObfuscationMode,kotlin.Char))
