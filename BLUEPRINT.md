# LiteReader Blueprint

## Overview
A lightweight, offline-first minimal EPUB reader designed as a floating overlay for Android, allowing multi-tasking and quick reading.

## Phase 1: Core Setup
- [x] Basic project layout.
- [x] Room Database configured for EPUB storage.

## Phase 2: Feature Matrix (Implemented)
- **Library Module**:
  - [x] Local storage EPUB import (Multi-select via intents).
  - [x] Fallback text parser (unzips, html-strip, filters to `.txt`).
  - [x] Minimal Tabs implementation (Recent).
- **Floating overlay Service**:
  - [x] Termux/MoonReader hybrid UI.
  - [x] Tap outside to fold to Book Icon.
  - [x] Top bar drag to move. Cross to exit overlay.
  - [x] Auto-scroll engine (Smooth pixel interval scroll).
  - [x] Toolbar uses icons (Library, Settings, Search, Scroll, Navigation).
  - [x] Crash-proof persistent state (tracking chapter index and scroll Y offset precisely).
- **Visual Design**:
  - [x] Custom Vector Book Icon (Purple `V` over minimalist dark Grey).
  - [x] Dark overlay aesthetic.

## Active State
System is fully rebuilt from scratch post-workspace wipe. Ready for deployment.

### Phase 6: Headless Full Book Search
- [x] Integrate 'Full Book Search' button into Floating Reader search overlay.
- [x] Create a headless `searchJob` tracking worker natively iterating through sequential read logic.
- [x] Stream dynamically mapped text snippets (+Character Offsets) to a floating `overlay_search_results` FrameLayout.
- [x] Implement robust one-tap context jumps (`loadAndJumpToOffset`) without taxing memory overhead limits.

### Phase 7: UX/Navigation Refinements
- [x] Pull-down gesture to go to previous chapter jumps to bottom of the content.
- [x] Folded icon retains original X/Y drag position upon fold (prevent shifting to expanded corners).

### Phase 8: File Explorer Tab (Scoped Directory)
- [x] In the Floating Service's Settings, add an optional Switch for "Scoped Directory (File Explorer)".
- [x] Enable users to grant `ACTION_OPEN_DOCUMENT_TREE` permissions from `MainActivity` via a "Select Directory" button. Fix intent routing bypass.
- [x] When enabled, dynamically change the "Imported" Library tab to "File Explorer" which recursively loads visible EPUB/TXT files right from external shared folders using `DocumentFile`.
- [x] Hook tap-to-read from File Explorer directly to the `LibraryRepository.importBook()` pathway (populating the DB/Recent cache).

### Phase 9: UI Visual Styling
- [x] Condensed Navigation Toolbar (shrunk sizing and reduced padding gaps).
- [x] Folded Bubble Icon styled as purple 'v' icon over a grey circle with 70% transparency (`alpha=0.3`) for underlying readability.

### Phase 10: Smooth Scrolling FABs (Library)
- [x] Extracted Library FABs (Continue, Tracker, Add Book) from the fixed `layout_gravity="bottom|end"` view group overlay.
- [x] Moved them into a new `item_library_footer.xml` layout and created a `FooterAdapter`.
- [x] Employed `ConcatAdapter` to concatenate the file lists (`FileAdapter` / `LibraryAdapter`) and the Action Buttons (`FooterAdapter`). This perfectly embeds them at the bottom of the scrollable list without disrupting view recycling, keeping the layout completely smooth and lightweight when the floating window is shrunk.

### Phase 11: Top Bar Triggers & Library Overhaul
- [x] Moved Settings and Tracker triggers directly into the Library Top Bar.
- [x] Removed Tracker action from the Library Footer FABs array.
- [x] Added Back Button navigation logic natively inside the Settings top bar heading.
- [x] Resolved "Resume loading" lag by aligning `fab_continue` logic structurally matching Recent item opens (explicitly triggering `loadBook` irrespective of overlay nullification).
- [x] Implemented swipe-to-switch across Library tabs using `GestureDetector` `onFling`.
- [x] Deployed automated `TrackerDao` mapping, synchronizing every `EpubBook` automatically so any book opened structurally cascades into `TrackerBook` registries. 
- [x] Stabilized File Explorer tab text-color states enforcing color resolution explicitly before scope checking.

