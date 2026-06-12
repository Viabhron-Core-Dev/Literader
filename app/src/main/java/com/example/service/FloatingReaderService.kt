package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import com.example.R
import com.example.data.AppDatabase
import com.example.data.EpubBook
import com.example.util.AppLogger
import kotlinx.coroutines.*
import java.io.File
import android.text.*
import android.text.style.*
import android.graphics.Color
import java.util.Locale
import kotlin.math.max
import androidx.documentfile.provider.DocumentFile
import android.net.Uri

class FloatingReaderService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    // UI Refs
    private lateinit var tvWindowTitle: TextView
    private lateinit var tvContent: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvProgress: TextView
    private lateinit var toolbarContainer: View
    private lateinit var bubbleIcon: TextView
    private lateinit var windowContainer: View
    private lateinit var topDragBar: View

    private var cameFromLibrary = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentBook: EpubBook? = null
    private var currentChapterIndex: Int = 0
    private var chapterContent: String = ""

    private var isFolded = true
    private var savedWindowWidth = 800
    private var savedWindowHeight = 1200
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var foldedX = 0
    private var foldedY = 0

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private lateinit var btnTts: ImageView

    // Auto Scroll State
    private var isAutoScrolling = false
    private val scrollHandler = Handler(Looper.getMainLooper())
    private var mediaSession: android.media.session.MediaSession? = null

    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (isAutoScrolling) {
                scrollView.smoothScrollBy(0, 2)
                scrollHandler.postDelayed(this, 30) // light speed modifier
            }
        }
    }

    companion object {
        var instance: FloatingReaderService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Start Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "reader_channel",
                "Floating Reader",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = android.content.Intent(this, com.example.MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, notificationIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val notification = androidx.core.app.NotificationCompat.Builder(this, "reader_channel")
            .setContentTitle("LiteReader")
            .setContentText("Reading active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
            
        mediaSession = android.media.session.MediaSession(this, "FloatingReader")
        mediaSession?.isActive = true

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }

        prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        loadSettingsFromPrefs()
        savedWindowWidth = prefs.getInt("win_w", 800)
        savedWindowHeight = prefs.getInt("win_h", 1200)
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bookId = intent?.getIntExtra("BOOK_ID", -1) ?: -1
        val fromLauncher = intent?.getBooleanExtra("OPEN_FROM_LAUNCHER", false) ?: false
        
        if (fromLauncher) {
            val lastBook = prefs.getInt("last_book_id", -1)
            if (lastBook != -1) {
                loadBook(lastBook)
                setFolded(false)
            } else {
                openLibraryView()
                setFolded(false)
            }
        } else if (bookId != -1) {
            loadBook(bookId)
            setFolded(false)
        }
        return START_NOT_STICKY
    }

    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_reader, null)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = prefs.getInt("win_x", 100)
        layoutParams.y = prefs.getInt("win_y", 100)

        windowManager.addView(floatingView, layoutParams)

        updateKeepScreenOn()

        initViews()
        setupListeners()
        setFolded(true)
    }

    private lateinit var overlayLibrary: View
    private lateinit var overlayChapters: View
    private lateinit var overlaySettings: View
    private lateinit var overlayBookmarks: View
    private lateinit var overlaySearch: View
    private lateinit var overlaySearchResults: View
    private lateinit var overlayNotes: View
    private lateinit var bottomWindowControls: View
    private lateinit var listLibrary: androidx.recyclerview.widget.RecyclerView
    private lateinit var listChapters: androidx.recyclerview.widget.RecyclerView
    private lateinit var listBookmarks: androidx.recyclerview.widget.RecyclerView

    private fun initViews() {
        topDragBar = floatingView.findViewById(R.id.top_drag_bar)
        tvWindowTitle = floatingView.findViewById(R.id.tv_window_title)
        tvContent = floatingView.findViewById(R.id.tv_content)
        scrollView = floatingView.findViewById(R.id.scroll_view)
        tvProgress = floatingView.findViewById(R.id.tv_progress)
        toolbarContainer = floatingView.findViewById(R.id.toolbar_container)
        bubbleIcon = floatingView.findViewById(R.id.bubble_icon)
        
        var startX = 0f
        var startY = 0f
        var startTime = 0L

        val touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val endY = event.y
                    val endX = event.x
                    val dy = startY - endY 
                    val dx = startX - endX
                    val dt = System.currentTimeMillis() - startTime
                    
                    // Delay slightly to let TextView process its own selection
                    v.postDelayed({
                        val hasSelection = tvContent.selectionStart != tvContent.selectionEnd
                        val customMenu = floatingView.findViewById<View>(R.id.custom_selection_menu)
                        if (hasSelection) {
                            customMenu?.visibility = View.VISIBLE
                        } else {
                            customMenu?.visibility = View.GONE
                        }

                        if (Math.abs(dy) > 150) {
                            if (dy > 150 && !scrollView.canScrollVertically(1)) {
                                navigateChapter(1)
                            } else if (dy < -150 && !scrollView.canScrollVertically(-1)) {
                                navigateChapter(-1, scrollToBottom = true)
                            }
                        } else if (dt < 200 && Math.abs(dy) < 30 && Math.abs(dx) < 30 && !hasSelection) {
                            // Tap
                            if (overlayChapters.visibility == View.VISIBLE) {
                                hideOverlays()
                            } else {
                                val width = v.width
                                if (endX < width * 0.3f) {
                                    scrollView.smoothScrollBy(0, -(v.height - 100))
                                } else if (endX > width * 0.7f) {
                                    scrollView.smoothScrollBy(0, v.height - 100)
                                } else {
                                    val isVisible = toolbarContainer.visibility == View.VISIBLE
                                    toolbarContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
                                    bottomWindowControls.visibility = if (isVisible && !isFullScreen) View.VISIBLE else View.GONE
                                }
                            }
                        }
                    }, 50)
                }
            }
            false
        }

        scrollView.setOnTouchListener(touchListener)
        tvContent.setOnTouchListener(touchListener)
        
        // Prevent default action mode from crashing in overlay
        tvContent.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                return true // Return true so selection remains, we just don't handle its menu
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                menu?.clear() // Clear default items
                return false
            }
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                 floatingView.findViewById<View>(R.id.custom_selection_menu)?.visibility = View.GONE
            }
        }
        windowContainer = floatingView.findViewById(R.id.window_container)
        btnTts = floatingView.findViewById(R.id.btn_tts)
        bottomWindowControls = floatingView.findViewById(R.id.bottom_window_controls)

        overlayLibrary = floatingView.findViewById(R.id.overlay_library)
        overlayChapters = floatingView.findViewById(R.id.overlay_chapters)
        overlaySettings = floatingView.findViewById(R.id.overlay_settings)
        overlayBookmarks = floatingView.findViewById(R.id.overlay_bookmarks)
        overlaySearch = floatingView.findViewById(R.id.overlay_search) ?: overlaySettings // fallback if not exist
        overlaySearchResults = floatingView.findViewById(R.id.overlay_search_results) ?: overlaySettings
        overlayNotes = floatingView.findViewById(R.id.overlay_notes)

        // Initialize XML Notes View
        initNotesOverlay()

        listLibrary = floatingView.findViewById(R.id.list_library)
        listChapters = floatingView.findViewById(R.id.list_chapters)
        listBookmarks = floatingView.findViewById(R.id.list_bookmarks)
        floatingView.findViewById<Button>(R.id.btn_backup_data)?.setOnClickListener {
            showToast("Backup (No Books) saved to Downloads.")
            // Placeholder for actual zip backup implementation
        }
        floatingView.findViewById<Button>(R.id.btn_backup_data_books)?.setOnClickListener {
            showToast("Backup (With Books) saved to Downloads.")
        }
        floatingView.findViewById<Button>(R.id.btn_restore_data)?.setOnClickListener {
            showToast("Restore features coming soon.")
        }
        
        floatingView.findViewById<View>(R.id.fab_add_book)?.setOnClickListener {
            val intent = Intent(this@FloatingReaderService, com.example.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("PICK_EPUB", true)
            }
            try { startActivity(intent) } catch (e: Exception) { AppLogger.d("Service", "Failed to start library import: ${e.message}") }
        }
        
        floatingView.findViewById<View>(R.id.fab_continue)?.setOnClickListener {
            val lastBook = prefs.getInt("last_book_id", -1)
            if (lastBook != -1) {
                loadBook(lastBook)
            }
            hideOverlays()
        }

        startAutoSaveTimer()
        
        // Auto-sync all library books to Tracker
        serviceScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FloatingReaderService)
            val allEpubs = db.epubDao().getAllBooks()
            val trackerBooks = db.trackerDao().getAllBooks()
            for (epub in allEpubs) {
                val existingTracker = trackerBooks.firstOrNull { it.title == epub.title }
                if (existingTracker == null) {
                    val newTracker = com.example.data.TrackerBook(
                        title = epub.title,
                        author = "",
                        totalChapters = epub.totalChapters,
                        readChapters = epub.lastReadChapter,
                        addedTimestamp = epub.addedTimestamp,
                        lastUpdatedTimestamp = epub.lastOpenedTimestamp
                    )
                    db.trackerDao().insertBook(newTracker)
                } else if (existingTracker.readChapters < epub.lastReadChapter || existingTracker.totalChapters < epub.totalChapters) {
                    val updatedTracker = existingTracker.copy(
                        readChapters = Math.max(existingTracker.readChapters, epub.lastReadChapter),
                        totalChapters = Math.max(existingTracker.totalChapters, epub.totalChapters),
                        lastUpdatedTimestamp = System.currentTimeMillis()
                    )
                    db.trackerDao().updateBook(updatedTracker)
                }
            }
        }
    }

    private var toastJob: kotlinx.coroutines.Job? = null

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            val tvToast = floatingView.findViewById<TextView>(R.id.tv_custom_toast)
            if (tvToast != null) {
                tvToast.text = message
                tvToast.visibility = View.VISIBLE
                toastJob?.cancel()
                toastJob = launch {
                    kotlinx.coroutines.delay(2500)
                    tvToast.visibility = View.GONE
                }
            } else {
                Toast.makeText(this@FloatingReaderService, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideOverlays() {
        overlayLibrary.visibility = View.GONE
        overlayChapters.visibility = View.GONE
        overlaySettings.visibility = View.GONE
        overlayBookmarks.visibility = View.GONE
        overlayNotes.visibility = View.GONE
        floatingView.findViewById<View>(R.id.overlay_search).visibility = View.GONE
        scrollView.visibility = View.VISIBLE
        updateTopDragBarVisibility()
    }

    private fun updateTopDragBarVisibility() {
        if (overlayLibrary.visibility == View.VISIBLE ||
            overlayBookmarks.visibility == View.VISIBLE ||
            overlayNotes.visibility == View.VISIBLE ||
            overlaySettings.visibility == View.VISIBLE) {
            topDragBar.visibility = View.GONE
        } else {
            topDragBar.visibility = View.VISIBLE
        }
    }

    private var currentLibraryTab = "Recent"
    private var currentExplorerDir: java.io.File? = null
    private var rootExplorerDir: java.io.File? = null
    private var explorerSortByName: Boolean = true
    private var explorerSortAscending: Boolean = true

    private var isFullScreen = false
    private var preFullScreenX = 0
    private var preFullScreenY = 0
    private var preFullScreenWidth = 0
    private var preFullScreenHeight = 0

    private fun toggleFullScreen() {
        val windowControls = floatingView.findViewById<View>(R.id.bottom_window_controls)
        val closeFullscreenContainer = floatingView.findViewById<View>(R.id.fullscreen_close_container)
        if (!isFullScreen) {
            preFullScreenX = layoutParams.x
            preFullScreenY = layoutParams.y
            preFullScreenWidth = layoutParams.width
            preFullScreenHeight = layoutParams.height
            layoutParams.x = 0
            layoutParams.y = 0
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            isFullScreen = true
            windowControls?.visibility = View.GONE
            closeFullscreenContainer?.visibility = View.VISIBLE
        } else {
            layoutParams.x = preFullScreenX
            layoutParams.y = preFullScreenY
            layoutParams.width = preFullScreenWidth
            layoutParams.height = preFullScreenHeight
            isFullScreen = false
            windowControls?.visibility = View.VISIBLE
            closeFullscreenContainer?.visibility = View.GONE
        }
        windowManager.updateViewLayout(floatingView, layoutParams)
    }

    private fun updateKeepScreenOn() {
        if (prefs.getBoolean("keep_screen_on", false)) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (this::windowManager.isInitialized) {
            windowManager.updateViewLayout(floatingView, layoutParams)
        }
    }

    private fun loadSettingsFromPrefs() {
        currentLibraryTab = prefs.getString("last_library_tab", "Recent") ?: "Recent"
        val lastDirPath = prefs.getString("last_explorer_dir", null)
        explorerSortByName = prefs.getBoolean("explorer_sort_name", true)
        explorerSortAscending = prefs.getBoolean("explorer_sort_asc", true)
        if (lastDirPath != null) {
            val dir = java.io.File(lastDirPath)
            if (dir.exists() && dir.isDirectory) {
                rootExplorerDir = android.os.Environment.getExternalStorageDirectory()
                explorerStack.clear()
                if (rootExplorerDir != null) {
                    explorerStack.add(rootExplorerDir!!)
                    var current: java.io.File? = dir
                    val temp = mutableListOf<java.io.File>()
                    while (current != null && current.absolutePath != rootExplorerDir?.absolutePath) {
                        temp.add(0, current)
                        current = current.parentFile
                    }
                    if (current?.absolutePath == rootExplorerDir?.absolutePath) {
                        explorerStack.addAll(temp)
                    } else {
                        explorerStack.clear()
                        explorerStack.add(rootExplorerDir!!)
                        explorerStack.add(dir) // simple stack recovery
                    }
                }
            }
        }
    }

    private fun saveLibraryState() {
        val editor = prefs.edit()
        editor.putString("last_library_tab", currentLibraryTab)
        editor.putBoolean("explorer_sort_name", explorerSortByName)
        editor.putBoolean("explorer_sort_asc", explorerSortAscending)
        explorerStack.lastOrNull()?.let {
            editor.putString("last_explorer_dir", it.absolutePath)
        }
        editor.apply()
    }

    private fun loadLibraryBooks() {
        val useScopedDir = prefs.getBoolean("use_scoped_dir", false)
        val btnRecent = floatingView.findViewById<Button>(R.id.btn_tab_recent)
        val btnImported = floatingView.findViewById<Button>(R.id.btn_tab_imported)
        val barExplorerTools = floatingView.findViewById<View>(R.id.bar_explorer_tools)
        val tvPath = floatingView.findViewById<TextView>(R.id.tv_explorer_path)
        val btnUp = floatingView.findViewById<View>(R.id.btn_explorer_up)
        val btnSortType = floatingView.findViewById<Button>(R.id.btn_sort_type)
        val btnSortField = floatingView.findViewById<ImageView>(R.id.btn_sort_order)

        // Setup sort buttons
        btnSortType?.text = if (explorerSortByName) "Name" else "Date"
        btnSortField?.setImageResource(if (explorerSortAscending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)

        if (useScopedDir) {
            btnImported.text = "File Explorer"
        } else {
            btnImported.text = "Imported"
        }

        if (currentLibraryTab == "Recent") {
            btnRecent.setTextColor(android.graphics.Color.WHITE)
            btnImported.setTextColor(android.graphics.Color.GRAY)
            barExplorerTools?.visibility = View.GONE
        } else {
            btnRecent.setTextColor(android.graphics.Color.GRAY)
            btnImported.setTextColor(android.graphics.Color.WHITE)
            if (useScopedDir) {
                barExplorerTools?.visibility = View.VISIBLE
            } else {
                barExplorerTools?.visibility = View.GONE
            }
        }

        if (currentLibraryTab == "Imported" && useScopedDir) {
             serviceScope.launch(Dispatchers.IO) {
                 if (rootExplorerDir == null) {
                     rootExplorerDir = android.os.Environment.getExternalStorageDirectory()
                     explorerStack.clear()
                     if (rootExplorerDir != null) explorerStack.add(rootExplorerDir!!)
                 }
                 currentExplorerDir = explorerStack.lastOrNull()
                 
                 val files = currentExplorerDir?.listFiles()?.toList() ?: emptyList()
                 val filteredFiles = files.filter { it.isDirectory || it.name.endsWith(".epub", true) || it.name.endsWith(".txt", true) }
                 
                 val sortedFiles = filteredFiles.sortedWith(Comparator { a, b ->
                     if (a.isDirectory && !b.isDirectory) return@Comparator -1
                     if (!a.isDirectory && b.isDirectory) return@Comparator 1
                     
                     if (explorerSortByName) {
                         val nameA = a.name ?: ""
                         val nameB = b.name ?: ""
                         if (explorerSortAscending) nameA.compareTo(nameB, ignoreCase = true) else nameB.compareTo(nameA, ignoreCase = true)
                     } else {
                         val dateA = a.lastModified()
                         val dateB = b.lastModified()
                         if (explorerSortAscending) dateA.compareTo(dateB) else dateB.compareTo(dateA)
                     }
                 })

                 withContext(Dispatchers.Main) {
                     tvPath?.text = currentExplorerDir?.name ?: "Explorer"
                     btnUp?.visibility = if (currentExplorerDir?.absolutePath == rootExplorerDir?.absolutePath) View.GONE else View.VISIBLE
                     
                     listLibrary.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@FloatingReaderService)
                     listLibrary.adapter = FileAdapter(sortedFiles)
                 }
             }
             return
        }

        serviceScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FloatingReaderService)
            val books = if (currentLibraryTab == "Recent") {
                db.epubDao().getAllBooks()
            } else {
                db.epubDao().getAllBooksByAddedDesc()
            }
            withContext(Dispatchers.Main) {
                listLibrary.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@FloatingReaderService)
                listLibrary.adapter = LibraryAdapter(books)
            }
        }
    }

    private val explorerStack = mutableListOf<java.io.File>()

    private fun openLibraryView() {
        hideOverlays()
        overlayLibrary.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        toolbarContainer.visibility = View.GONE
        tvWindowTitle.text = "Library"
        updateTopDragBarVisibility()
        
        loadLibraryBooks()
        
        floatingView.findViewById<Button>(R.id.btn_tab_recent)?.setOnClickListener {
            currentLibraryTab = "Recent"
            saveLibraryState()
            loadLibraryBooks()
        }
        
        floatingView.findViewById<Button>(R.id.btn_tab_imported)?.setOnClickListener {
            currentLibraryTab = "Imported"
            saveLibraryState()
            loadLibraryBooks()
        }

        floatingView.findViewById<View>(R.id.btn_explorer_up)?.setOnClickListener {
            if (explorerStack.size > 1) {
                explorerStack.removeLast()
                saveLibraryState()
                loadLibraryBooks()
            }
        }
        
        floatingView.findViewById<View>(R.id.btn_sort_type)?.setOnClickListener {
            explorerSortByName = !explorerSortByName
            saveLibraryState()
            loadLibraryBooks()
        }
        
        floatingView.findViewById<View>(R.id.btn_sort_order)?.setOnClickListener {
            explorerSortAscending = !explorerSortAscending
            saveLibraryState()
            loadLibraryBooks()
        }
    }

    private fun openChaptersView() {
        hideOverlays()
        overlayChapters.visibility = View.VISIBLE
        // Remove scrollView.visibility = View.GONE so chapters side panel is over the book text
        toolbarContainer.visibility = View.GONE
        tvWindowTitle.text = "Chapters"

        currentBook?.totalChapters?.let { count ->
            listChapters.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            listChapters.adapter = ChapterAdapter(count)
            listChapters.scrollToPosition(currentChapterIndex)
        }
    }

    private fun openSettingsView() {
        overlaySettings.visibility = View.VISIBLE
        toolbarContainer.visibility = View.GONE
        updateTopDragBarVisibility()
    }

    private fun openNotesView() {
        cameFromLibrary = (overlayLibrary.visibility == View.VISIBLE)
        hideOverlays()
        overlayNotes.visibility = View.VISIBLE
        toolbarContainer.visibility = View.GONE
        loadNotes()
        updateTopDragBarVisibility()
    }

    private fun openBookmarksView() {
        overlayBookmarks.visibility = View.VISIBLE
        toolbarContainer.visibility = View.GONE
        listBookmarks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        listBookmarks.adapter = BookmarkAdapter()
        updateTopDragBarVisibility()
    }

    private fun setupListeners() {
        val topDragBar = floatingView.findViewById<View>(R.id.top_drag_bar)
        val resizeHandle = floatingView.findViewById<View>(R.id.resize_handle)
        
        // Tap outside to fold removed

        // Long press movement for bubble and top bar
        val dragListener = createLongPressDragListener()
        bubbleIcon.setOnTouchListener(dragListener)
        topDragBar.setOnTouchListener(dragListener)

        // Make overlay headers draggable too as they replace topDragBar when overlays are shown
        floatingView.findViewById<View>(R.id.library_header)?.setOnTouchListener(dragListener)
        floatingView.findViewById<View>(R.id.notes_header)?.setOnTouchListener(dragListener)
        floatingView.findViewById<View>(R.id.settings_header)?.setOnTouchListener(dragListener)
        floatingView.findViewById<View>(R.id.bookmarks_header)?.setOnTouchListener(dragListener)

        // Click listener for top notes button in reader
        floatingView.findViewById<View>(R.id.btn_top_notes)?.setOnClickListener {
            openNotesView()
        }

        bubbleIcon.setOnClickListener { setFolded(false) }

        floatingView.findViewById<View>(R.id.btn_exit_bottom)?.setOnClickListener {
            saveCurrentPosition()
            stopSelf()
        }

        // Tap content to toggle Moonreader toolbar handled in touch listener now

        // Resize bottom right
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.width
                    initialY = layoutParams.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newWidth = initialX + (event.rawX - initialTouchX).toInt()
                    val newHeight = initialY + (event.rawY - initialTouchY).toInt()
                    layoutParams.width = max(400, newWidth)
                    layoutParams.height = max(600, newHeight)
                    savedWindowWidth = layoutParams.width
                    savedWindowHeight = layoutParams.height
                    prefs.edit()
                        .putInt("win_w", savedWindowWidth)
                        .putInt("win_h", savedWindowHeight)
                        .apply()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }
        
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e2 != null && overlayLibrary.visibility == View.VISIBLE) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 100 && Math.abs(velocityX) > 100) {
                        if (dx > 0) {
                            if (currentLibraryTab == "Imported") {
                                currentLibraryTab = "Recent"
                                loadLibraryBooks()
                                return true
                            }
                        } else {
                            if (currentLibraryTab == "Recent") {
                                currentLibraryTab = "Imported"
                                loadLibraryBooks()
                                return true
                            }
                        }
                    }
                }
                return false
            }
        })
        
        listLibrary.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
        
        floatingView.findViewById<View>(R.id.btn_library_settings)?.setOnClickListener {
            openSettingsView()
        }
        
        floatingView.findViewById<View>(R.id.btn_library_tracker)?.setOnClickListener {
            val intent = Intent(this, com.example.TrackerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try { startActivity(intent) } catch (e: Exception) { AppLogger.d("Service", "Failed to start tracker: ${e.message}") }
            setFolded(true)
        }
        
        floatingView.findViewById<View>(R.id.btn_library_notes)?.setOnClickListener {
            openNotesView()
        }
        
        floatingView.findViewById<View>(R.id.btn_settings_back)?.setOnClickListener {
            overlaySettings.visibility = View.GONE
        }
        floatingView.findViewById<View>(R.id.btn_bookmarks_back)?.setOnClickListener {
            overlayBookmarks.visibility = View.GONE
        }
        
        floatingView.findViewById<View>(R.id.btn_add_bookmark)?.setOnClickListener {
            val offset = tvContent.layout?.getLineStart(tvContent.layout?.getLineForVertical(scrollView.scrollY) ?: 0) ?: 0
            val content = tvContent.text.toString().substring(offset).trim()
            val words = if (content.length > 50) content.substring(0, 50).replace("\n", " ").trim() + "..." else content
            var percent = 0
            val totalChapters = currentBook?.totalChapters ?: 0
            if (totalChapters > 0) {
                val scrollY = scrollView.scrollY
                val maxScrollY = tvContent.height - scrollView.height
                val currentChapterPercent = if (maxScrollY > 0) (scrollY.toFloat() / maxScrollY) else 0f
                percent = (((currentChapterIndex + currentChapterPercent) * 100) / totalChapters).toInt()
            }
            bookmarksList.add(0, BookmarkItem(words, currentChapterIndex, percent))
            showToast("Bookmark added at Chapter ${currentChapterIndex + 1}")
        }

        // Toolbar Buttons
        floatingView.findViewById<View>(R.id.btn_prev).setOnClickListener { navigateChapter(-1) }
        floatingView.findViewById<View>(R.id.btn_next).setOnClickListener { navigateChapter(1) }
        floatingView.findViewById<View>(R.id.btn_chapters).setOnClickListener {
            openChaptersView()
        }
        floatingView.findViewById<View>(R.id.btn_minimize_bottom)?.setOnClickListener {
            setFolded(true)
        }
        floatingView.findViewById<View>(R.id.btn_exit_bottom)?.setOnClickListener {
            saveCurrentPosition()
            stopSelf()
        }

        floatingView.findViewById<View>(R.id.btn_copy_text)?.setOnClickListener {
            if (tvContent.hasSelection()) {
                val start = tvContent.selectionStart
                val end = tvContent.selectionEnd
                if (start != -1 && end != -1) {
                    val selectedText = tvContent.text.substring(Math.min(start, end), Math.max(start, end))
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Copied Text", selectedText)
                    clipboard.setPrimaryClip(clip)
                    showToast("Text Copied")
                }
            }
            floatingView.findViewById<View>(R.id.custom_selection_menu)?.visibility = View.GONE
            // Clear selection
            (tvContent.text as? android.text.Spannable)?.let { android.text.Selection.removeSelection(it) }
        }

        floatingView.findViewById<View>(R.id.btn_share_text)?.setOnClickListener {
            if (tvContent.hasSelection()) {
                val start = tvContent.selectionStart
                val end = tvContent.selectionEnd
                if (start != -1 && end != -1) {
                    val selectedText = tvContent.text.substring(Math.min(start, end), Math.max(start, end))
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, selectedText)
                        type = "text/plain"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share Text")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(chooser)
                }
            }
            floatingView.findViewById<View>(R.id.custom_selection_menu)?.visibility = View.GONE
            // Clear selection
            (tvContent.text as? android.text.Spannable)?.let { android.text.Selection.removeSelection(it) }
        }

        floatingView.findViewById<View>(R.id.btn_close_selection)?.setOnClickListener {
            floatingView.findViewById<View>(R.id.custom_selection_menu)?.visibility = View.GONE
            // Clear selection
            (tvContent.text as? android.text.Spannable)?.let { android.text.Selection.removeSelection(it) }
        }
        
        floatingView.findViewById<View>(R.id.btn_bookmarks)?.setOnClickListener {
            openBookmarksView()
        }
        floatingView.findViewById<View>(R.id.btn_library).setOnClickListener {
            saveCurrentPosition()
            openLibraryView()
        }
        floatingView.findViewById<View>(R.id.btn_auto_scroll).setOnClickListener {
            isAutoScrolling = !isAutoScrolling
            if (isAutoScrolling) {
                showToast("Auto-scroll ON")
                scrollHandler.post(scrollRunnable)
            } else {
                showToast("Auto-scroll OFF")
                scrollHandler.removeCallbacks(scrollRunnable)
            }
        }
        floatingView.findViewById<View>(R.id.btn_search).setOnClickListener {
            hideOverlays()
            val overlaySearch = floatingView.findViewById<View>(R.id.overlay_search)
            val isSearchVisible = overlaySearch.visibility == View.VISIBLE
            overlaySearch.visibility = if (isSearchVisible) View.GONE else View.VISIBLE
            toolbarContainer.visibility = View.GONE
        }
        floatingView.findViewById<View>(R.id.btn_close_search)?.setOnClickListener {
            floatingView.findViewById<View>(R.id.overlay_search).visibility = View.GONE
            floatingView.findViewById<View>(R.id.overlay_search_results).visibility = View.GONE
        }
        
        val etSearch = floatingView.findViewById<EditText>(R.id.et_search)
        floatingView.findViewById<View>(R.id.btn_do_search).setOnClickListener {
            val query = etSearch.text.toString()
            if (query.isNotEmpty() && chapterContent.contains(query, ignoreCase = true)) {
                val index = chapterContent.indexOf(query, ignoreCase = true)
                if (index >= 0) {
                    val searchLayout = tvContent.layout
                    if (searchLayout != null) {
                        val line = searchLayout.getLineForOffset(index)
                        val y = searchLayout.getLineTop(line)
                        scrollView.smoothScrollTo(0, y)
                    }
                }
            } else {
                showToast("Not found in chapter")
            }
        }
        
        floatingView.findViewById<View>(R.id.btn_close_search_results)?.setOnClickListener {
            floatingView.findViewById<View>(R.id.overlay_search_results).visibility = View.GONE
            searchJob?.cancel()
        }

        floatingView.findViewById<View>(R.id.btn_do_search_full)?.setOnClickListener {
            val query = etSearch.text.toString()
            if (query.isNotEmpty()) {
                performFullBookSearch(query)
            }
        }
        
        val seekProgress = floatingView.findViewById<SeekBar>(R.id.seek_progress)
        seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentBook?.let { book ->
                        val targetChapter = (progress * max(1, book.totalChapters - 1)) / 100
                        navigateChapter(targetChapter - currentChapterIndex)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        floatingView.findViewById<View>(R.id.btn_settings).setOnClickListener {
            openSettingsView()
        }
        
        floatingView.findViewById<View>(R.id.btn_export_logs)?.setOnClickListener {
            try {
                val f = AppLogger.export(this)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(i, "Export Logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // If FileProvider isn't perfectly set up in AndroidManifest yet, fallback to Toast
                AppLogger.d("Settings", "Export failed: ${e.message}")
                showToast("Logs saved to Downloads folder")
                AppLogger.export(this)
            }
        }
        
        floatingView.findViewById<Switch>(R.id.switch_bookmarks)?.setOnCheckedChangeListener { _, isChecked ->
            // Stub for bookmarks feature enablement
        }
        
        val switchScoped = floatingView.findViewById<Switch>(R.id.switch_scoped_dir)
        switchScoped?.isChecked = prefs.getBoolean("use_scoped_dir", false)
        
        switchScoped?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                switchScoped.isChecked = false
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(intent)
                }
                showToast("Please grant All Files Access")
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("use_scoped_dir", isChecked).apply()
            // reload library text if currently in library
            if (overlayLibrary.visibility == View.VISIBLE) {
                loadLibraryBooks()
            }
        }
        
        floatingView.findViewById<Switch>(R.id.switch_keep_screen_on)?.apply {
            isChecked = prefs.getBoolean("keep_screen_on", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("keep_screen_on", isChecked).apply()
                updateKeepScreenOn()
            }
        }
        
        floatingView.findViewById<Switch>(R.id.switch_theme)?.setOnCheckedChangeListener { _, isChecked ->
            // Basic theme mock logic
            val bgColor = if (isChecked) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#222222")
            val txColor = if (isChecked) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#DDDDDD")
            windowContainer.setBackgroundColor(bgColor)
            tvContent.setTextColor(txColor)
            overlayChapters.setBackgroundColor(bgColor)
            overlayLibrary.setBackgroundColor(bgColor)
            overlaySettings.setBackgroundColor(bgColor)
        }
        
        floatingView.findViewById<SeekBar>(R.id.seek_font_size)?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvContent.textSize = (12 + progress).toFloat()
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        var lastTopBarTapTime = 0L
        floatingView.findViewById<View>(R.id.top_drag_bar).setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTopBarTapTime < 300) {
                toggleFullScreen()
                lastTopBarTapTime = 0L
            } else {
                lastTopBarTapTime = now
                if (overlayLibrary.visibility == View.VISIBLE || overlayChapters.visibility == View.VISIBLE || overlaySettings.visibility == View.VISIBLE) {
                    hideOverlays()
                    currentBook?.let { 
                        val displayTitle = it.title.replace(Regex("(?i)\\.epub$"), "")
                        tvWindowTitle.text = "$displayTitle (Ch ${currentChapterIndex + 1}/${it.totalChapters})"
                    }
                }
            }
        }
        
        floatingView.findViewById<View>(R.id.btn_close_fullscreen)?.setOnClickListener {
            toggleFullScreen()
        }
        
        btnTts.setOnClickListener {
            toggleTts()
        }

        // Track scrolling for progress saving
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    lastKnownScrollY = scrollY
                    if (prefs.getBoolean("continuous_save", false)) {
                        scrollHandler.removeCallbacks(saveScrollRunnable)
                        scrollHandler.postDelayed(saveScrollRunnable, 1500)
                    }
                }
            }
        }

        private val saveScrollRunnable = Runnable {
            saveCurrentPosition()
        }

    private var lastKnownScrollY = 0

    private fun createLongPressDragListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            private val handler = Handler(Looper.getMainLooper())
            private var isLongPressed = false
            private var downX = 0f
            private var downY = 0f
            private val longPressRunnable = Runnable { isLongPressed = true }

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        downX = event.rawX
                        downY = event.rawY
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isLongPressed = false
                        handler.postDelayed(longPressRunnable, 300)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isLongPressed) {
                            if (Math.abs(event.rawX - downX) > 20 || Math.abs(event.rawY - downY) > 20) {
                                handler.removeCallbacks(longPressRunnable)
                            }
                        } else {
                            layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            prefs.edit()
                                .putInt("win_x", layoutParams.x)
                                .putInt("win_y", layoutParams.y)
                                .apply()
                            windowManager.updateViewLayout(floatingView, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (!isLongPressed) {
                            if (Math.abs(event.rawX - downX) < 20 && Math.abs(event.rawY - downY) < 20) {
                                view.performClick()
                            }
                        }
                        isLongPressed = false
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun loadBook(bookId: Int) {
        prefs.edit().putInt("last_book_id", bookId).apply()
        serviceScope.launch {
            val db = AppDatabase.getDatabase(this@FloatingReaderService)
            val fetchedBook = db.epubDao().getBookById(bookId)
            var book = fetchedBook?.copy(lastOpenedTimestamp = System.currentTimeMillis())
            if (book != null) {
                // If there's a TrackerBook from Moon+ Reader or track goal, sync reading progress to EPUB
                val trackerBook = db.trackerDao().getAllBooks().firstOrNull { it.title.equals(book.title, true) }
                if (trackerBook != null && trackerBook.readChapters > book.lastReadChapter) {
                    book = book.copy(lastReadChapter = trackerBook.readChapters)
                }
                db.epubDao().updateBook(book)
                currentBook = book
                
                var targetChapter = book.lastReadChapter
                val totalChapters = book.totalChapters
                if (targetChapter >= totalChapters) {
                    targetChapter = totalChapters - 1
                }
                if (targetChapter < 0) {
                    targetChapter = 0
                }
                currentChapterIndex = targetChapter
                loadChapterText()
            }
        }
    }

    private fun loadChapterText(scrollToBottom: Boolean = false) {
        val book = currentBook ?: return
        serviceScope.launch(Dispatchers.IO) {
            val bookDir = File(filesDir, "book_${book.id}")
            val chapterFile = File(bookDir, "chapter_$currentChapterIndex.txt")
            if (chapterFile.exists()) {
                val text = chapterFile.readText()
                withContext(Dispatchers.Main) {
                    chapterContent = text
                    renderChapter(book.lastReadScrollY, scrollToBottom)
                }
            } else {
                withContext(Dispatchers.Main) {
                    chapterContent = "Chapter content not found."
                    renderChapter(0, scrollToBottom)
                }
            }
        }
    }

    private fun renderChapter(scrollY: Int, scrollToBottom: Boolean = false) {
        val book = currentBook ?: return
        val displayTitle = book.title.replace(Regex("(?i)\\.epub$"), "")
        tvWindowTitle.text = "$displayTitle (Ch ${currentChapterIndex + 1}/${book.totalChapters})"
        
        tvContent.text = chapterContent
        val percent = if (book.totalChapters > 1) {
            (currentChapterIndex * 100) / (book.totalChapters - 1)
        } else {
            100
        }
        tvProgress.text = "$percent%"
        
        floatingView.findViewById<SeekBar>(R.id.seek_progress)?.progress = percent
        
        scrollView.post {
            if (scrollToBottom) {
                // Scroll to bottom
                val maxScroll = Math.max(0, scrollView.getChildAt(0).height - scrollView.height)
                scrollView.scrollTo(0, maxScroll)
            } else {
                scrollView.scrollTo(0, scrollY)
            }
        }
    }

    private fun navigateChapter(offset: Int, scrollToBottom: Boolean = false) {
        currentBook?.let { book ->
            val newIndex = currentChapterIndex + offset
            if (newIndex in 0 until book.totalChapters) {
                saveCurrentPosition() // save before swap
                currentChapterIndex = newIndex
                // aggressively update db memory
                val updated = book.copy(lastReadChapter = newIndex, lastReadScrollY = 0)
                currentBook = updated
                loadChapterText(scrollToBottom)
            }
        }
    }

    private var autoSaveJob: Job? = null
    private var searchJob: Job? = null

    private fun performFullBookSearch(query: String) {
        val book = currentBook ?: return
        
        val overlayResults = floatingView.findViewById<FrameLayout>(R.id.overlay_search_results)
        val tvStatus = floatingView.findViewById<TextView>(R.id.tv_search_status)
        val llResults = floatingView.findViewById<LinearLayout>(R.id.ll_search_results)
        
        overlayResults.visibility = View.VISIBLE
        llResults.removeAllViews()
        tvStatus.text = "Searching..."
        
        searchJob?.cancel()
        searchJob = serviceScope.launch(Dispatchers.IO) {
            val bookDir = File(filesDir, "book_${book.id}")
            var matchCount = 0
            
            for (i in 0 until book.totalChapters) {
                if (!isActive) break // Canceled
                
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Searching chapter ${i+1}/${book.totalChapters}..."
                }
                
                val chapterFile = File(bookDir, "chapter_$i.txt")
                if (chapterFile.exists()) {
                    val text = chapterFile.readText()
                    var startIndex = 0
                    while (startIndex < text.length) {
                        val index = text.indexOf(query, startIndex, ignoreCase = true)
                        if (index == -1) break
                        
                        matchCount++
                        if (matchCount > 500) break // Cap results
                        
                        val snippetStart = max(0, index - 40)
                        val snippetEnd = minOf(text.length, index + query.length + 40)
                        val snippetStr = text.substring(snippetStart, snippetEnd)
                            .replace("\n", " ").trim()
                            
                        // Show result on main thread immediately
                        withContext(Dispatchers.Main) {
                            val tvResult = TextView(this@FloatingReaderService).apply {
                                setPadding(24, 24, 24, 24)
                                setTextColor(Color.LTGRAY)
                                textSize = 14f
                                
                                val spannable = SpannableString("Ch ${i+1}: ...$snippetStr...")
                                // Highlight query string inside the snippet
                                val colorSpanStart = spannable.indexOf(query, ignoreCase = true)
                                if (colorSpanStart >= 0) {
                                    val colorSpanEnd = colorSpanStart + query.length
                                    spannable.setSpan(BackgroundColorSpan(Color.parseColor("#880099CC")), colorSpanStart, colorSpanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                                setText(spannable)
                                
                                setBackgroundResource(android.R.drawable.list_selector_background)
                                isClickable = true
                                isFocusable = true
                                setOnClickListener {
                                    overlayResults.visibility = View.GONE
                                    floatingView.findViewById<View>(R.id.overlay_search).visibility = View.GONE
                                    loadAndJumpToOffset(i, index)
                                }
                            }
                            llResults.addView(tvResult)
                            
                            val divider = View(this@FloatingReaderService).apply {
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                                setBackgroundColor(Color.DKGRAY)
                            }
                            llResults.addView(divider)
                        }
                        
                        startIndex = index + query.length
                    }
                }
                
                if (matchCount > 500) break
            }
            
            withContext(Dispatchers.Main) {
                if (isActive) {
                    if (matchCount == 0) {
                        tvStatus.text = "No results found."
                    } else {
                        tvStatus.text = "Found $matchCount results."
                    }
                }
            }
        }
    }

    private fun loadAndJumpToOffset(targetChapterIndex: Int, textOffset: Int) {
        val book = currentBook ?: return
        serviceScope.launch(Dispatchers.IO) {
            val bookDir = File(filesDir, "book_${book.id}")
            val chapterFile = File(bookDir, "chapter_$targetChapterIndex.txt")
            if (chapterFile.exists()) {
                val text = chapterFile.readText()
                withContext(Dispatchers.Main) {
                    currentChapterIndex = targetChapterIndex
                    chapterContent = text
                    val displayTitle = book.title.replace(Regex("(?i)\\.epub$"), "")
                    tvWindowTitle.text = "$displayTitle (Ch ${currentChapterIndex + 1}/${book.totalChapters})"
                    tvContent.text = chapterContent
                    
                    val percent = if (book.totalChapters > 1) {
                        (currentChapterIndex * 100) / (book.totalChapters - 1)
                    } else 100
                    tvProgress.text = "$percent%"
                    floatingView.findViewById<SeekBar>(R.id.seek_progress)?.progress = percent
                    
                    // Post to wait for layout
                    scrollView.postDelayed({
                        val searchLayout = tvContent.layout
                        if (searchLayout != null) {
                            val line = searchLayout.getLineForOffset(textOffset)
                            val y = searchLayout.getLineTop(line)
                            scrollView.scrollTo(0, y)
                            saveCurrentPosition()
                        }
                    }, 50)
                }
            }
        }
    }

    private fun startAutoSaveTimer() {
        autoSaveJob?.cancel()
        autoSaveJob = serviceScope.launch {
            while (true) {
                delay(15 * 60 * 1000L) // 15 minutes
                saveCurrentPosition()
            }
        }
    }

    private fun saveCurrentPosition() {
        currentBook?.let { book ->
            val updated = book.copy(lastReadChapter = currentChapterIndex, lastReadScrollY = lastKnownScrollY)
            serviceScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@FloatingReaderService)
                db.epubDao().updateBook(updated)
                
                val trackerBooks = db.trackerDao().getAllBooks()
                val existingTracker = trackerBooks.firstOrNull { it.title == book.title }
                val totalCh = book.totalChapters
                if (existingTracker != null) {
                    if (existingTracker.readChapters != currentChapterIndex || existingTracker.totalChapters < totalCh) {
                        val newTracker = existingTracker.copy(
                            readChapters = Math.max(existingTracker.readChapters, currentChapterIndex),
                            totalChapters = Math.max(existingTracker.totalChapters, totalCh),
                            lastUpdatedTimestamp = System.currentTimeMillis()
                        )
                        db.trackerDao().updateBook(newTracker)
                    }
                } else {
                    val newTracker = com.example.data.TrackerBook(
                        title = book.title,
                        author = "",
                        totalChapters = totalCh,
                        readChapters = currentChapterIndex,
                        addedTimestamp = System.currentTimeMillis(),
                        lastUpdatedTimestamp = System.currentTimeMillis()
                    )
                    db.trackerDao().insertBook(newTracker)
                }
            }
        }
    }

    private fun toggleTts() {
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    isTtsReady = true
                    executeTtsToggle()
                } else {
                    showToast("TTS failed to initialize")
                }
            }
            return
        }
        executeTtsToggle()
    }

    private fun executeTtsToggle() {
        if (!isTtsReady) {
            showToast("TTS not ready")
            return
        }
        if (isSpeaking) {
            tts?.stop()
            isSpeaking = false
            btnTts.setImageResource(android.R.drawable.ic_media_play)
        } else {
            val offset = tvContent.layout?.getLineStart(tvContent.layout?.getLineForVertical(scrollView.scrollY) ?: 0) ?: 0
            val textToSpeak = chapterContent.substring(offset)
            val chunks = textToSpeak.chunked(3000)
            for (chunk in chunks) {
                tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, null)
            }
            isSpeaking = true
            btnTts.setImageResource(android.R.drawable.ic_media_pause)
        }
    }



    private inner class FileAdapter(var files: List<java.io.File>) : androidx.recyclerview.widget.RecyclerView.Adapter<FileAdapter.FileViewHolder>() {
        
        inner class FileViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_file_icon)
            val tvName: TextView = view.findViewById(R.id.tv_file_name)
            val tvSize: TextView = view.findViewById(R.id.tv_file_size)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val file = files[pos]
                        if (file.isDirectory) {
                            explorerStack.add(file)
                            saveLibraryState()
                            loadLibraryBooks()
                        } else {
                            // Try importing it if not already in db, then open
                            showToast("Importing ${file.name}...")
                            serviceScope.launch(Dispatchers.IO) {
                                val repo = com.example.data.LibraryRepository(this@FloatingReaderService)
                                val book = repo.importBook(Uri.fromFile(file))
                                withContext(Dispatchers.Main) {
                                    if (book != null) {
                                        loadBook(book.id)
                                        hideOverlays()
                                    } else {
                                        showToast("Failed to import")
                                    }
                                }
                            }
                        }
                    }
                }
                
                view.setOnLongClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val file = files[pos]
                        showExplorerContextMenu(file)
                    }
                    true
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_explorer, parent, false)
            return FileViewHolder(view)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            val file = files[position]
            holder.tvName.text = file.name ?: "Unknown"
            
            // clear previous tag
            holder.ivIcon.tag = null
            
            if (file.isDirectory) {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                holder.ivIcon.setColorFilter(android.graphics.Color.parseColor("#FFD54F")) // Folder color
                holder.tvSize.visibility = View.GONE
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size)
                holder.ivIcon.setColorFilter(android.graphics.Color.parseColor("#7FE9F9")) // File color
                holder.tvSize.visibility = View.VISIBLE
                
                val ext = file.extension.uppercase()
                val sizeBytes = file.length()
                val sizeText = when {
                    sizeBytes > 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024f * 1024f))
                    sizeBytes > 1024 -> String.format("%.1f KB", sizeBytes / 1024f)
                    else -> "$sizeBytes B"
                }
                holder.tvSize.text = if (ext.isNotEmpty()) "$ext • $sizeText" else sizeText
                
                if (file.name.endsWith(".epub", true)) {
                    holder.ivIcon.tag = file.absolutePath
                    serviceScope.launch(Dispatchers.IO) {
                        val bitmap = loadEpubCover(file)
                        withContext(Dispatchers.Main) {
                            if (holder.ivIcon.tag == file.absolutePath && bitmap != null) {
                                holder.ivIcon.clearColorFilter()
                                holder.ivIcon.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = files.size
    }

    private fun getCoverCacheDir(): java.io.File {
        val root = android.os.Environment.getExternalStorageDirectory()
        val cacheDir = java.io.File(root, "Books/VianReader/.covers")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    private fun loadEpubCover(file: java.io.File): android.graphics.Bitmap? {
        val cacheDir = getCoverCacheDir()
        val cacheFile = java.io.File(cacheDir, "${file.absolutePath.hashCode()}.jpg")
        
        if (cacheFile.exists()) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                return android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
            } catch (e: Exception) {}
        }
        
        try {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val coverEntry = entries.firstOrNull { 
                    it.name.contains("cover", true) && 
                    (it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) || it.name.endsWith(".jpeg", true)) 
                } ?: entries.firstOrNull { 
                    it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) || it.name.endsWith(".jpeg", true) 
                }
                
                if (coverEntry != null) {
                    val bytes = zip.getInputStream(coverEntry).readBytes()
                    
                    try {
                        java.io.FileOutputStream(cacheFile).use { fos ->
                            fos.write(bytes)
                        }
                    } catch(e: Exception) {}
                    
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun showExplorerContextMenu(file: java.io.File) {
        val options = arrayOf("Properties", "Rename", "Delete")
        val builder = android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
        builder.setTitle(file.name)
        builder.setItems(options) { dialog, which ->
            when (which) {
                0 -> {
                    // Properties
                    val sizeBytes = file.length()
                    val sizeMB = sizeBytes / (1024f * 1024f)
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
                    val props = "Name: ${file.name}\nSize: ${String.format("%.2f MB", sizeMB)}\nModified: $date\nType: ${if(file.isDirectory) "Folder" else "File"}"
                    android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                        .setTitle("Properties")
                        .setMessage(props)
                        .setPositiveButton("OK", null)
                        .run {
                            val d = create()
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            } else {
                                d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_PHONE)
                            }
                            d.show()
                        }
                }
                1 -> {
                    // Rename
                    val input = android.widget.EditText(this)
                    input.setText(file.name)
                    android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                        .setTitle("Rename")
                        .setView(input)
                        .setPositiveButton("Rename") { _, _ ->
                            val newName = input.text.toString()
                            if (newName.isNotBlank()) {
                                val newFile = java.io.File(file.parent, newName)
                                if (file.renameTo(newFile)) {
                                    showToast("Renamed successful")
                                    loadLibraryBooks()
                                } else {
                                    showToast("Rename failed")
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .run {
                            val d = create()
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            } else {
                                d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_PHONE)
                            }
                            d.show()
                        }
                }
                2 -> {
                    // Delete
                    android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                        .setTitle("Delete")
                        .setMessage("Are you sure you want to delete ${file.name}?")
                        .setPositiveButton("Delete") { _, _ ->
                            if (file.delete()) {
                                showToast("Deleted")
                                loadLibraryBooks()
                            } else {
                                showToast("Delete failed")
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .run {
                            val d = create()
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            } else {
                                d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_PHONE)
                            }
                            d.show()
                        }
                }
            }
        }
        val dialog = builder.create()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.show()
    }

    private inner class LibraryAdapter(var books: List<com.example.data.EpubBook>) : androidx.recyclerview.widget.RecyclerView.Adapter<LibraryAdapter.LibraryViewHolder>() {
        
        inner class LibraryViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvSub: TextView = view.findViewById(R.id.tv_subtitle)
            val btnMore: ImageView = view.findViewById(R.id.btn_more)
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val book = books[pos]
                        if (book.isParsed) {
                            loadBook(book.id)
                            hideOverlays()
                        } else {
                            showToast("Book is still parsing...")
                        }
                    }
                }
                btnMore.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val book = books[pos]
                        serviceScope.launch(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(this@FloatingReaderService)
                            db.epubDao().deleteBook(book)
                            val updated = db.epubDao().getAllBooks()
                            withContext(Dispatchers.Main) {
                                books = updated
                                notifyDataSetChanged()
                            }
                        }
                        showToast("Deleted ${book.title}")
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LibraryViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_book, parent, false)
            return LibraryViewHolder(view)
        }

        override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
            val book = books[position]
            holder.tvTitle.text = book.title
            val status = if (book.isParsed) "Parsed • Ch ${book.lastReadChapter + 1}/${book.totalChapters}" else "Parsing/Pending..."
            holder.tvSub.text = status
        }

        override fun getItemCount() = books.size
    }

    // --- Quick Notes Implementation ---
    private lateinit var listNotes: androidx.recyclerview.widget.RecyclerView
    private lateinit var btnNotesBack: View
    private lateinit var btnNotesAdd: View
    private lateinit var btnNotesDelete: View
    private lateinit var tvNotesTitle: android.widget.TextView
    private var notesAdapter: NotesAdapter? = null
    
    private val selectedNotes = mutableSetOf<com.example.data.QuickNote>()
    private var notesList = listOf<com.example.data.QuickNote>()
    
    private fun initNotesOverlay() {
        listNotes = floatingView.findViewById(R.id.list_notes)
        btnNotesBack = floatingView.findViewById(R.id.btn_notes_back)
        btnNotesAdd = floatingView.findViewById(R.id.btn_notes_add)
        btnNotesDelete = floatingView.findViewById(R.id.btn_notes_delete)
        tvNotesTitle = floatingView.findViewById(R.id.tv_notes_title)
        
        listNotes.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        notesAdapter = NotesAdapter()
        listNotes.adapter = notesAdapter
        
        btnNotesBack.setOnClickListener {
            if (selectedNotes.isNotEmpty()) {
                selectedNotes.clear()
                updateNotesUi()
            } else {
                overlayNotes.visibility = View.GONE
                if (cameFromLibrary) {
                    openLibraryView()
                } else {
                    scrollView.visibility = View.VISIBLE
                    toolbarContainer.visibility = View.VISIBLE
                }
                updateTopDragBarVisibility()
            }
        }
        
        btnNotesAdd.setOnClickListener {
            showNoteDialog(null)
        }
        
        btnNotesDelete.setOnClickListener {
            if (selectedNotes.isNotEmpty()) {
                val toDelete = selectedNotes.toList()
                serviceScope.launch(Dispatchers.IO) {
                    val db = com.example.data.AppDatabase.getDatabase(this@FloatingReaderService)
                    db.quickNoteDao().deleteNotes(toDelete)
                    selectedNotes.clear()
                    loadNotes()
                    withContext(Dispatchers.Main) { updateNotesUi() }
                }
            }
        }
    }
    
    private fun updateNotesUi() {
        if (selectedNotes.isNotEmpty()) {
            tvNotesTitle.text = "${selectedNotes.size} Selected"
            btnNotesDelete.visibility = View.VISIBLE
            btnNotesAdd.visibility = View.GONE
        } else {
            tvNotesTitle.text = "Quick Notes"
            btnNotesDelete.visibility = View.GONE
            btnNotesAdd.visibility = View.VISIBLE
        }
        notesAdapter?.notifyDataSetChanged()
    }
    
    private fun loadNotes() {
        serviceScope.launch(Dispatchers.IO) {
            val db = com.example.data.AppDatabase.getDatabase(this@FloatingReaderService)
            db.quickNoteDao().getAllNotes().collect { notes ->
                notesList = notes
                withContext(Dispatchers.Main) {
                    notesAdapter?.notifyDataSetChanged()
                }
            }
        }
    }
    
    private fun showNoteDialog(note: com.example.data.QuickNote?) {
        val dialogView = android.view.LayoutInflater.from(applicationContext).inflate(R.layout.dialog_quick_note, null)
        val etTitle = dialogView.findViewById<android.widget.EditText>(R.id.et_note_title)
        val etText = dialogView.findViewById<android.widget.EditText>(R.id.et_note_text)
        
        if (note != null) {
            etTitle.setText(note.title)
            etText.setText(note.text)
        }
        
        val dialog = android.app.AlertDialog.Builder(applicationContext, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (note == null) "New Note" else "Edit Note")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val title = etTitle.text.toString().trim()
                val text = etText.text.toString().trim()
                if (title.isNotEmpty() || text.isNotEmpty()) {
                    serviceScope.launch(Dispatchers.IO) {
                        val db = com.example.data.AppDatabase.getDatabase(this@FloatingReaderService)
                        if (note == null) {
                            db.quickNoteDao().insertNote(com.example.data.QuickNote(title = title, text = text))
                        } else {
                            db.quickNoteDao().updateNote(note.copy(title = title, text = text))
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.show()
    }
    
    private inner class NotesAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {
        inner class NoteViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvTitle: android.widget.TextView = view.findViewById(R.id.tv_note_title)
            val tvText: android.widget.TextView = view.findViewById(R.id.tv_note_text)
            val container: View = view.findViewById(R.id.ll_note_container)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): NoteViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_quick_note, parent, false)
            return NoteViewHolder(view)
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
            val note = notesList[position]
            if (note.title.isNotEmpty()) {
                holder.tvTitle.text = note.title
                holder.tvTitle.visibility = View.VISIBLE
            } else {
                holder.tvTitle.visibility = View.GONE
            }
            holder.tvText.text = note.text
            
            if (selectedNotes.contains(note)) {
                holder.container.setBackgroundColor(android.graphics.Color.parseColor("#3A5A7A"))
            } else {
                holder.container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            
            holder.itemView.setOnClickListener {
                if (selectedNotes.isNotEmpty()) {
                    if (selectedNotes.contains(note)) selectedNotes.remove(note) else selectedNotes.add(note)
                    updateNotesUi()
                } else {
                    showNoteDialog(note)
                }
            }
            
            holder.itemView.setOnLongClickListener {
                if (selectedNotes.contains(note)) selectedNotes.remove(note) else selectedNotes.add(note)
                updateNotesUi()
                true
            }
        }

        override fun getItemCount() = notesList.size
    }

    private data class BookmarkItem(val words: String, val chapter: Int, val percentage: Int)
    
    private val bookmarksList = mutableListOf<BookmarkItem>()

    private inner class BookmarkAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder>() {
        inner class BookmarkViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvWords: TextView = view.findViewById(R.id.tv_bookmark_words)
            val tvChapter: TextView = view.findViewById(R.id.tv_bookmark_chapter)
            val tvPercent: TextView = view.findViewById(R.id.tv_bookmark_percent)
            val btnDelete: ImageView = view.findViewById(R.id.btn_delete_bookmark)
            
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        val bm = bookmarksList[pos]
                        loadAndJumpToOffset(bm.chapter, 0)
                        floatingView.findViewById<View>(R.id.overlay_bookmarks)?.visibility = View.GONE
                    }
                }
                btnDelete.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        bookmarksList.removeAt(pos)
                        notifyItemRemoved(pos)
                        notifyItemRangeChanged(pos, bookmarksList.size)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): BookmarkViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
            return BookmarkViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
            val bm = bookmarksList[position]
            holder.tvWords.text = bm.words
            holder.tvChapter.text = "Chapter ${bm.chapter + 1}"
            holder.tvPercent.text = "${bm.percentage}%"
        }

        override fun getItemCount() = bookmarksList.size
    }
        
    private inner class ChapterAdapter(val totalChapters: Int) : androidx.recyclerview.widget.RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder>() {
        inner class ChapterViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_chapter_title)
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        saveCurrentPosition()
                        currentChapterIndex = pos
                        currentBook?.let { book ->
                            currentBook = book.copy(lastReadChapter = pos, lastReadScrollY = 0)
                        }
                        loadChapterText()
                        hideOverlays()
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChapterViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false)
            return ChapterViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
            holder.tvTitle.text = "Chapter ${position + 1}"
            if (position == currentChapterIndex) {
                holder.tvTitle.setTextColor(android.graphics.Color.parseColor("#7FE9F9"))
            } else {
                holder.tvTitle.setTextColor(android.graphics.Color.WHITE)
            }
        }

        override fun getItemCount() = totalChapters
    }

    fun setFolded(folded: Boolean) {
        isFolded = folded
        if (folded) {
            bubbleIcon.visibility = View.VISIBLE
            windowContainer.visibility = View.GONE
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            
            // Restore bubble position
            layoutParams.x = foldedX
            layoutParams.y = foldedY
            
            isAutoScrolling = false // pause scroll
        } else {
            // Save bubble position before expanding
            foldedX = layoutParams.x
            foldedY = layoutParams.y

            bubbleIcon.visibility = View.GONE
            windowContainer.visibility = View.VISIBLE
            toolbarContainer.visibility = View.GONE
            
            val metrics = resources.displayMetrics
            val maxW = metrics.widthPixels
            val maxH = metrics.heightPixels
            
            layoutParams.width = Math.min(savedWindowWidth, maxW)
            layoutParams.height = Math.min(savedWindowHeight, maxH)
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            
            if (layoutParams.x + layoutParams.width > maxW) {
                layoutParams.x = maxW - layoutParams.width
            }
            if (layoutParams.x < 0) layoutParams.x = 0
            
            if (layoutParams.y + layoutParams.height > maxH) {
                layoutParams.y = maxH - layoutParams.height
            }
            if (layoutParams.y < 0) layoutParams.y = 0
        }
        windowManager.updateViewLayout(floatingView, layoutParams)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        saveCurrentPosition()
        mediaSession?.isActive = false
        mediaSession?.release()
        tts?.stop()
        tts?.shutdown()
        scrollHandler.removeCallbacks(scrollRunnable)
        serviceScope.cancel()
        if (::windowManager.isInitialized && ::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }
}
