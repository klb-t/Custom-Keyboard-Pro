# IO Matrix development rules

IO Matrix is a configurable information layer; Android IME is its first host.
Preserve the existing `Wires`, layout, settings, discovery and Matrix abstractions.

* Do not encode a choice in control flow when it can be an instance of a shared
  model. Preserve existing examples as profiles and add new examples to that same
  registry. Separate mechanism, policy, presentation, transport and provider.
* The expert UI is a projection of canonical settings. A setting needs persistence,
  validation, a discoverable control, a real consumer and a useful explanation.
  Keep `Settings`, its codec and schema synchronized; coverage tests enforce this.
* Core typing works without a model, network, accessibility, overlay, account or
  vault. Optional capabilities declare their needs, current availability and fallback.
* Begin provider setup with the desired capability/model. Providers, account steps,
  endpoints, model defaults, parameters and evidence are catalogue/profile data.
  Do not confuse model author, inference provider and transport format. Unknown
  price/privacy/availability remains unknown. Never relax a privacy rule silently.
* A graph edge names information lost or generated, uncertainty, reversibility,
  provenance, cost and privacy. A representation is not the underlying data type.
* Contextual controls and expert controls must update the same settings. Menus
  opened from tiles or the keyboard must preserve the user's target and focus.
  Android-restricted actions must explain their limits; a visible control is not
  evidence that the OS can perform it.
* Private inputs must never reach suggestions, learning, macro recording, model
  context or clipboard history. Credentials stay out of logs, portable profiles and
  backups. Do not invent cryptography or weaken certificate/origin validation.
* Preserve responsive input: disk I/O, cloud listing and cryptography do not belong
  in a per-frame UI update. Show pending/error state honestly.
* Verify behavior with focused regression tests and the existing full unit suite.
  Hardware, OEM shade behavior, real Keystore/authentication, Autofill/browser and
  cloud-provider compatibility require device validation; never claim unit tests
  establish those results.
* Do not present planned adapters or permissions as working integrations. Record
  remaining work and scope explicitly in the handoff.

Build using the versions declared in the repository:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The active implementation is on `claude/keyboard-app-all-features-78fkrd`.
Inspect the remote before pushing to avoid overwriting concurrent work.
