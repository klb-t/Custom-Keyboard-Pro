# Documentation

Start with [the project overview](../README.md), then use the current
[recovery and validation receipt](RESUME.md) for source revisions, confirmed checks
and concrete next steps. Feature documents explain contracts and boundaries;
their dated test counts do not certify later source.

## Design and implementation

| Guide | Purpose |
|---|---|
| [Architecture](ARCHITECTURE.md) | Host/runtime paths, package boundaries and canonical decisions |
| [IO Matrix model](IO-MATRIX.md) | Data type, representation, transform, transport, provenance and streaming |
| [Layout format](LAYOUT_FORMAT.md) | Authored JSON layouts, key actions, geometry and validation |
| [Use cases](USE-CASES.md) | Concrete cases absorbed by common mechanisms and remaining gaps |
| [Ecosystem direction](../ECOSYSTEM.md) / [adapter boundaries](ECOSYSTEM_ADAPTER_BOUNDARIES.md) | Possible cross-project roles and local authority boundaries |

## Feature contracts

| Area | Guides |
|---|---|
| Keyboard, settings and profiles | [Authoring and toolbar](KEYBOARD_AUTHORING_TOOLBAR.md), [owners and inheritance](SETTINGS_SCOPE_AND_LEVELS.md), [Expert controls](EXPERT_SETTINGS_AND_PROFILES.md), [prediction](PREDICTION.md), [tiles and lock](TILES-AND-LOCK.md) |
| Assistant and phone | [Universal target](UNIVERSAL_ASSISTANT.md), [initial goal/light implementation](GOAL_ASSISTANT_IMPLEMENTATION.md), [system voice host](SYSTEM_VOICE_ASSISTANT.md), [phone tools](PHONE_TOOLS.md), [Android capability research](ANDROID_PHONE_CAPABILITIES.md) |
| Providers and model calls | [Provider discovery](PROVIDERS.md), [capability-first onboarding](PROVIDER_ONBOARDING.md), [AI request profiles](AI_TASK_PROFILES.md), [goal-model evaluator](GOAL_AI_EVALUATION.md) |
| Capture and workspace | [Conversation capture](CONVERSATION_CAPTURE.md), [accessibility-tree inspection](ACCESSIBILITY_TREE_CAPTURE.md), [clipboard recovery](CLIPBOARD_WORKSPACE.md), [media hub](MEDIA_HUB.md) |
| Credentials and portability | [Vault and Autofill](VAULT_AND_AUTOFILL.md), [vault backup](VAULT_BACKUP.md), [manual device sync](DEVICE_SYNC.md) |

## Build, evidence and history

- [Local Android build](LOCAL_ANDROID_BUILD.md): pinned toolchain, bootstrap and actual gate commands.
- [Current work/recovery](RESUME.md): active source and the current validation boundary.
- [Validation receipts](validation/): machine-readable evidence for exact checkpoints.
- [Development history](history/README.md): preserved dated handoffs and interrupted-session observations.
- [Development rules](../AGENTS.md): invariants and recovery procedure for contributors and agents.
- [Project license](../LICENSE), [commercial licensing](../COMMERCIAL_LICENSE.md) and [third-party material](LICENSES.md).

A model proposal, passing unit test, successful platform dispatch and verified physical
outcome are separate evidence. Guides preserve that distinction; missing adapters,
permissions, device checks and unknown costs stay visible rather than being inferred
from a UI control or catalogue entry.
