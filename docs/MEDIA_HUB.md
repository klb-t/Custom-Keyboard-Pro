# Media hub

The media hub is a working browser over Android's Storage Access Framework. A
local folder, an SD-card folder, a cloud folder and an individually selected cloud
document use one source profile and one adapter. There are no hard-coded provider
names or fabricated cloud API integrations.

## What is implemented

- **Add folder** opens `ACTION_OPEN_DOCUMENT_TREE`. **Add files** opens
  `ACTION_OPEN_DOCUMENT` with multi-selection, including virtual documents. The
  second route also supports providers that cannot expose directory trees.
- Read grants are persisted from the result flags; write access is not requested.
  Android's provider picker handles available accounts and provider selection.
- Source profiles persist URI, alias, kind and enabled state. Source aliases and
  inclusion in the aggregate view are editable. Removing a source releases its
  exact read grant. No account credential is stored.
- The aggregate view loads the first directory level of each enabled folder and
  every selected file. Selecting a folder navigates inside its original tree grant.
- A MIME-pattern view profile drives filtering, hidden names, folder visibility and
  sorting. Eight built-in views in `media_view_profiles.json` are instances of the
  same model as edited views. All six browser options use the central expert
  settings registry, including provider timeout and listing limit.
- Search operates on loaded display names, MIME types and source labels, with all
  query terms required. The interface states that this is **this-level search**, not
  an indexed search through an entire cloud account. It does not recursively scan
  or silently download content.
- Per-source limits bound listing size. Truncation and provider-loading messages
  remain visible, and the user can refresh or change the limit.
- Open, share and copy-file actions carry read-only URI grants. The receiving app
  must support the MIME type or file pasting. A virtual document can be opened via
  its provider; raw-byte sharing/copying is disabled until it is exported there.
- Unknown size and modification times are preserved as unknown, not zero.
  Provider unavailability and missing grants are shown per source.

## Integration

`com.example.ui.settings.MediaHubScreen(modifier: Modifier = Modifier)` is the
settings route entrypoint. The screen creates its own repository. The sources and
view are stored as profile data; changing the alias or removing a hub entry never
changes the remote file.

No broad storage permission, accessibility access, direct cloud credentials, or
manifest provider declaration is needed. The app uses providers already present
on the device. Providers absent from the system picker are not integrated through
this adapter. Direct vendor OAuth adapters, remote full-text indexes, editing,
uploading, moving and deleting remote files are not implemented by this feature.

## Verification

`MediaProfilesTest` checks profile roundtrips, MIME/folder filtering, multi-term
search, unknown-size ordering, picker flags, URI grants and virtual-file action
restrictions. `MediaHubRepositoryTest` uses a fake content provider to exercise
persisted grants, query metadata, revoked access, tree boundaries and truncation.
Device checks still needed: a local folder with nested children, a
cloud provider that only exposes files, an offline provider, a revoked grant,
sharing to another app, and reopening the hub after a reboot.

Android references consulted 2026-09-27:

- [Access documents and other files](https://developer.android.com/training/data-storage/shared/documents-files)
- [DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider)

The platform's persisted grants can cease to work after a document is moved or
deleted, and Android restricts some directory selections. The UI offers selecting
the source again; it does not attempt to bypass those restrictions.
