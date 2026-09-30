# Configuration owners, inheritance and presentation levels

Configuration has two independent dimensions. An option's **owner** determines
what it changes; a **level** determines when its control is shown. Choosing Basic
never erases an Expert value, changes a precedence rule, enables AI, or grants an
Android permission.

## Presentation levels

* **Basic:** standard keyboard choices: active layouts, size/theme, feedback,
  capitalisation/punctuation, suggestions, clipboard and speech language.
* **Advanced:** the previous broader surface, phone tools, layout authoring,
  model/provider setup and structured toolbar composition.
* **Expert:** every canonical persisted user setting and its ownership metadata,
  plus raw layout JSON and applicable local settings. Search reports additional
  matches that need this level; showing them requires an explicit selection.
* **Debugger:** Expert plus the separate runtime inspector. Persisted configuration
  is not presented as a live variable table. Runtime access comes from the host's
  published typed variables, not an arbitrary expression evaluator.

Fresh installations start Basic. Existing settings without `settingsLevel` retain
Advanced when the old `expertMode` flag was false, and Expert when it was true.
The selector writes the new level and the compatibility flag together. The legacy
flag remains in the canonical codec for old callers; its control explains this.
When a stored document explicitly includes the new level, that level is authoritative
and its compatibility flag is normalized. The canonical legacy setter updates both
fields; directly constructed old in-memory snapshots still display the old Expert
surface until normalized through the new codec.

## Shared owners and actual instances

`SettingSpec.owner` classifies all persisted keys as application behavior,
keyboard defaults, or stored instance state. The Settings explorer filters by this
owner and level, and resets an explicitly reviewed, frozen list of keys. Search
and profiles use the same canonical schema. An AI-generated settings panel is a
**view of values**, not a new owner of those values.

`SettingSpec.applicableScopes` separately names legal override locations. Credentials,
account configuration, permission/privacy guards, sync policy and arbitrary network
commands are shared. They cannot be injected through layout override JSON.

The initial cascading options cover local typing, punctuation, feedback, gesture
thresholds and key/panel rendering. The complete finite allowlist is in
`SettingsHierarchy`; adding an option requires a real contextual consumer. Toolbar
row composition applies to layouts and panels, not individual keys.

The native stored instances are:

| Location | Storage | Address |
| --- | --- | --- |
| Keyboard defaults | existing `Settings` field | canonical key |
| Layout | `LayoutDef.settingsOverrides` | layout ID |
| Panel | `ElementDef.settingsOverrides` | layout ID + panel ID |
| Key | `KeyDef.settingsOverrides` | layout ID + exact layer + key ID, optionally panel ID |

Values are sparse, immutable typed `SettingsOverrides`. Missing means **inherit**;
a copied default is an explicit override. The existing `LayoutJson` codec writes
these fields only when nonempty and strictly validates them when present. Legacy
layouts have empty overrides and keep inheriting defaults.

Normal precedence is **key > panel > layout > keyboard defaults**. Resolution
returns both a `Settings` snapshot for existing consumers and source metadata for
inspection. Exact layer addresses avoid changing every `a` key in every layer.
The first visible docked panel follows layer switching, matching the renderer;
other panels retain their declared layer. An address for another layout, missing
panel, ambiguous key, or unrelated layer fails before mutation.

`localSettingsPolicy` provides three device-wide choices:

* `CASCADE`: use the complete chain above.
* `LAYOUT_ONLY`: ignore panel/key values, retaining layout overrides.
* `DEFAULTS_ONLY`: use keyboard defaults, ignoring all local values.

Ignored overrides stay stored. The contextual editor distinguishes the **stored
local value**, the **effective value/source**, and the sources suppressed by policy.
“Inherit” removes only the selected instance's override.

## Editing and profiles

Layouts opens a contextual local-settings editor from the selected layout.
All edits remain in its draft until Save; switching the active layout elsewhere
does not redirect the captured target. Save rejects a layout that changed after
opening. Keys, panels and layout defaults use the same control renderer, validation
and resolver. The editor never writes shared `Settings` values through its controls.

The same `SettingsProfiles` registry can capture an instance's explicit overrides
and apply a compatible partial profile to that instance. Profile application is
validated as a whole, changes only the addressed draft, and requires Save to reach
the layout repository. A profile containing a global-only key is rejected as a
whole. Global profile application remains a separate explicit reviewed action in
the Settings explorer.

Structural panel/key fields (placement, bounds, key actions, labels, indicators and
controls) remain in the native layout models and authoring tools. They are not
represented by a second unconsumed application-wide settings map.

## Verification and remaining boundaries

`SettingsHierarchyTest` covers fresh/old migration, level round-trips, visibility
without value changes, hidden search results, ownership and scoped resets.
`ScopedSettingsTest` covers precedence/provenance, inheritance removal, exact layer
and layout isolation, ignore policies, typed codec round-trips, strict rejection,
level independence, dynamic docked layers, profile atomicity and immutable values.
The existing Compose pocket-control smoke test explicitly selects Expert.

The full Android SDK compile, suite and lint results are recorded separately in
`RESUME.md` after the integration gate. Unit tests do not establish physical phone,
OEM, split-screen, keyboard focus or touch-device behavior. Preserve the last
verified APK until the new integration gate succeeds.
