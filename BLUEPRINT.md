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

### Progress Update
* Blueprint Status: Phase 6
* Files Synchronized:
  - `layout_floating_reader.xml`: Replaced chapter search with toggles for both Search Chapter and Search Full Book. Added search results FrameLayout overlay.
  - `FloatingReaderService.kt`: Added `performFullBookSearch` with Dispatchers.IO coroutine to sequentially parse EPUB splits; injected `loadAndJumpToOffset`.
* Next Action: Testing stability and visual UI offsets when jumping.
