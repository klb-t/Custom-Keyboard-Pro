# Keyboard gestures, toolbar profiles and visual geometry

This document describes the implemented source behavior. Android SDK build/test results belong to the current validation checkpoint; the presence of a test file is not a claim that it passed on a phone.

## Gesture ownership

`KeySurface` keeps one pointer loop for the authored layout, surface id, host and actual surface size. Shift, an automatic one-shot reset, a changed display layer or a settings write does not replace that owner. A real definition replacement, resize or removal cancels outstanding timers, popups and held momentary modifiers.

Every admitted finger retains its `PlacedKey`, complete key bindings, immutable effective settings and action callback with the exact authored origin. Repeat, long press, popup release and ordinary release use this snapshot. A finger deliberately sliding onto another key starts a new admitted target with the current policy. New fingers use the current geometry and policy through `rememberUpdatedState`; no scoped-settings parsing happens per pointer sample.

The concrete bottom-row defect was a `pointerInput(settings, layerName)` restart: releasing Shift could change `layerName` while another finger held Ctrl, Alt or a letter. The other finger's pending release was discarded. Compose multi-pointer tests cover Ctrl held while Shift changes, a held character across layer/settings changes, and lifecycle cancellation of a momentary modifier. Momentary modifiers act on down and clear on release/cancellation; one-shot/toggle/lock keys retain their ordinary release behavior.

Automatic Shift resolves policy against the authored base key, even when an explicit shifted layer supplies its display. If that key declines `autoCapitalize`, its base text and label are used verbatim; intentional capitals in the base definition are preserved. Manual and locked Shift keep the exact authored shifted-layer policy and text. Dispatch retains this origin so another key or panel cannot supply its overrides. The UI snapshot controls admitted thresholds, bindings and origin. At dispatch the host resolves the latest typing/privacy settings, so a privacy or permission change is not kept alive by an old finger snapshot.

## Scientific punctuation

