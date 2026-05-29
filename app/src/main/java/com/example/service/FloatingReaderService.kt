package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.AppDatabase
import com.example.data.EpubBook
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max

class FloatingReaderService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private var isFolded = true
    
    // Default window dimensions
    private var savedWindowWidth = 800
    private var savedWindowHeight = 1200
    
    // Dragging state
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    // Resize state
    private var initialWidth: Int = 0
    private var initialHeight: Int = 0

    // Reader state
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var database: AppDatabase
    private var currentBook: EpubBook? = null
    private var currentChapterIndex: Int = 0
    
    // UI Refs
    private lateinit var tvTitle: TextView
    private lateinit var tvContent: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var bottomControls: LinearLayout
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    
    // Chapter sliding window (current +- 5 chapters) buffer
    private val chapterCache = mutableMapOf<Int, String>()

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bookId = intent?.getIntExtra("BOOK_ID", -1) ?: -1
        if (bookId != -1) {
            loadBook(bookId)
            setFolded(false)
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val channelId = "floating_reader_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Reader Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LiteReader")
            .setContentText("Floating reader is active")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()
            
        startForeground(1, notification)

        database = AppDatabase.getDatabase(this)
        setupFloatingView()
    }

    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_reader, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 100

        windowManager.addView(floatingView, layoutParams)

        tvTitle = floatingView.findViewById(R.id.tv_title)
        tvContent = floatingView.findViewById(R.id.tv_content)
        scrollView = floatingView.findViewById(R.id.scroll_view)
        bottomControls = floatingView.findViewById(R.id.bottom_controls)
        btnPrev = floatingView.findViewById(R.id.btn_prev)
        btnNext = floatingView.findViewById(R.id.btn_next)
        
        tvContent.typeface = Typeface.SERIF

        // Setup folded state toggle
        val bubbleIcon = floatingView.findViewById<ImageView>(R.id.bubble_icon)
        val titleBar = floatingView.findViewById<View>(R.id.title_bar)
        val btnClose = floatingView.findViewById<ImageView>(R.id.btn_close)
        val resizeHandle = floatingView.findViewById<View>(R.id.resize_handle)

        // Handle touch outside to fold
        floatingView.setOnTouchListener { _, event ->
            if (!isFolded && event.action == MotionEvent.ACTION_OUTSIDE) {
                setFolded(true)
                return@setOnTouchListener true
            }
            false
        }

        // Tap bubble to open
        bubbleIcon.setOnClickListener {
            setFolded(false)
        }
        
        // Tap close to fold
        btnClose.setOnClickListener {
            saveCurrentPosition()
            setFolded(true)
        }

        // Tap content to toggle bottom controls
        tvContent.setOnClickListener {
            val isControlsVisible = bottomControls.visibility == View.VISIBLE
            bottomControls.visibility = if (isControlsVisible) View.GONE else View.VISIBLE
        }

        btnPrev.setOnClickListener { navigateToChapter(currentChapterIndex - 1) }
        btnNext.setOnClickListener { navigateToChapter(currentChapterIndex + 1) }

        // Track scrolling to save position
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                // Basic debounce or immediate save if lightweight enough
                currentBook?.let { book ->
                    // Note: doing this on every pixel scroll might be slightly heavy, but PRD asks to save constantly
                    // we'll update local variables, and save to DB occasionally or on fold.
                    bookProgressCache[book.id] = scrollY
                }
            }
        }

        // Drag bubble or title bar
        val dragListener = View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val xDiff = Math.abs(event.rawX - initialTouchX)
                    val yDiff = Math.abs(event.rawY - initialTouchY)
                    if (xDiff < 10 && yDiff < 10) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleIcon.setOnTouchListener(dragListener)
        titleBar.setOnTouchListener(dragListener)

        // Resize handle
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = layoutParams.width
                    initialHeight = layoutParams.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = initialWidth + (event.rawX - initialTouchX).toInt()
                    val h = initialHeight + (event.rawY - initialTouchY).toInt()
                    layoutParams.width = max(400, w)
                    layoutParams.height = max(400, h)
                    savedWindowWidth = layoutParams.width
                    savedWindowHeight = layoutParams.height
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }

        // Initialize folded
        setFolded(true)
    }

    private val bookProgressCache = mutableMapOf<Int, Int>()

    private fun loadBook(bookId: Int) {
        serviceScope.launch {
            val book = withContext(Dispatchers.IO) { database.epubDao().getBookById(bookId) }
            if (book != null && book.isParsed) {
                currentBook = book
                currentChapterIndex = book.lastReadChapter
                loadChaptersIntoCache(currentChapterIndex)
                renderCurrentChapter(book.lastReadProgress)
            } else {
                tvTitle.text = book?.title ?: "Unknown Book"
                tvContent.text = if (book?.isParsed != true) "Book is still parsing..." else "Book not found."
            }
        }
    }

    private suspend fun loadChaptersIntoCache(centerIdx: Int) = withContext(Dispatchers.IO) {
        val book = currentBook ?: return@withContext
        val bookDir = File(filesDir, "book_${book.id}")
        
        // Evict far chapters
        val toKeep = (centerIdx - 5..centerIdx + 5)
        chapterCache.keys.retainAll(toKeep)

        // Load missing
        for (i in toKeep) {
            if (i >= 0 && i < book.totalChapters && !chapterCache.containsKey(i)) {
                val chapterFile = File(bookDir, "chapter_$i.txt")
                if (chapterFile.exists()) {
                    chapterCache[i] = chapterFile.readText()
                }
            }
        }
    }

    private fun renderCurrentChapter(savedScrollY: Int = 0) {
        val book = currentBook ?: return
        tvTitle.text = "${book.title} (Ch ${currentChapterIndex + 1}/${book.totalChapters})"
        tvContent.text = chapterCache[currentChapterIndex] ?: "Chapter content not loaded."
        
        btnPrev.isEnabled = currentChapterIndex > 0
        btnNext.isEnabled = currentChapterIndex < book.totalChapters - 1
        
        // Restore scroll position after layout
        scrollView.post {
            scrollView.scrollTo(0, savedScrollY)
        }
    }

    private fun navigateToChapter(newIdx: Int) {
        val book = currentBook ?: return
        if (newIdx in 0 until book.totalChapters) {
            saveCurrentPosition() // Save current before switching
            currentChapterIndex = newIdx
            tvContent.text = "Loading..."
            scrollView.scrollTo(0, 0)
            
            serviceScope.launch {
                loadChaptersIntoCache(currentChapterIndex)
                renderCurrentChapter(0)
                saveCurrentPosition() // Save new chapter index
            }
        }
    }

    private fun saveCurrentPosition() {
        val book = currentBook ?: return
        val currentScrollY = bookProgressCache[book.id] ?: scrollView.scrollY
        val updatedBook = book.copy(
            lastReadChapter = currentChapterIndex,
            lastReadProgress = currentScrollY,
            lastReadTime = System.currentTimeMillis()
        )
        currentBook = updatedBook
        serviceScope.launch(Dispatchers.IO) {
            database.epubDao().insertBook(updatedBook)
        }
    }

    private fun setFolded(folded: Boolean) {
        this.isFolded = folded
        if (folded) {
            floatingView.findViewById<View>(R.id.bubble_icon).visibility = View.VISIBLE
            floatingView.findViewById<View>(R.id.window_container).visibility = View.GONE
            
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowManager.updateViewLayout(floatingView, layoutParams)
        } else {
            floatingView.findViewById<View>(R.id.bubble_icon).visibility = View.GONE
            floatingView.findViewById<View>(R.id.window_container).visibility = View.VISIBLE
            
            layoutParams.width = savedWindowWidth
            layoutParams.height = savedWindowHeight
            
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                 WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                 WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                 
            windowManager.updateViewLayout(floatingView, layoutParams)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        serviceScope.cancel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
