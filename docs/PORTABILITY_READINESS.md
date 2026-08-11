# Portability Readiness

## KMP-Readiness
- The `com.example.domain` package is highly decoupled from Android.
- UUID, Time, and Logging generation have been abstracted through `IdSource`, `MonotonicClock`, `WallClock`, and `DiagnosticBus`.
- UI uses `Jetpack Compose` which is heavily multi-platform ready (Compose Multiplatform).

## Remaining Android/JVM leaks
- The `LocalSensitivityPatch` uses `ByteArray` which is fine but could be replaced if needed by more raw buffer types for C++.
- The `InputConnectionSelectionBackend` heavily ties to `android.view.inputmethod.InputConnection`.
- UI inputs tie into Android-specific View systems via `PointerEvent` wrappers that we manually map from `MotionEvent`.
- `UUID.randomUUID()` might still be lingering in few models for defaults, need `expect/actual` or factory injection for KMP.

## Future C++ Engine Candidates
- `ProbabilisticSampler` - if number of candidates scales up and spatial index becomes bottleneck.
- `CoordinateTransform` - heavy matrix operations when scaling to complex multi-touch gestures.
- Coarse Boundary: `InputProcessor` processes `PointerEvent` into `ProcessorResult`.
