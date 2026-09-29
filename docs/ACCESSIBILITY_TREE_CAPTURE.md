# Accessibility tree and collapsed conversation content

Follow-up to draft PR #3, 2026-09-29. Source implementation only: not a new APK.
Keep release 4.4.2 until the complete Android build, lint and device gates pass.

## What can actually be read

Offscreen, collapsed and absent are different conditions. A non-visible node can
still exist in the app's accessibility tree; if the provider exposes its text, it
can be read without putting pixels on screen. A collapsed disclosure may expose
only its control, not its descendants. In that case the capture must request
expansion and read fresh nodes. A lazy/virtualized item that has not been created
cannot be read from a nonexistent node; it requires scrolling/materialization.
No capability here inspects another app's heap, private model state, internal
network responses or payloads that the UI does not expose.

The optional `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` requests a broader reported view
hierarchy. It does not force hidden/lazy content to be created or override platform
privacy restrictions. The default is off; non-visible-node reading defaults on.
API 36 exposes expanded state (undefined, collapsed, partial, full). Older Android
versions use only the capabilities/state descriptions actually available.

Primary references checked when implementing:
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
- https://developer.android.com/develop/ui/compose/lists
- https://developer.android.com/develop/ui/compose/accessibility/merging-clearing

## Android implementation and controls

Open **Capture conversation** (existing tile/action, `do:open iomatrix://capture`).
The panel is consent, not an automatic capture trigger. It now offers:
- Read non-visible nodes exposed by the app (on by default).
- Request extended tree / not-important views (off by default).
- Capture nested scrollable panels (on by default; automatic mode only).
- **Inspect full accessibility tree (no actions)** for a bounded read-only snapshot
  of the active application window rather than only its selected scroll container.

The inspector reuses the same private-subtree filtering, limits and review/export
pipeline. It does not click, expand or scroll. Show text / Show tree switch a
selectable review; Copy preview, Save tree and Save JSON preserve the observations.
Changing accessibility service flags is temporary; inspector "no actions" means
no UI actions sent to the source app, not an absence of service configuration.

The shared `CaptureTreeReader` records hierarchy, paths, IDs, class, text,
description, state, bounds, provider visibility, viewport intersection, actions and
flags. API guards protect expanded/checked state (36), data sensitivity (34),
unique ID (33) and state description (30). Collection row/column metadata are kept
when supplied. Missing metadata remains missing, not inferred. JSON schema v2
stores observation diagnostics and the effective service flag bitmask.

The adapter requests REPORT_VIEW_IDS and RETRIEVE_INTERACTIVE_WINDOWS for the
explicit session, optionally INCLUDE_NOT_IMPORTANT_VIEWS; it restores only added
bits on exit without replacing unrelated existing service flags. No manifest
permission, dependency, signing config, release, CI workflow or typing path changes.

Automatic detail handling is ordered:
1. Semantic EXPAND on eligible visible nodes, without requiring English captions.
2. Semantic EXPAND on eligible non-visible nodes already exposed by the provider.
3. Advertised SHOW_ON_SCREEN for an eligible offscreen disclosure, then a fresh
   read before any toggle click. No coordinate click fallback.
4. Nested scrollable panels, deepest first, with bounded attempts to reach their
   start and collect forward. Outer scrolling resumes after no further progress.

A generic CLICK still requires a known disclosure caption, advertised click action
and explicit collapsed state. Full/unknown state is not treated as permission to
toggle. Before CLICK the adapter rechecks the current control subtree, including
child captions, so a recycled virtual row cannot inherit a previous row's caption.
Known destructive/unrelated captions, link/checkable/private/disabled controls are
excluded. Explicit EXPAND may be tried on partial state; toggle CLICK is not.
Opaque provider custom action IDs are recorded, not guessed or executed.

Every operation revalidates package, window and container; handles are reacquired
and released, never retained across ticks. New dialogs/windows stop the pinned
session rather than being followed blindly. Declared collection-position scrolling
may be used to try row zero; absent support falls back to advertised directional
scroll actions. Manual mode does not request show-on-screen or nested scrolling.

