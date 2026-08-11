# Foundation Sprint Progress

## Completed Phases
- **Phase A (Truth audit & repairs):** Fixed `InputPipeline` element vs action ID dispatch, typed diagnostic values with `DiagnosticValue`, removed fake session IDs, implemented `isHealthy` sink states, added true entropy calculation.
- **Phase B (Portable Core):** Abstracted `IdSource`, `MonotonicClock`, and `DiagnosticSessionContext`.
- **Phase D (Selection):** Implemented `Selection`, `SelectionIntent`, `TextBoundaryProvider`, `SelectionEngine`, and the first `InputConnectionSelectionBackend`.
- **Phase E (Declarative Profile):** Added schema/profile experiment using JSON. Implemented `validate_profiles.py`, `openrouter_inference.json`, and `first_branch_manifest.json`.
- **Phase H (Phone-only Build):** Added GitHub action workflow `.github/workflows/build-debug.yml`.
- **Phase I (Architecture Docs):** Created `docs/PORTABILITY_READINESS.md`.

## Notes
- `UUID.randomUUID()` default params remain in domain models (`Structure.kt`, `Action.kt`). They should be refactored to factories before full KMP extraction.
- Selection implementation utilizes `InputConnectionSelectionBackend` as the primary Android realization for `SelectionEngine`.
- Tests run green, all profiles validate.

## Readiness for manual stability test
- Yes, the core probabilistic models, input processor decoupling, diagnostics pipeline, and selection foundations are ready for the next QA test phase.
