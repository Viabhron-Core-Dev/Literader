# Receipts Log 001

## Entry 1
* Timestamp: 2026-08-31T14:14:15-07:00
* Request: Fix backup restore file picker not opening, enforce 10 books limit for Recent tab (cleaning up extracted chapter files beyond 10), and make folded/unfolded states lean and strictly on-demand (stopping auto-scroll, TTS, search jobs, and detaching overlay caches upon fold).
* Files Touched:
  - `/app/src/main/java/com/example/MainActivity.kt`
  - `/app/src/main/java/com/example/data/LibraryRepository.kt`
  - `/app/src/main/java/com/example/service/FloatingReaderService.kt`
  - `/BLUEPRINT.md`
  - `/receipts/RECEIPTS_001.md`
* What Was Done:
  - Fixed `MainActivity.kt` `onCreate` intent bypass condition so `PICK_BACKUP` properly opens the system file picker instead of launching into the reader overlay.
  - Updated `backupPicker` MIME type filter to `*/*` to ensure `.zip` backup archives are universally selectable across all Android file managers.
  - Added IO-safe recursive file cleanup in `LibraryRepository.deleteBook` and exposed `enforceLimit(max = 10)` to purge stored books and their unzipped chapter directories beyond the 10 most recent.
  - Bound `enforceLimit(10)` into `FloatingReaderService.loadBook()` to ensure opening a book automatically cleans up older books exceeding the limit.
  - Enhanced `FloatingReaderService.setFolded(true)` to immediately save book progress, stop and unregister auto-scroll handlers, shutdown and release TextToSpeech engines, cancel running background search coroutines, hide overlays, and detach RecyclerView adapter references.
* Verification: Local build only (`compile_applet` passed successfully).
* Deviation: None.
* Known Issues / Follow-up: None.

## Entry 2
* Timestamp: 2026-08-31T14:21:10-07:00
* Request: In ereader, implement tap to pause/play auto-scroll inside reader. Add auto-scroll speed adjustment in Settings. Only save extracted text of current epub on disk instead of saving text for multiple recent books. Make recent books list capacity 20.
* Files Touched:
  - `/app/src/main/res/layout/overlay_settings.xml`
  - `/app/src/main/java/com/example/data/LibraryRepository.kt`
  - `/app/src/main/java/com/example/service/FloatingReaderService.kt`
  - `/BLUEPRINT.md`
  - `/receipts/RECEIPTS_001.md`
* What Was Done:
  - Added Auto-Scroll Speed control (SeekBar range 1-20 and dynamic text label) in `overlay_settings.xml`.
  - Configured `SeekBar` listeners in `FloatingReaderService.inflateSettingsIfNeeded` to update `autoScrollSpeed` and persist to `SharedPreferences` under `"auto_scroll_speed"`.
  - Updated `scrollRunnable` in `FloatingReaderService.kt` to dynamically adjust step and interval timing based on `autoScrollSpeed`.
  - Added tap-to-pause and tap-to-resume gesture handling for auto-scroll sessions inside the reader touch listener.
  - Implemented `retainOnlyActiveBookChapters(activeBookId)` in `LibraryRepository.kt` and hooked it into `loadBook()` to delete all unzipped `book_<id>` chapter directories on disk except for the currently active book.
  - Added on-demand chapter extraction check in `loadBook()` so switching back to a previous book unzips its chapters seamlessly without requiring full re-import.
  - Updated recent books library capacity from 10 to 20 across `LibraryRepository` and `FloatingReaderService`.
* Verification: Local build only (`compile_applet` passed successfully).
* Deviation: None.
* Known Issues / Follow-up: None.

## Entry 3
* Timestamp: 2026-08-31T15:57:30-07:00
* Request: Implement remaining items from technical review: auto-scroll continuous chapter auto-advance, TTS and auto-scroll mutual exclusion, re-extraction visual loading indicator, and graceful missing source file feedback.
* Files Touched:
  - `/app/src/main/java/com/example/service/FloatingReaderService.kt`
  - `/BLUEPRINT.md`
  - `/receipts/RECEIPTS_001.md`
* What Was Done:
  - Enhanced `scrollRunnable` in `FloatingReaderService.kt` to detect when the user reaches the bottom of the current chapter (`scrollY >= maxScroll - 6`), automatically triggering `navigateChapter(1)`, scrolling to top, and continuing auto-scrolling with toast feedback.
  - Added mutual exclusion between Text-to-Speech (TTS) and Auto-Scroll: starting TTS stops active auto-scrolling; starting or resuming auto-scroll immediately halts TTS audio playback.
  - Added UI loading spinner feedback during on-demand chapter re-extraction when switching between recent books in `loadChapterText`.
  - Handled missing/deleted source EPUB/TXT files with a descriptive placeholder and user notification instead of silent failure.
* Verification: Local build only (`compile_applet` passed successfully).
* Deviation: None.
* Known Issues / Follow-up: None.

