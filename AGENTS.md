# IO Matrix development rules

IO Matrix is a configurable information layer; Android IME is its first host.
Preserve the existing `Wires`, layout, settings, discovery and Matrix abstractions.

## Product scope

Read the canonical root [ECOSYSTEM.md](ECOSYSTEM.md) when making design decisions.
iOmatrix is a standalone, learning participant that can both use and contribute
knowledge, procedures, structures and interaction capabilities across the ecosystem.
Keep observations, user declarations, model hypotheses and action outcomes distinct.
Sharing a representation or profile never transfers access or execution authority.
The document describes possible directions, not completed integrations or an order
to implement every link. Do not impose one universal database, graph semantics,
provider, runtime or mandatory dependency on the other projects.

Read `docs/UNIVERSAL_ASSISTANT.md` for the explicit long-term target: all special
functions, special permissions and advanced phone capabilities, external devices,
and a voice/text assistant that discovers APIs and prerequisites, plans execution,
acts and verifies the result. Do not reduce this to a fixed list of keyboard
commands or voice phrases. The Wi-Fi LED colour-organ example is a required
composition scenario. Keep this product target separate from implemented/tested
capabilities; a missing adapter is not by itself proof of impossibility.

## Development rules

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

The active implementation is on `feat/phone-workspace-2026-09-30`, stacked on
`feat/goal-assistant-2026-09-29`. Preserve the earlier working APK and branches.
Inspect the remote before pushing to avoid overwriting concurrent work.

## Resuming interrupted work

Read `docs/RESUME.md` before extending an interrupted task. GitHub is the durable
checkpoint; a chat transcript or a scratch checkout is not the only source of truth.
Update that file at each completed feature with its validation state and the next
concrete step. Publish coherent, reviewable checkpoints instead of accumulating
hours of changes only in the temporary workspace. Do not label untested code as
verified. Preserve the last working APK while a new build is being checked.

Keep command output bounded and save long build logs to files. Start long commands
as resumable sessions; do not block the conversation waiting for them. Report real
progress regularly. These measures preserve work and responsiveness; they do not
claim to prevent failures in the ChatGPT client or service.
