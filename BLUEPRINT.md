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
- [x] Enable users to grant `ACTION_OPEN_DOCUMENT_TREE` permissions from `MainActivity` via a "Select Directory" button.
- [x] When enabled, dynamically change the "Imported" Library tab to "File Explorer" which recursively loads visible EPUB/TXT files right from external shared folders using `DocumentFile`.
- [x] Hook tap-to-read from File Explorer directly to the `LibraryRepository.importBook()` pathway (populating the DB/Recent cache).

### Progress Update
* Blueprint Status: Phase 8
* Files Synchronized:
  - `MainActivity.kt`: Bound `ActivityResultContracts.OpenDocumentTree()` to handle external folder access and persisting URI permissions.
  - `layout_floating_reader.xml`: Added `.switch_scoped_dir` toggle and `.btn_pick_dir` button in the Settings overlay.
  - `build.gradle.kts`: Added `androidx.documentfile:documentfile` library.
  - `FloatingReaderService.kt`: Intercepted Library tab rendering (`loadLibraryBooks`), added conditional `DocumentFile` traversal, and deployed `FileAdapter` specifically tailored for parsing and rapid importing.
* Next Action: Test external EPUB folder traversal.


