# Quick Settings, shortcut sessions and pocket lock

The two tiles share one action coordinator. It dismisses the shade before requesting
an IME window or installing the lock overlay. Android 12+ accessibility dismissal
is used only when the service is running and `performGlobalAction` succeeds.
Otherwise `TileService.startActivityAndCollapse` launches a transparent,
non-focusable activity that finishes before executing the action. Android 14+
uses its `PendingIntent` overload. The bridge creates no editor and no replacement
input connection; shortcuts remain attached to the system's actual input target.

Holding either tile opens the schema-backed expert settings through
`ACTION_QS_TILE_PREFERENCES`. `Intent.EXTRA_COMPONENT_NAME` distinguishes the lock
from the keyboard. The lock's permission route offers the actual supported hosts:
accessibility or display over other apps.

## Shortcut visibility

`keyboardKeepVisible` keeps a user-started shortcut session across input-target or
foreground-app changes. Enabling it while the keyboard is visible pins that session.
A restore is one delayed request per target change, not a loop from the hide callback.
Restores are suppressed while the device is locked, the display is off, the pocket
lock is active, or IO Matrix itself is the input target. Hide, Back, switching input
methods, opening the settings app or tapping the active tile ends the session.

`fieldlessLayoutId` is a temporary session layout. It does not overwrite the global
or remembered per-app typing layout. `tune.keyboardRestoreDelayMs` and
`tune.keyboardShowTimeoutMs` control restoration timing and the initial refusal
message. `tune.pocketShadeDelayMs` is shared by both tiles.

Android still decides whether an IME can appear without an editable field or a
served input target. No `SHOW_FORCED`, hidden API or fake editor is used. A refused
initial request reports the problem and clears its session instead of leaving a
misleading active tile. This implementation does not promise global hardware-key
injection into arbitrary applications.

## Independent lock domains

| Setting | Effect | Limit |
| --- | --- | --- |
| `pocketBlockTouch` | Overlay consumes touches | System edges and trusted system windows may remain reachable |
| `pocketMode` | Leave visible (`TOUCH`) or black out and dim (`SCREEN`) | Blackout is not physical screen power-off |
| `pocketBlockKeys` | Consume volume keys | Only events Android delivers |
| `pocketBlockMediaKeys` | Consume headset/media keys | Only events Android delivers |
| `pocketBlockNavigation` | Consume Back key events | Home, Recents, power and system gestures are controlled by Android |
| `pocketBlockOtherKeys` | Consume other delivered physical keys | Focused ordinary overlays cannot guarantee forwarding allowed keys to the underlying app |
| `pocketRestoreApp` | Try reopening the original app after a foreground change | Requires accessibility; launching may still be restricted by Android |

All booleans are independent and exposed through the settings schema. Existing
profiles without separate Back/other settings inherit the old `pocketBlockKeys`
value, preserving their earlier behavior.

A full-opacity ordinary app overlay with touch pass-through is rejected on Android
12+, where the OS blocks those untrusted touches. The accessibility overlay is a
supported host for that combination. Touch pass-through requires a valid direct
key escape. When touch is blocked and both key and hold escapes were disabled or
invalid, a two-finger hold remains available. The proximity policy still applies.

The unlock matcher retains overlapping prefixes and enforces the whole-sequence
time window. Consumed key-down events retain their repeats and key-up events even
if the down event unlocks the overlay, avoiding a lone release in the foreground
app. Physical power and other system-controlled keys are never consumed by policy.

## Verification

Pure tests cover independent key-domain combinations, system-key exclusions,
unlock fallback, paired release bookkeeping, overlapping/timed unlock sequences,
private-screen restore suppression and explicit pin cancellation. These do not
substitute for device checks of Android window management.

Device acceptance checks:

1. On Android 12+ without accessibility, tap the keyboard tile over an existing
   editor. Confirm the shade closes, the original editor retains input, and Hide
   leaves the keyboard hidden. Repeat with accessibility enabled.
2. Enable persistence, start a tile session and change apps. Confirm at most one
   restore request, no restore over the device lock or IO Matrix settings, and a
   second tile tap stops the session. Test an app with no editable input target and
   confirm refusal feedback instead of repeated requests.
3. Hold each tile and verify the appropriate expert settings. Change each lock
   domain independently, including media pass-through, then verify the unlock key
   receives no orphan release in the foreground app.
4. Try both lock hosts, including revoked permissions, touch pass-through,
   unsupported blackout pass-through and disabled/invalid escape profiles. Confirm
   unsupported combinations explain the reason and a blocking overlay always has
   a direct escape.
5. Start a macro, focus a password or vault field, then type. Confirm recording is
   discarded and no secret action is serialized. Suggestions must not fetch private
   surrounding text.

## Platform references

- [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- [TileService: preferences and activity collapse](https://developer.android.com/reference/android/service/quicksettings/TileService)
- [InputMethodService visibility requests](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)
- [Accessibility global actions](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 12 untrusted-touch behavior](https://developer.android.com/about/versions/12/behavior-changes-all)
- [System-owned key events](https://developer.android.com/reference/android/view/KeyEvent)
