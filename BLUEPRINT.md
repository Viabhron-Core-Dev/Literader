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

### Progress Update
* Blueprint Status: Phase 11
* Files Synchronized:
  - `layout_floating_reader.xml`: Overhauled Library Top Bar with Action Icons; overhauled Settings Top Bar to incorporate Revert button natively.
  - `item_library_footer.xml`: Excluded Tracker FAB.
  - `FloatingReaderService.kt`: Dislodged and reinserted listener arrays, merged global `TrackerDao` iteration syncing, wrapped gesture `onFling` configurations for swipe navigation, and streamlined logic flow scaling.
* Next Action: Monitor user verification of the new layout flow.


