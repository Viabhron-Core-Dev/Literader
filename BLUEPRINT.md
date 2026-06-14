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

### Phase 20: App Icon Update
- [x] Processed user-provided custom book icon for the application.
- [x] Scaled and structured the icon into adaptive layouts by dropping it as the primary `ic_launcher_foreground` drawing and matching bounding colors in `ic_launcher_background` (`#1A2045`).
- [x] **Hotfix**: Updated `MainActivity.kt` Jetpack Compose `painterResource` to reference `ic_custom_book_icon` directly, since Compose cannot load `<layer-list>` structured XMLs natively on some platforms causing `IllegalArgumentException`.

### Phase 21: UI Icon Refinement
- [x] Swapped `ic_menu_revert` with standard Android styled `ic_arrow_back` vector geometry for library overlay navigation back paths.

### Phase 22: Window Layout Refinements
- [x] Transformed `btn_minimize`, `btn_exit`, and `resize_handle` into explicit interaction blocks with solid semantic colors (green, red, grey) mimicking classic window title controls.
- [x] Skinned the bottom resize strip thinner (16dp heights instead of 24dp).
- [x] Duplicated layout folding action to the bottom strip opposite the resize handle, granting dual-edge minimizing mirroring resizing patterns without needing to crawl back up.

### Phase 23: Icon Reload
- [x] Processed new imported user icon replacement and populated directly over `ic_custom_book_icon.png` in resources for app launcher and splash views.

### Phase 24: Float Layout Adjustments & App Optimization
- [x] Softened the sharp borders of the floating overlay window by moving the background style to a `bg_floating_window.xml` rounded 14dp shape drawable.
- [x] Minified the application payload by converting the heavy `ic_custom_book_icon.png` resource to a compressed `.webp` format utilizing server-side command-line converters, dramatically dropping APK weight.

### Phase 25: Reader Strip Revamp & VianReader Migration
- [x] Renamed core metadata strings and framework IDs dropping LiteReader for `VianReader`.
- [x] Built a persistent public cache hook inside `Internal Storage/Books/VianReader/.covers/` to save extracted EPUB covers independent from internal sandboxing memory.
- [x] Stripped down the top drag bar to an explicit 2-line title wrap, scrubbing `.epub` file extensions from display logic in `FloatingReaderService.kr`.
- [x] Restructured bottom right window corner pooling dragging, folding, and closing triggers tightly against a shared linear layout with red, green, grey semantics.
- [x] Integrated a discrete search-close cross vector icon alongside chapter/full search actions.

### Phase 26: Complete Navigation & System Redesign
- [x] Swapped all generic Android UI icons out for proper Custom Vector Drawables representing Resize, Minimize, Library, Chapters, Settings, Auto-Scroll, and Bookmarks.
- [x] Adjusted `FloatingReaderService` `tvContent` text fields to strictly `textIsSelectable=true` permitting standard Android Text selection hooks behind the floating layout window context.
- [x] Reshaped the `layout_floating_reader` structure converting the fat bottom SeekBar into a thin 4dp UI line spanning the horizon. Set to invisible handles `@0` bounding the actual text view.
- [x] Corrected back-button behavior globally across Settings and the new Bookmarks view natively hiding the view instead of artificially navigating backward.
- [x] Overhauled UI saving using robust `SharedPreferences` interceptors storing `currentLibraryTab`, `explorerSortAscending`, and Explorer nested directory navigation `File` instances locally across boot.
- [x] Bound a mocked 'Save Bookmark' system toast to intercept Bookmarks array inserts.
- [x] Polished Back Navigation in Settings and Bookmarks: Removed hard-clearing of underlying views, allowing seamless return to exact previous state (Library or Book).

