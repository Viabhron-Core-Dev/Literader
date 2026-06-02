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
import java.util.Locale
import kotlin.math.max

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

    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var isSpeaking = false
    private lateinit var btnTts: ImageView

    // Auto Scroll State
    private var isAutoScrolling = false
    private val scrollHandler = Handler(Looper.getMainLooper())
    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (isAutoScrolling) {
                scrollView.smoothScrollBy(0, 2)
                scrollHandler.postDelayed(this, 30) // light speed modifier
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

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

        val notification = androidx.core.app.NotificationCompat.Builder(this, "reader_channel")
            .setContentTitle("LiteReader")
            .setContentText("Reading active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
            
        val mediaSession = android.media.session.MediaSession(this, "FloatingReader")
        mediaSession.isActive = true

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }

        prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        savedWindowWidth = prefs.getInt("win_w", 800)
        savedWindowHeight = prefs.getInt("win_h", 1200)
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                isTtsReady = true
            }
        }
        
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

        initViews()
        setupListeners()
        setFolded(true)
    }

    private lateinit var overlayLibrary: View
    private lateinit var overlayChapters: View
    private lateinit var overlaySettings: View
    private lateinit var listLibrary: androidx.recyclerview.widget.RecyclerView
    private lateinit var listChapters: androidx.recyclerview.widget.RecyclerView

    private fun initViews() {
        tvWindowTitle = floatingView.findViewById(R.id.tv_window_title)
        tvContent = floatingView.findViewById(R.id.tv_content)
        scrollView = floatingView.findViewById(R.id.scroll_view)
        tvProgress = floatingView.findViewById(R.id.tv_progress)
        toolbarContainer = floatingView.findViewById(R.id.toolbar_container)
        bubbleIcon = floatingView.findViewById(R.id.bubble_icon)
        
        var startY = 0f
        scrollView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val endY = event.y
                    val dy = startY - endY 
                    
                    if (dy > 150 && !scrollView.canScrollVertically(1)) {
                        navigateChapter(1)
                    } else if (dy < -150 && !scrollView.canScrollVertically(-1)) {
                        navigateChapter(-1)
                    }
                }
            }
            false
        }
        windowContainer = floatingView.findViewById(R.id.window_container)
        btnTts = floatingView.findViewById(R.id.btn_tts)

        overlayLibrary = floatingView.findViewById(R.id.overlay_library)
        overlayChapters = floatingView.findViewById(R.id.overlay_chapters)
        overlaySettings = floatingView.findViewById(R.id.overlay_settings)
        listLibrary = floatingView.findViewById(R.id.list_library)
        listChapters = floatingView.findViewById(R.id.list_chapters)
    }

    private fun hideOverlays() {
        overlayLibrary.visibility = View.GONE
        overlayChapters.visibility = View.GONE
        overlaySettings.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
    }

    private var currentLibraryTab = "Recent"

    private fun loadLibraryBooks() {
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
                
                val btnRecent = floatingView.findViewById<Button>(R.id.btn_tab_recent)
                val btnImported = floatingView.findViewById<Button>(R.id.btn_tab_imported)
                if (currentLibraryTab == "Recent") {
                    btnRecent.setTextColor(android.graphics.Color.WHITE)
                    btnImported.setTextColor(android.graphics.Color.GRAY)
                } else {
                    btnRecent.setTextColor(android.graphics.Color.GRAY)
                    btnImported.setTextColor(android.graphics.Color.WHITE)
                }
            }
        }
    }

    private fun openLibraryView() {
        hideOverlays()
        overlayLibrary.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        tvWindowTitle.text = "Library"
        
        loadLibraryBooks()
        
        floatingView.findViewById<Button>(R.id.btn_tab_recent)?.setOnClickListener {
            currentLibraryTab = "Recent"
            loadLibraryBooks()
        }
        
        floatingView.findViewById<Button>(R.id.btn_tab_imported)?.setOnClickListener {
            currentLibraryTab = "Imported"
            loadLibraryBooks()
        }

        floatingView.findViewById<View>(R.id.fab_add_book)?.setOnClickListener {
            val intent = Intent(this, com.example.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("PICK_EPUB", true)
            }
            try { startActivity(intent) } catch (e: Exception) { AppLogger.d("Service", "Failed to start library import: ${e.message}") }
        }
        
        floatingView.findViewById<View>(R.id.fab_tracker)?.setOnClickListener {
            val intent = Intent(this, com.example.TrackerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try { startActivity(intent) } catch (e: Exception) { AppLogger.d("Service", "Failed to start tracker: ${e.message}") }
            setFolded(true)
        }
        
        floatingView.findViewById<View>(R.id.fab_continue)?.setOnClickListener {
            hideOverlays()
            if (currentBook == null) {
                // If no book is open, open the last read book
                val lastBook = prefs.getInt("last_book_id", -1)
                if (lastBook != -1) {
                    loadBook(lastBook)
                }
            }
        }
    }

    private fun openChaptersView() {
        hideOverlays()
        overlayChapters.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        tvWindowTitle.text = "Chapters"

        currentBook?.totalChapters?.let { count ->
            listChapters.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            listChapters.adapter = ChapterAdapter(count)
            listChapters.scrollToPosition(currentChapterIndex)
        }
    }

    private fun openSettingsView() {
        hideOverlays()
        overlaySettings.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        tvWindowTitle.text = "Settings"
    }

    private fun setupListeners() {
        val topDragBar = floatingView.findViewById<View>(R.id.top_drag_bar)
        val resizeHandle = floatingView.findViewById<View>(R.id.resize_handle)
        
        // Tap outside to fold removed

        // Long press movement for bubble and top bar
        val dragListener = createLongPressDragListener()
        bubbleIcon.setOnTouchListener(dragListener)
        topDragBar.setOnTouchListener(dragListener)

        bubbleIcon.setOnClickListener { setFolded(false) }

        floatingView.findViewById<View>(R.id.btn_exit).setOnClickListener {
            saveCurrentPosition()
            stopSelf()
        }

        // Tap content to toggle Moonreader toolbar
        tvContent.setOnClickListener {
            val isVisible = toolbarContainer.visibility == View.VISIBLE
            toolbarContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

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

        // Toolbar Buttons
        floatingView.findViewById<View>(R.id.btn_prev).setOnClickListener { navigateChapter(-1) }
        floatingView.findViewById<View>(R.id.btn_next).setOnClickListener { navigateChapter(1) }
        floatingView.findViewById<View>(R.id.btn_chapters).setOnClickListener {
            openChaptersView()
        }
        floatingView.findViewById<View>(R.id.btn_minimize).setOnClickListener {
            setFolded(true)
        }
        floatingView.findViewById<View>(R.id.btn_exit_toolbar).setOnClickListener {
            saveCurrentPosition()
            stopSelf()
        }
        floatingView.findViewById<View>(R.id.btn_library).setOnClickListener {
            saveCurrentPosition()
            openLibraryView()
        }
        floatingView.findViewById<View>(R.id.btn_auto_scroll).setOnClickListener {
            isAutoScrolling = !isAutoScrolling
            if (isAutoScrolling) {
                Toast.makeText(this, "Auto-scroll ON", Toast.LENGTH_SHORT).show()
                scrollHandler.post(scrollRunnable)
            } else {
                Toast.makeText(this, "Auto-scroll OFF", Toast.LENGTH_SHORT).show()
                scrollHandler.removeCallbacks(scrollRunnable)
            }
        }
        floatingView.findViewById<View>(R.id.btn_search).setOnClickListener {
            Toast.makeText(this, "Search coming soon", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Logs saved to Downloads folder", Toast.LENGTH_LONG).show()
                AppLogger.export(this)
            }
        }
        
        floatingView.findViewById<Switch>(R.id.switch_bookmarks)?.setOnCheckedChangeListener { _, isChecked ->
            // Stub for bookmarks feature enablement
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

        floatingView.findViewById<View>(R.id.top_drag_bar).setOnClickListener {
            if (overlayLibrary.visibility == View.VISIBLE || overlayChapters.visibility == View.VISIBLE || overlaySettings.visibility == View.VISIBLE) {
                hideOverlays()
                currentBook?.let { 
                    tvWindowTitle.text = "${it.title} (Ch ${currentChapterIndex + 1}/${it.totalChapters})"
                }
            }
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
            currentBook = fetchedBook?.copy(lastOpenedTimestamp = System.currentTimeMillis())
            currentBook?.let {
                db.epubDao().updateBook(it)
                currentChapterIndex = it.lastReadChapter
                loadChapterText()
            }
        }
    }

    private fun loadChapterText() {
        val book = currentBook ?: return
        serviceScope.launch(Dispatchers.IO) {
            val bookDir = File(filesDir, "book_${book.id}")
            val chapterFile = File(bookDir, "chapter_$currentChapterIndex.txt")
            if (chapterFile.exists()) {
                val text = chapterFile.readText()
                withContext(Dispatchers.Main) {
                    chapterContent = text
                    renderChapter(book.lastReadScrollY)
                }
            } else {
                withContext(Dispatchers.Main) {
                    chapterContent = "Chapter content not found."
                    renderChapter(0)
                }
            }
        }
    }

    private fun renderChapter(scrollY: Int) {
        val book = currentBook ?: return
        tvWindowTitle.text = "${book.title} (Ch ${currentChapterIndex + 1}/${book.totalChapters})"
        
        tvContent.text = chapterContent
        val percent = if (book.totalChapters > 1) {
            (currentChapterIndex * 100) / (book.totalChapters - 1)
        } else {
            100
        }
        tvProgress.text = "$percent%"
        
        floatingView.findViewById<SeekBar>(R.id.seek_progress)?.progress = percent
        
        scrollView.post {
            scrollView.scrollTo(0, scrollY)
        }
    }

    private fun navigateChapter(offset: Int) {
        currentBook?.let { book ->
            val newIndex = currentChapterIndex + offset
            if (newIndex in 0 until book.totalChapters) {
                saveCurrentPosition() // save before swap
                currentChapterIndex = newIndex
                // aggressively update db memory
                val updated = book.copy(lastReadChapter = newIndex, lastReadScrollY = 0)
                currentBook = updated
                loadChapterText()
            }
        }
    }

    private fun saveCurrentPosition() {
        currentBook?.let {
            val updated = it.copy(lastReadChapter = currentChapterIndex, lastReadScrollY = lastKnownScrollY)
            serviceScope.launch(Dispatchers.IO) {
                AppDatabase.getDatabase(this@FloatingReaderService).epubDao().updateBook(updated)
            }
        }
    }

    private fun toggleTts() {
        if (!isTtsReady) {
            Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSpeaking) {
            tts.stop()
            isSpeaking = false
            btnTts.setImageResource(android.R.drawable.ic_media_play)
        } else {
            val chunks = chapterContent.chunked(3000)
            for (chunk in chunks) {
                tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, null)
            }
            isSpeaking = true
            btnTts.setImageResource(android.R.drawable.ic_media_pause)
        }
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
                            Toast.makeText(this@FloatingReaderService, "Book is still parsing...", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@FloatingReaderService, "Deleted ${book.title}", Toast.LENGTH_SHORT).show()
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

    private fun setFolded(folded: Boolean) {
        isFolded = folded
        if (folded) {
            bubbleIcon.visibility = View.VISIBLE
            windowContainer.visibility = View.GONE
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            isAutoScrolling = false // pause scroll
        } else {
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
        saveCurrentPosition()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        scrollHandler.removeCallbacks(scrollRunnable)
        serviceScope.cancel()
        if (::windowManager.isInitialized && ::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }
}
