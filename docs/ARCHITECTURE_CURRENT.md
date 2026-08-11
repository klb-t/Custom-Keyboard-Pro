# Current Architecture

## Input Pipeline
- **InputEvent** (e.g. `PointerEvent`) is created by platform bindings.
- **ProbabilisticInputProcessor** handles input processing.
  - Transforms coordinates.
  - Queries `SpatialCandidateIndex` for candidates.
  - Uses `ProbabilisticSampler` to sample raw patches.
  - Uses `ArgmaxDecisionPolicy` to declare a `Winner` element.
  - Resolves `Element ID` to `ActionRef`.

## Sensitivity
- `LocalSensitivityPatch` stores per-element local fields.
- Overlapping elements are sampled properly via bounds tracking and an explicit index.
- Absolute confidence logic applies via `BACKGROUND_KEY_ID`.

## Actions
- Separated from Element IDs. `ActionDefinition` represents reusable behavior logic.
- Includes generic actions (e.g., `CommitText`, `ExecuteMacro`, `SelectionAction`).

## Selection
- `SelectionEngine` controls selection manipulation via `SelectionBackend`.
- Supports various `SelectionIntent` commands (Move, Extend, SelectAll).

## Observability
- Driven by `DiagnosticBus` and `DiagnosticSink`.
- Strongly-typed `DiagnosticValue` objects decouple tracking from string logs.
- Privacy tracking built-in via `SensitivityClassification`.