### Phase 12: FAB Positioning (Library)
- [x] Restored correct vertical stacking constraint and alignment (`gravity="end"`) to the trailing Action FABs within the generic `item_library_footer.xml` adapter layout. 
- [x] Aligned spacing/margins to mirror the original UI overlay positioning exactly, anchoring them on the bottom right of the scrolling context.

### Phase 13: Floating Action Buttons Revert & Robust Storage
- [x] Restored the Action FABs (`fab_continue` and `fab_add_book`) exactly to the main `layout_floating_reader.xml` overlay layout, bound to a bottom right floating position so they remain fixed regardless of scroll or empty lists.
- [x] Eliminated the `ConcatAdapter` and `FooterAdapter` list dependencies.
- [x] Rebuilt File Explorer list synchronization to execute asynchronously and traverse directories robustly allowing nested EPUB files exploration under heavily nested directories like `Books/Author`.

### Phase 14: Lightweight File Explorer & Cover Layouts
- [x] Converted the recursive file explorer into a localized, lightweight directory traversal system using `explorerStack`, adhering to non-recursive constraints.
- [x] Designed `item_file_explorer.xml` specifically for parsing non-metadata directory states (Name, precise File Size formatting, native Folder/File icons).
- [x] Restructured `item_library_book.xml` assigning a native 2:3 vertical Cover aspect ratio for the Recent tab to prepare metadata layouts.
- [x] Integrated sorting interfaces (Name/Date, Ascending/Descending) into a native `bar_explorer_tools` top bar visible only in the File Explorer tab.
- [x] Wired a long-press Context Menu (`showExplorerContextMenu`) for Properties, Rename, and Delete file-level operations.

### Phase 15: Application Logging & Notifications Resiliency
- [x] Fixed "Suppressing toast by user request" OS-level warnings occurring when background apps lack active Foreground notification permissions on Android 13+.
- [x] Replaced system `Toast` dependencies entirely with a custom floating overlay `tv_custom_toast` view directly anchored into the `layout_floating_reader.xml` interface.
- [x] Programmed Coroutine `toastJob` delays (`showToast`) to natively replicate SnackBar appearance and duration directly inside the overlay engine.

### Phase 16: Deep Hierarchy Full File Permissions
- [x] Ripped out restrictive Storage Access Framework (SAF) `DocumentFile` limitations blocking recursive subdirectory traversal.
- [x] Injected native Android 13+ `MANAGE_EXTERNAL_STORAGE` permission requirements allowing pure `java.io.File` absolute path hierarchy scans natively avoiding memory leaks from large cursors.
- [x] Connected File Explorer direct iteration logic (`android.os.Environment.getExternalStorageDirectory`) utilizing OS native iterators without needing arbitrary permission tree picks.

### Phase 17: Fallback App Settings Direction
- [x] Rerouted permission intent from explicit `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` directly to `ACTION_APPLICATION_DETAILS_SETTINGS` avoiding system settings page crashes on custom restricted vendor firmware versions.
- [x] Fixed string literal escaping typo (`\$packageName`).

### Phase 18: Librera Reader Permission Parity
- [x] Restored `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for Android 11+ as the primary explicit trigger for full structure directory access.
- [x] Implemented standard Android `try/catch` fallbacks to `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` list pickers if the direct App Details path throws `ActivityNotFoundException` (common in restricted Vendor UI layers), matching Librera's permission matrix.

### Phase 19: EPUB Thumbnails & Rich File Formatting
- [x] Reworked File Explorer `item_file_explorer.xml` resolving text layout clamping — migrating to `maxLines=2` with end ellipsizing to cleanly display longer eBook names.
- [x] Injected rich sub-text metadata into File Explorer adapters pairing file extensions and precise file sizes side-by-side (`EPUB • 2.50 MB`).
- [x] Integrated lightweight IO off-thread ZIP extraction within the `FileAdapter` capturing localized `cover.jpg` from within the EPUB structure and painting bitmaps direct into the explorer list.

### Progress Update
* Blueprint Status: Phase 19
* Files Synchronized:
  - `item_file_explorer.xml`: Upgraded ImageView dimensions handling varied ratio book thumbnails.
  - `FloatingReaderService.kt`: Built `loadEpubCover` asynchronous extraction methods scanning ZIP directory arrays to serve responsive UI image caching via `setImageBitmap`.
* Next Action: Await File explorer validations.


