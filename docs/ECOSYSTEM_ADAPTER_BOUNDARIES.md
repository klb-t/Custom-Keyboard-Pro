# Ecosystem decisions for iOmatrix

Updated 2026-09-30. Root [ECOSYSTEM.md](../ECOSYSTEM.md), copied unchanged from
`main` commit `8e8aa9f`, is the shared conceptual source. This note records how the
current implementation keeps those directions possible. It is not a claim that
cross-project integrations, shared knowledge memory or structural graph paste exist.

iOmatrix remains independently useful. Keyboard input, local profiles, clipboard
history and available local tools work without ChatADHD, Loom, AGEDS, WatchDog or a
shared server. Cooperation can be bidirectional through capability adapters; a
project name must not select a different execution mechanism. No universal database,
graph semantics, provider or runtime is imposed by this stage.

| Boundary | Design decision | Existing foundation / remaining adapter |
|---|---|---|
| Work objects | Preserve stable identity, source, representations and transformation history. Each adapter declares its semantics, losses and uncertainty. | Matrix provenance and explicit conversions exist. Typed graph/structure clipboard exchange remains open. |
| Knowledge and corrections | Keep observed material, a user declaration, a model hypothesis and an execution receipt distinguishable, with source/time/corrections. Observing a statement does not verify its content. | Local dictionaries/corrections and typed goal outcomes exist. Shared knowledge/memory adapters remain open. |
| Learned preferences | Represent a proposed preference as a partial profile for a precise scope. Apply through the canonical resolver, preserving global privacy boundaries and ignore-local policy. | Sparse layout/panel/key overrides, effective-source UI and validated instance profiles exist. Cross-project learning proposals remain open. |
| Procedures | Transfer goal, parameters, dependencies, prerequisite descriptions and verification methods. Reassess the receiving host/device. | Bounded goal definitions import/export without approval or execution state. Procedure discovery/learning remains open. |
| Selection and context | Anchor a fuzzy selection to source material, candidate ranges and uncertainty. Resolve the concrete target before an effect. | Exact editor targets and explicit capture boundaries exist. Typed fuzzy-selection exchange remains open. |
| Authority | Sharing data, profile definitions or historical receipts never transfers credentials, grants, session identities or execution approval. | Credentials are omitted from portable profiles/sync; goal tickets bind exact sessions and fresh facts. |
| Host and runtime | Keep canonical definitions separate from geometry, trigger recognition, transport, rendering and effect adapters. Android is the first host profile. | Layout/geometry, typed settings and canonical verbs are distinct. A future portable codec/runtime is a separate adapter effort. |

The current receipt vocabulary matters across the ecosystem: an opened setup page
or dispatched request is not a verified result; a user-confirmed observation remains
labelled as such. Local diagnostic data is not automatically returned to a model.
Imported provenance describes a history and is not authenticated proof of an action
on the receiving device.

Current sync shares reviewed portable settings and clipboard text/images through
encrypted file or Google Drive app-data exchange. It does not synchronize all
conversation history, ecosystem knowledge, fuzzy selections or arbitrary graphs.
Existing schema versions and instance addresses should be retained; define explicit
versioned adapters and loss reporting before widening that contract.

Next work is driven by a concrete cross-project use case and its acceptance criteria,
not by pre-installing speculative links. Possible first cases are a reviewed
correction/preference proposal, a structural clipboard object with a text fallback,
or a reusable procedure definition. Keep these as alternative capability profiles
until a real producer, consumer and permission boundary are implemented and tested.