The first quick hold alternate is the standard shifted punctuation: `/` → `?`, `=` → `+`, `-` → `_`, `;` → `:`, `'` → `"`, `\` → `|`, brackets → braces, comma/period → `<`/`>`, and number keys → their usual shifted symbols. The hint describes that first result. Release without movement selects it.

The scientific layout also defines a real Shift layer, preserving matching key ids and non-tap bindings. Mathematical alternates remain in the quick strip and existing symbol-board groups. Keys such as minus and equals now offer both: the strip first, then the board after the configured additional hold time. This uses the existing general key mechanism rather than a science-only gesture recognizer.

## Toolbar as data

`Settings.toolbarRowsJson` has global, layout and panel scope. An empty string retains the legacy adaptive single strip and its visibility switches. A non-empty value is validated by `ToolbarRows.parse`; the renderer falls back to the compatible strip for invalid imported data. Row ids are unique and stable, source names are validated, input is bounded to 16 KiB, and at most four rows are allowed.

```json
{
  "version": 1,
  "rows": [
    {"id": "words", "sources": ["suggestions", "contextual"], "visibility": "always", "reserveSpace": true},
    {"id": "tools", "sources": ["tools"], "visibility": "always", "reserveSpace": true}
  ]
}
```

| Row option | Behavior |
| --- | --- |
| `sources` | Ordered composition of `suggestions`, `tools`, `contextual`; each source appears once per row and may repeat in other rows. |
| `visibility=always` | Draw the configured row. |
| `visibility=content` | Show its contents when an enabled source has content. |
| `visibility=contextual` | Show contents when contextual actions exist and no panel replaces the keys. |
| `reserveSpace=true` | Keep the row's height when its visibility condition is false, preventing keys from moving during typing. This is the default. |
| `reserveSpace=false` | Allow the row to appear/disappear with its visibility condition. This is an explicit dynamic-height choice. |
| `rows=[]` | Explicitly remove configured toolbar rows. |

Profiles are instances of this format: **One mixed row**, **Suggestions + tools**, and **Two mixed rows**. `ToolbarRowsEditor` provides profile selection, source selection, row ordering, removal/addition and visibility/reservation controls. Tools scroll horizontally with accessible labels; a narrow screen does not force seven fixed buttons to overflow. Notices replace the first existing row rather than adding height.

Completion remains a separate semantic append row. Suggestion acceptance may replace a word; completion acceptance appends a chosen slice. Repeated/mixed toolbar sources do not merge those edit semantics.

The root measures the same slots that are drawn. Additional rows reduce available key height within the bounded panel budget, and row height is reduced on short screens. Scoped settings are resolved from the original layout/panel/key instances before rendering; transformed labels/bindings do not introduce a new settings instance.

The keyboard body additionally budgets its actual measured viewport. A 140 dp floating panel has only 114 dp below its grip; four 42 dp toolbars could previously consume that entire body before keys were measured. `KeyboardViewportBudget` retains at least 45% of a short body for keys (up to a 96 dp minimum on larger bodies), reducing toolbar/completion/indicator/padding chrome together. The same measured budget supplies the actual drawn heights through resize.

Those sizing choices are bounded numeric Expert settings in the existing `Knobs` registry. They use canonical persistence/native controls and may be overridden by a layout or the main viewport panel; other panels and keys do not own a separate shared viewport.

| Expert setting | Default | Allowed range |
| --- | --- | --- |
| `tune.viewportMinimumKeysDp` | 96 dp | 24–240 dp |
| `tune.viewportMinimumKeysFraction` | 0.45 | 0.2–0.8 |
| `tune.toolbarRowHeightDp` | 42 dp | 24–72 dp |
| `tune.keyboardMaxScreenFraction` | 0.85 | 0.3–0.95 |

The root screen budget and actual measured body consume the resolved policy. Defaults preserve the validated behavior above; stronger minimum key-area preferences shrink chrome earlier. Resource/validation limits such as the four-row bound remain fixed.

The current shared toolbar belongs to the main docked panel (the first visible docked element); its panel override supplies those rows. Other docked pieces and loose/free/control elements do not create their own toolbar surface. Their other eligible local visual/touch settings are consumed, but a `toolbarRowsJson` override on an element without a toolbar is not drawn. Per-element toolbar chrome needs its own sizing contract before it can be advertised for those elements.

## Visual geometry

`LayoutGeometryEditor(layout, onChange)` edits a local draft with layer selection, actual pointer dragging, multi-selection, a 1% snap grid, undo/redo, cancel and apply. Cancel never calls `onChange`. Apply sends the complete updated layout to the existing validated persistence flow.

| Mode/operation | Stored effect |
| --- | --- |
| Row drag | Reorder an existing key within its row or move it into another row. The drop index excludes the dragged key. Row weights/padding and the complete `KeyDef` are retained. |
| Use free positioning | Convert only the selected layer's current placed geometry into normalized free-key rectangles. Existing row weights/padding are reflected in the frozen rectangles. Undo restores the row definition. |
| Free drag | Move the selected group with a snapped anchor, then clamp the group inside 0..1 without compressing relative spacing. |
| Align | Align selected free keys left/right/top/bottom or along horizontal/vertical centres of their combined geometry. Individual dimensions are preserved. |
| Spacing | Give at least three selected free keys equal clear horizontal/vertical gaps, including differently sized keys. Refuse an impossible overlapping arrangement. |
| Undo/redo | Restore immutable complete layout snapshots; history is bounded to 50 entries. |

Key ids, bindings, popups, styling and scoped settings are preserved. Operations target the exact selected layer; same-id keys in another layer remain untouched. Duplicate key ids in a layer and invalid free bounds are refused by the pure geometry engine. The editor does not infer unrelated binding or settings changes from a drag.

## Focused verification

- `LayoutGeometryTest`: six tests for cross-row/same-row drops, weighted geometry conversion, group edge clamping, variable-width alignment/spacing and refusal of ambiguous/impossible geometry.
- `ToolbarRowsTest`: four tests for profile round trips, repeated roles, stable/dynamic visibility, legacy/zero-row behavior and invalid/bounded data.
- `ScienceLayoutBehaviorTest`: two tests for standard quick alternates/Shift text with preserved mathematics, and automatic/manual capitalization with intentional authored capitals.
- `KeySurfaceGestureTest`: three real Compose multi-pointer/lifecycle regressions described above.
- `KeyboardViewportBudgetTest` and `KeyboardViewportTest`: two budget contracts and a real Compose measurement regression reproducing zero-height keys with the former fixed chrome, then retaining the key area through a 140 → 220 → 140 dp floating resize.
- `ViewportExpertSettingsTest`: native numeric metadata, Expert visibility, validation/persistence, and actual budgeting from resolved layout/main-panel values with local-policy suppression.

These checks cover deterministic renderer/editor contracts. Navigation-bar/OEM window behavior, physical multi-touch, and a full interaction review on the user's device remain physical-device checks.

The initial SDK 36.1 checkpoint on 2026-09-30 passed the first six test classes (18 tests) with zero failures/errors, including the measured floating-viewport reproduction and resize regression. Main and test Kotlin compilation passed. The combined unit suite reported 936 tests, 935 passed, zero failures/errors and one existing FileProvider assumption skip. The subsequent Expert policy/settings changes await the next shared gate; lint and APK outcomes are recorded by that checkpoint separately.
