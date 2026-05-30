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

### Progress Update
* Blueprint Status: Phase 5
* Files Synchronized:
  - `layout_floating_reader.xml`: Added dynamic FrameLayout overlays for Library, Chapters, Settings. Added minimize icon, and new UI files.
  - `item_library_book.xml & item_chapter.xml`: List templates for Recyclerviews.
  - `FloatingReaderService.kt`: Added `LibraryAdapter` and `ChapterAdapter`, switching visibility states without closing overlay `view_container`. Added real DB linkage.
  - `MainActivity.kt`: Removed full-screen library Compose Activity. Routes `PICK_EPUB` strictly.
* Next Action: Test internal navigation flow (Library <-> Settings <-> Reader).
