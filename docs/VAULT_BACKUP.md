# Portable vault backup and restore

In the unlocked vault, choose **Create encrypted backup**, select a destination,
authenticate again after returning from the picker, and enter a separate backup
passphrase twice. Use a long, unique phrase and keep it separately from the file.
The app does not store this passphrase or send it to a model, clipboard or server.

Choose **Restore encrypted backup** to test the saved file. After authentication,
enter its passphrase, review the entries and select a merge policy. Cancel at the
preview to verify recovery without changing the current vault. Keep your previous
backup until this test succeeds; a cloud DocumentsProvider may finish uploading
after its output stream closes. Copy the file off the original phone as well.

On another phone, create its screen lock, install IO Matrix and restore the same
file with its backup passphrase. The current local vault still uses that phone's
authenticated Android Keystore. Existing unreadable device ciphertext is never
silently erased; recovery is supported into an accessible/new vault. There is no
forgotten-passphrase reset and no automatic cloud synchronization in this feature.

The explicit merge policies are:

| Policy | Existing matching ID | Other IDs |
| --- | --- | --- |
| Keep existing | Preserve current entry | Add imported entry |
| Update matching IDs | Replace after confirmation | Add imported entry |
| Keep both | Add a new ID | Add imported entry |

Matching uses backup IDs, not labels or usernames. Imported app/browser trust
bindings are cleared. Bind the restored entries explicitly before using Autofill.
The whole import is checked before the encrypted local vault is atomically saved.

## Format and limits

* Compact JWE through Nimbus JOSE+JWT 10.10, with exact type
  `io-matrix-vault-backup+jwe`, PBES2-HS512+A256KW and A256GCM.
* Fresh 32-byte salt, 220,000 PBKDF2-HMAC-SHA512 iterations and a fresh content key
  for every export. Header/work-factor/algorithm limits are checked before decryption.
* No compression, external key URLs, algorithm negotiation or plaintext export.
* At most 3 MiB archive, 2 MiB minus envelope allowance in decoded content and
  1000 entries. Passphrases are 12–1024 characters; length alone does not ensure strength.
* Copies of mutable byte/character buffers are cleared after use. JVM/Compose
  strings and library internals cannot promise complete memory zeroization.
* File I/O and password derivation run outside the UI thread. Leaving, cancelling
  or expiry clears the preview and prevents a late result from applying to a new
  session. A provider write may leave an incomplete encrypted export on failure.

References checked 2026-09-28:

* [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt)
* [JSON Web Encryption, RFC 7516](https://www.rfc-editor.org/rfc/rfc7516)
* [OWASP PBKDF2 work factors](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2)

Regression tests cover logins/cards/Unicode, randomness, wrong passwords,
tampering, hostile headers, size/ID limits, merge conflicts and trust removal.
Real device authentication, picker cancellation/rotation, slow or offline cloud
providers and cross-phone recovery still require device acceptance testing.