### Phase 27: Text Selection Fixes & Floating Controls
- [x] Converted the rigid bottom layout into a semi-transparent floating control cluster (Close, Minimize, Resize) situated at the bottom-right corner.
- [x] Stretched the main `ScrollView` completely to the bottom edge, providing an unobstructed reading view.
- [x] Restored `ScrollView` + `TextView` combined tap listeners. A short tap now correctly toggles the toolbar visibility even with Android's selection handler enabled.
- [x] Integrated a native custom floating Context Menu containing [Copy], [Share], and [Clear]. This appears specifically when `tvContent.hasSelection()` is true, bypassing the Android Overlay restriction that prevents `ActionMode` toolbars from spawning.

### Phase 28: Third-Party Progress Syncs
- [x] Spliced a quick "Moon+ Backup Import" Action directly into the Book Tracker's top bar `Icons.CloudDownload`.
- [x] Re-routed the android SAF generic file picker to intercept `*/*` payload triggers and invoke a fake "Extract & Restitch" engine.
- [x] Mocked the rough landing by automatically mapping discovered `.po` zip signatures backward into `AppDatabase`'s `TrackerBook` entries, intelligently updating `readChapters` and `lastUpdatedTimestamp`.

### Phase 29: Core Stability & Vulnerability Fixes
- [x] Secured MediaSession lifecycles inside `FloatingReaderService.kt` to avoid phantom instances continuing after garbage collection.
- [x] Plugged Notification pending intents by appending explicit `PendingIntent` declarations, fixing UI un-interactivity warnings for foreground tasks.
- [x] Created `BootReceiver` hook configured to restore existing Reader sessions seamlessly if a reboot interrupted an ongoing task and the reader was active. 

### Phase 30: Context-Aware Text-To-Speech (TTS)
- [x] Reworked the TTS `speak()` logic within `toggleTts()`.
- [x] Tied the parsing pointer to `scrollView.scrollY`, automatically jumping the audio queue to whatever line is actively visible inside the window bounds instead of beginning from the start of the chapter.

### Phase 31: App Icon Fix
- [x] Transferred custom uploaded app icons (`app_icon.png` folder mappings) to the system standard res structure (`mipmap` hdpi -> xxxhdpi).
- [x] Fixed `.png.png` double extensions.
- [x] Standardized `adaptive_fore` and `adaptive_back` layers in `anydpi-v26` and mapped XML correctly to standard `app_icon` paths.

### Phase 32: Diagnostics & Lazy Initialisation
- [x] Repaired aggressive startup latency in `FloatingReaderService` by detaching Text-To-Speech from the service `onCreate` lifecycle constraint.
- [x] Made `tts` instance completely lazy/on-demand so memory allocations are deferred specifically to explicitly invoked auditory actions via `executeTtsToggle()`.

### Phase 33: Floating Window Layout Refinement
- [x] Streamlined the Library view top header bar, reducing the fat padding gaps and shaving the text down directly to simply "Library".
- [x] Extracted the persistent bottom window control modules (Close, Minimize, Resize) out of the deep frame layout constraint structure.
- [x] Repositioned the window controls to strictly float atop the absolute highest UI layer inside the wrapper. Providing a seamless interface that guarantees the controls are ever-present across Library, Search, Bookmarks, and Settings overlays independently.
- [x] Inserted a sleek red-tinted cross vector icon into the `item_bookmark` adapter cards directly mapping cleanly to the real-time delete commands across the Room instance.

### Phase 34: Keystore Security Remediation
- [x] Added `debug.keystore.base64`, `*.keystore`, `*.jks`, `*.p12` to `.gitignore`.
- [ ] Remove `debug.keystore.base64` from git tracking via `git rm --cached debug.keystore.base64` (Manual step required outside AI Studio constraints).
- [x] Verified `build.gradle.kts` signing configuration - defaults are secure.
- [ ] Purge git history to remove compromised keystore (Manual step required: user must use BFG or git filter-repo outside AI Studio).

### Progress Update
* Blueprint Status: Phase 34
* Files Synchronized:
  - `.gitignore`: Updated with strict keystore exclusion rules.
* Next Action: Await user manual Git history purge.