Counters mean **accepted requests**, not proven UI effects. Subsequent frames are
the evidence of changed content. Attempts, frame/text/time limits and no-progress
bounds prevent endless expansion/scroll loops. Request histories are scoped to a
scroll epoch; equal text is not a permanent message identity.

## Privacy and fidelity

Password, editable and API-declared sensitive subtrees are skipped before reading
text. Ancestor aggregate text/descriptions/state/action labels/unique IDs are also
removed when a private or unreadable descendant could be included. Visible-only
mode still traverses visible descendants of a non-visible container, without
leaking the omitted text through aggregate parents. Unknown/unavailable children,
clipping and exclusions are reported. Platform-sensitive content is not bypassed.

Raw frames retain repetitions and revisions; convenience text is not a canonical
chatbot record. A collection end, two equal snapshots, empty subtree or successful
EXPAND is never a completeness proof. Inspector output is a partial, bounded
observation of what the provider exposed at that instant, not the app's object tree.
Independent panels, separate windows, missing roles, large/truncated subtrees,
async content arriving after the settle interval and virtualized history may still
require manual review or a source-specific profile. Actual effects vary by app.

## Browser DOM path

`tools/conversation-capture.browser.js` is a standalone script run on the open
conversation page; no remote server, network interception or account integration.
`IOConversationCapture.inspect(options)` is read-only; the panel exposes it as
**Inspect DOM**. `readCollapsedDom: true` explicitly opts into already-present DOM
inside closed native details or a hidden panel controlled by a recognized collapsed
ARIA disclosure. The visible consent checkbox is initially checked, but the
programmatic option is off unless explicitly true.

No click is required for text already there. Records mark `collapsed-disclosure-dom`
versus `exposed-dom`, together with layout/offscreen and exclusion diagnostics.
Independently hidden descendants, unrelated hidden panels, scripts, form values,
password/contenteditable fields remain excluded. A script variable holding a future
payload is not DOM and is not extracted. For lazily created UI, explicit expansion
and settling remain necessary. Visible `aria-busy` postpones subsequent actions;
an unannounced long delayed response still has no completeness guarantee.

Native details and recognized ARIA buttons can be expanded, including nested
disclosures and role=button controls. Disabled/form/submit controls are excluded.
The capture pins the page location including query/hash while avoiding their
publication in source metadata. Node/text limits and cancellation still apply.
Iframe contents, canvas/media bytes and shadow-root descendants are not traversed;
omission markers report unsupported containers. This is NOT a packaged mobile
browser extension or a tested adapter for the current ChatGPT/Claude DOM.

## Validation performed

- `bash tools/test-capture-core.sh`: **51/51** standalone Kotlin/JVM regression
  methods passed. Covers the shared reader, policy, limits, archive and session.
  This is not the complete project Gradle/JUnit suite.
- `python tools/test-capture-adapter.py`: **16/16** deterministic fake-platform
  adapter checks passed. Compiles and executes the production adapter against
  minimal test doubles. This is NOT an Android SDK/API compatibility compile,
  Binder test, permission test, Android UI test or physical-device acceptance.
- `python tools/test_browser_capture.py`: **43/43** offline Chromium fixture checks
  passed, including collapsed/offscreen DOM, lazy creation, async busy state,
  virtualized repeated history, nested details, privacy, cancellation and limits.
  These fixtures are not live chatbot apps or mobile browser tests.
- Node syntax, JSON Unicode/control-character round trip, Python syntax and patch
  whitespace checks passed on the final source.

No Android SDK/full Gradle dependency checkout or phone was available in this
execution environment. No full Android build, lint, full project suite, emulator,
real ChatGPT/Claude app or live browser acceptance ran. No new APK was produced.

Next gate: use repository SDK/Gradle versions, run the full suite, lint and build;
fix actual diagnostics, then inspect real app trees and validate service/IME/shade
lifecycle, flag restoration, same-window pinning, nested/lazy panels, long histories,
selection, export and interruption on devices before merging/publishing.
