# 📘 LiteReader Blueprint

## 1. Project Overview
**App Name:** LiteReader  
**Purpose:** A lightweight, highly optimized floating EPUB reader designed for low-end Android devices (2-3GB RAM).  
**Core Mechanic:** A resizable floating window that can fold into a minimal bubble (frozen state) when tapping outside. It reads cached plain-text chapters, loading at most 11 chapters in memory (current ± 5) to minimize RAM footprint.  

## 2. Key Architecture & Constraints
*   **Storage Access Framework (SAF):** No `READ_EXTERNAL_STORAGE` permission. EPUBs are selected via the system picker and imported into app-internal storage.
*   **Memory Management:** EPUBs are pre-parsed into plain text upon import. The reader only loads a minimal chapter sliding window (current ± 5 chapters). 
*   **Floating Window:** Requires `SYSTEM_ALERT_WINDOW`. Managed via a Foreground Service.
*   **Resizability & Folding:** The window can be resized manually. Tapping outside the window instantly collapses it into a persistent "bubble" icon.
*   **Offline/Standalone:** Exportable via GitHub Actions for a Debug APK. No cloud sync, no tracking.

## 3. Development Phases

### Phase 1: Setup & Library Foundation
- [x] Initialize Android project and Room Database for Library metadata.
- [x] Build Main Activity: Library UI (List of imported EPUBs, Recent Read list).
- [x] Implement SAF file picker to select and import `.epub` files.
- [x] Implement Delete and Filter functionality for the library.

### Phase 2: EPUB Parsing Engine
- [x] Implement ZIP extraction and `content.opf` parser.
- [x] Strip HTML to plain text.
- [x] Save processed books as plain text chapter-chunks in internal cache.

### Phase 3: Floating Window & Service Core
- [x] Create Foreground Service to hold the overlay.
- [x] Implement Floating View (Window Manager) with "Window" and "Folded Bubble" states.
- [x] Implement tap-to-fold logic (detecting outside touches) and drag-to-move for the bubble.
- [x] Implement resizability for the open window.

### Phase 4: Reader Implementation
- [x] Implement the reading View (TextView/ScrollView) inside the floating window.
- [x] Build the Chapter Management engine (loading current ± 5 chapters into memory dynamically).
- [x] Implement highly aggressive state saving (scroll position, current chapter) on scroll stop and fold.
- [x] Add basic UI chrome (progress bar, chapter nav) visible only on tap.

### Phase 5: Advanced Features
- [ ] Implement Library Search (by title/author).
- [ ] Implement In-EPUB text search.
- [ ] Add Settings (Font size: S/M/L, Theme: Dark/Light, Chapter List, Bookmarks).
- [ ] Final memory profiling and optimizations.
