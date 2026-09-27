# Clipboard recovery and usable workspace (4.3)

All bulk Clear commands route to one inline confirmation, including bindings/macros.
The reviewed snapshot excludes pins; a later copy or pin is never swept into it.
Manual deletes move to persistent Trash, retaining owned image bytes. Undo works in
the current panel and Restore works after reopening/restarting. Default retention
is 24 hours (expert range 1–168); expiration is swept when the keyboard starts.
Automatic history capacity/age policies remain separate and are still configurable.
Room migration 3→4 preserves content and initializes stable IDs for future merging.

Clipboard accepts image shares, multiple images and explicit system-document picks.
It stores bytes, not a temporary grant. Bounded thumbnails decode on an IO thread.
The explicit Screenshot command uses accessibility capture on Android 11+, and
window capture on Android 14+ when possible. Protected-window refusals remain
refusals. Android 9–10 uses system capture; share the result into IO Matrix. Other
apps' automatic screenshots are not secretly watched. Android 11–13 full-display
capture may include the IME. Private active fields are excluded from direct capture.

Cursor rectangles are transformed with CursorAnchorInfo.matrix before comparing
with screen-space panel rectangles. Insets use actual window coordinates, including
split windows, rather than physical screen height. Cursor monitoring requests an
immediate report and ongoing reports; settings, system bars and layout changes
coalesce to one check per frame. No disk/network work occurs there.
Floating panels stay within current safe constraints and choose above or below the
field; if neither fits the configured fade is used. Reserve-space policy remains
stable rather than alternating on each editor resize. Compound layout elements keep
their authored constraints; independent per-element cursor avoidance is future work.

New installations enable cursor avoidance. Existing explicit choices are retained.
For applications refusing cursor reports or ignoring IME insets, Android does not
let an IME move their text field directly. Use FULL insets / RESERVE_SPACE, or adjust
floating position. Real acceptance still requires ChatGPT Android, split-screen,
rotation, gesture/three-button navigation, OEMs and image paste into a receiving app.
Unit/CI success is not a claim that these device checks happened.
