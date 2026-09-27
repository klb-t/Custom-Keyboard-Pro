# Expert settings and portable profiles

`Settings` is the runtime snapshot. Its JSON codec is the persistence contract;
`SettingsSchema` derives controls from that codec, and `Knobs` contributes numeric
expert options. A reflection test compares every Settings property against the
codec (allowing only the flattened `knobs` map), so adding a property without
persisting/exposing it fails the test.

The expert screen is a lazy searchable list with no expert-only visibility gate.
It supports modified-only filtering, a reset per setting, exact numeric entry,
and explicit Apply/Discard for JSON and action text. JSON must be complete,
numbers must be finite and within their documented ranges, and closed enums must
name a supported value. Open provider/model/layout identifiers stay extensible.

## Profiles

`app/src/main/assets/settings_profiles.json` contains partial version-1 profiles.
Their values use the same schema and validation as direct editing. Profiles cover
touch and screen locks, selective hardware controls, persistent shortcuts, literal
typing, floating and free keys, larger targets, quiet feedback and local conversion
with prediction disabled. Applying a profile changes only its listed keys.

From **Every setting → Profiles & import**, users can:

- Preview a profile's changed values before applying it.
- Save the current search/modified-only selection as a named local profile.
- Export that selection or paste a versioned/legacy settings patch.
- Undo the last applied profile while there have been no intervening changes.

```json
{
  "format": "io.matrix.settings-profile",
  "version": 1,
  "id": "my-shortcuts",
  "name": "My shortcuts",
  "description": "Keep my shortcut board available",
  "values": {
    "fieldlessLayoutId": "hacker",
    "keyboardKeepVisible": true,
    "keyboardToolbarVisible": true
  }
}
```

Imports validate the complete patch before applying it. Unknown keys, unsupported
versions, invalid values and credential/private fields are errors; none are
silently discarded. Export excludes provider credentials, account definitions,
auth-bearing URLs, private text and opaque automation payloads. Those have their
own local editors. A profile is consequently portable configuration, not a complete
backup. Existing credentials and unrelated settings survive import.

## Contextual configuration

`AllSettingsScreen(settings, initialQuery)` accepts a key or search phrase.
`RequestPanelScreen(settings, initialRequest)` offers deterministic local matching
from `settings_search_aliases.json`, including Polish and English intent hints.
The user selects actual controls and can save that panel without an account.
Model-assisted panel generation remains an explicit optional action. The model
receives no credential/private-field values; generated controls still must name
real schema keys.

## Persistence

Updates are synchronized and appear immediately in the runtime StateFlow. One
conflated IO writer coalesces 100 ms bursts before encrypting and atomically writing
the snapshot; dragging never fsyncs on the UI thread. `persistencePending` and
`persistenceError` report durability separately from the visible state.
`awaitPersistence()` is available to deliberate save flows. A failed write preserves
the previous disk file and the current unsaved edits; retry is explicit. An initial
read/decryption failure prevents overwriting the unreadable data.

Provider credentials use the device-bound encrypted settings store. Portable
profiles omit them. Saved profiles have their own local storage and cannot replace
bundled profile identities. Corrupt saved-profile data is reported and preserved.

## Limits

Local request matching is keyword/alias search, not arbitrary natural-language
understanding. JSON validation checks syntax and generic setting types/ranges; the
individual feature parsers remain responsible for their nested semantics. Android
controls actual IME visibility and system navigation/power keys; settings cannot
guarantee capabilities that Android does not grant.
