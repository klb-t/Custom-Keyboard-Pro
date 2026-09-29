# Conversation capture — exposed text, details and structure

Status: implementation on `feat/conversation-capture-2026-09-29`, based on active
branch commit `9b76a39b4fbbfb98e961eeeb225091381b7983eb`. This is **not a new verified
Android release**. Keep the existing 4.4.2 APK until the Android build/device gates
below pass. No new permission, dependency, signing change or CI workflow is added.

## Accessibility-tree follow-up (schema version 2)

The current implementation extends the initial visible-only capture. Read
[ACCESSIBILITY_TREE_CAPTURE.md](ACCESSIBILITY_TREE_CAPTURE.md) for non-visible
nodes, semantic expansion, nested scrollers, full-window/DOM inspection and the
updated validation record. The original implementation notes below describe the
initial checkpoint; the follow-up document supersedes its visible-only and
caption-only disclosure restrictions. The Android build/device gates remain open.

## What the inspection found

`IoAccessibilityService.screenText()` reads one visible screen, caps output at
20,000 characters and deduplicates text with a set. `PageReader` scrolls for speech
and suppresses globally seen lines. `sweep` selects list items/checkboxes; it is not
a general text-range selector. None is a lossless whole-conversation exporter or
an automatic disclosure-expansion engine. They are left unchanged.

## Android entry points and operation

The canonical action profile now includes **Capture conversation**. It uses the
existing generic Open mechanism, `open iomatrix://capture`; a custom key/wire can
bind `do:open iomatrix://capture`. Existing customized profile JSON is not silently
replaced: add that command manually when the new default profile is not shown.
A **Capture conversation** Quick Settings tile is also registered. Its launch
reuses the existing supported shade-collapse bridge.

The link/activity opens controls, never a capture. It ignores incoming parameters
and requires an explicit **Start** even when another app opened the link. With
accessibility off it explains the requirement and offers system settings; typing
continues to work without accessibility.

The consent panel offers automatic versus manual scrolling, an optional attempt to
reach the beginning, recognized disclosure expansion, and 50/200/500-frame limits.
The small running control is draggable and includes **Stop & review** and a
confirmed discard. Insets/rotation bound the control to the screen; small-screen
consent is scrollable. A review screen provides real text selection, Copy text,
Save text and Save JSON. Clipboard transfers above 240,000 UTF-8 bytes are refused
with a file-save alternative, not silently truncated. File writes run off the UI
thread through the system document picker.

**Try system selection of focused text** requests `ACTION_SET_SELECTION` only on
a non-private accessibility-focused node that advertises it. It does not claim to
select arbitrary app screens, concatenate native selections across messages, or
read an editor draft. The captured review is the fallback when a source app does
not implement selection.

## Mechanism and policy

* `core/capture` contains platform-free options, the bounded state machine, raw
  frame archive/JSON and a disclosure-label policy. The Android driver is separate.
* The driver pins an application package, window and scroll-container identity;
  every read/scroll/expansion reacquires and validates fresh node handles. There is
  no screen-coordinate tap/swipe fallback. An app/window/container change, lock,
  service loss or read failure stops the session. Node handles are recycled.
* Password/editable subtrees are excluded. Aggregate ancestor text is also excluded
  when a descendant is private or uninspected. The per-frame traversal has node,
  depth, text and time bounds. Any clipping is exported and stops collection.
* Forward actions wait for two equal observations. Streaming text is periodically
  retained without clicking a moving UI. Polling exists only in a visible session;
  Stop removes callbacks, and finished sessions perform no further operations.
* Native expansion needs a recognized label and `ACTION_EXPAND`, or a recognized
  clickable disclosure with an explicit localized collapsed state and no collapse
  action. Links, checkboxes and inputs are excluded. Attempts are bounded per scroll
  epoch, not deduplicated globally by label. Unknown buttons are never guessed.
* A global text set is deliberately not used. Raw JSON retains viewport-local
  parent paths, text, descriptions, classes, resource IDs, states, bounds and
  expansion revisions. Paths are not stable message IDs. Android author roles are
  not invented. The convenience text removes only strong adjacent multi-block
  overlaps, annotates boundaries/gaps/revisions and is **not** a lossless substitute
  for raw JSON.

The source app may itself load data when scrolled or expanded. The collector makes
no model calls, uploads, direct network requests, OCR or screenshot requests.
Capture stays in memory until the user copies/saves it. Review closure discards its
in-memory result; process termination loses unsaved data. Save important results.

## Browser DOM alternative

`tools/conversation-capture.browser.js` is a standalone local browser helper. It can
be run as a user-controlled script in an already authenticated conversation page;
loading it only displays controls. Press **Start**, then **Stop** or wait for its
bounded scan, review the selectable preview, and choose **Save text / Save JSON**.
This is not a packaged browser extension and is not an assertion that Android
Chrome can install userscripts. The Android accessibility path above also targets
browser windows, without DOM injection.

For a developer-controlled browser console, execute the script file, then its panel
is available. Explicit programmatic use supports a nonstandard conversation root:

```javascript
const capture = IOConversationCapture.start({
  root: document.querySelector('main'),
  // scroller: document.querySelector('#actual-conversation-scroll-container'),
  autoScroll: true,
  seekStart: true,
  expand: true
});
// capture.stop(); // explicit cancellation
const result = await capture.done;
// IOConversationCapture.text(result) is the selectable text representation.
```

The DOM adapter preserves tag/child structure, code/preformatted blocks, exposed
role attributes and stable message identifiers when supplied by the page. Latest
keyed message revisions form a convenience view; all recorded revisions remain in
raw frames. Identical replies with different IDs remain separate. Missing/duplicate
IDs and content outside keyed turns fall back to raw frame sections rather than
being silently discarded. Native nested `<details>` are opened without clicking
summary links; recognized non-form `aria-expanded=false` buttons may be clicked.
Editable controls, hidden content, scripts and arbitrary application state are not
read. No React-state scraping, private endpoint calls or cross-origin iframe access
is attempted. Only origin/path are recorded for provenance, not URL query tokens.

## Limits that must remain visible

This exports only content actually exposed by the source UI. Visible reasoning
summaries and tool details can be captured after expansion; hidden internal model
reasoning, unavailable payloads, attachment/media bytes and canvas-only text cannot
be recovered by these adapters. Screen/DOM structure is not proof of the chatbot's
internal conversation schema.

Reaching a local scroll boundary is **not proof of a complete conversation**.
Virtualization, delayed loading, very long messages, unsupported scroll actions,
filters, hidden branches and resource limits can leave gaps. Both formats state
`completeness: unverified`, with stop reason, start coverage, clipping and provenance.
Do not relabel them complete just because no new text appeared.

Stay in the same conversation/tab. Native package/window checks cannot reliably
distinguish navigation to a different chat inside the same window/container. The
browser checks origin/path, root attachment and tab visibility, but same-path
navigation is likewise not universally identifiable. Native expansion that opens
another window stops; browser portals outside the selected root, closed shadow
DOM and frames require separate explicit support. Generic controls are conservative;
real apps may require additional **data profiles and tests**, never blind clicking.

## Validation performed here

* 24/24 platform-free regression methods passed using Kotlin/JVM 1.9.0 and Java 21,
  with `tools/test-capture-core.sh`. This runner supplies only a local `@Test`
  annotation; test bodies use Kotlin `check()`. Gradle uses the actual existing JUnit
  dependency. This is not a claim that the full Android/JUnit project suite ran.
* 24/24 offline Chromium fixture checks passed with
  `python tools/test_browser_capture.py`, including nested disclosures, private
  inputs/aggregate labels, hidden direct text, repeated replies, virtualized history
  starting at the bottom, cancellation, limits, target removal, duplicate IDs and
  standalone tool content outside keyed messages. No live chatbot account was used.
* Independent Python JSON parsing verified control-character and Unicode round trips.
  XML/JSON wiring was parsed, and unchanged manifest/profile content was checked
  against the exact original Git blob hashes.

## Outstanding acceptance gates

1. Run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with the
   repository's SDK/Gradle versions. No Android SDK/full dependency checkout was
   available in this session; the Android adapter/UI has **not been built**. No APK
   is published for this feature, and no CI success is claimed.
2. Validate the action/profile/tile, activity/shade return, Android API 24/30/34/36,
   TalkBack/system selection, secure/editable fields, lock/app switching, rotation,
   split screen, navigation insets and the last reachable Stop control on devices.
   Validate focused-field avoidance; a draggable top overlay is not proof that every
   host/OEM lays it out correctly.
3. Test actual native chatbot apps and mobile/desktop browsers: long/virtualized
   chats, delayed tool panels, nested disclosures, tabs/dialogs, repeated messages,
   clipboard size handling, document save failures and process termination.
4. Review accessibility disclosure/store-policy requirements before publication.
   The runtime consent implemented here does not establish store acceptance.

Do not merge this feature into a verified-release claim before those gates pass.
