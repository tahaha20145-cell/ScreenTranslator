package com.screentranslator

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlin.math.hypot

/**
 * ปุ่มลอย + จับภาพหน้าจอ + OCR + แปลภาษา + วางคำแปลทับตำแหน่งข้อความเดิม
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_CONTINUOUS = "continuous"

        private const val ACTION_STOP = "com.screentranslator.STOP"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        private const val CONTINUOUS_INTERVAL_MS = 1500L
        private const val MIN_CAPTURE_DELAY_MS = 100L
        private const val MAX_CAPTURE_WAIT_MS = 500L
        private const val LONG_PRESS_MS = 1000L
        private const val MAX_CACHE = 500

        private const val COLOR_IDLE = 0xFF1E88E5.toInt()
        private const val COLOR_RUNNING = 0xFF43A047.toInt()
        private const val COLOR_BUSY = 0xFFFB8C00.toInt()
    }

    private class Block(val box: Rect, val source: String, val lines: Int) {
        var translated: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private val density: Float get() = resources.displayMetrics.density

    // จับภาพหน้าจอ
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageThread = HandlerThread("capture").apply { start() }
    private val imageLock = Any()
    private var latestImage: Image? = null
    @Volatile private var frameCount = 0L

    // OCR + แปล
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var modelReady = false
    private val cache = HashMap<String, String>()
    private var lastText = ""

    // หน้าต่างลอย
    private lateinit var button: TextView
    private lateinit var buttonBackground: GradientDrawable
    private lateinit var buttonParams: WindowManager.LayoutParams
    private lateinit var resultLayer: FrameLayout
    private lateinit var resultParams: WindowManager.LayoutParams

    private var continuous = true
    private var running = false
    private var busy = false
    private var destroyed = false

    private val loopRunnable = Runnable { if (running) captureAndTranslate() }
    private val longPressRunnable = Runnable {
        toast(R.string.closed)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        readScreenSize()
        createResultLayer()
        createButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (projection != null) {
            toast(R.string.already_running)
            return START_NOT_STICKY
        }

        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (intent == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val languages = SourceLanguage.values()
        val language = languages[intent.getIntExtra(EXTRA_LANGUAGE, 0).coerceIn(languages.indices)]
        continuous = intent.getBooleanExtra(EXTRA_CONTINUOUS, true)

        if (!startProjection(resultCode, data)) {
            stopSelf()
            return START_NOT_STICKY
        }
        setUpModels(language)
        windowManager.addView(resultLayer, resultParams)
        windowManager.addView(button, buttonParams)
        updateButtonColor()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        main.removeCallbacksAndMessages(null)
        for (view in listOf<View>(button, resultLayer)) {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
        virtualDisplay?.release()
        projection?.stop()
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        imageReader?.close()
        imageThread.quitSafely()
        recognizer?.close()
        translator?.close()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        readScreenSize()
        if (screenWidth == oldWidth && screenHeight == oldHeight) return

        // หมุนจอ (เช่นเกมแนวนอน) ต้องปรับขนาดภาพที่จับใหม่ ไม่งั้นตำแหน่งคำแปลจะเพี้ยน
        virtualDisplay?.let { display ->
            val oldReader = imageReader
            val newReader = createImageReader()
            imageReader = newReader
            display.resize(screenWidth, screenHeight, densityDpi)
            display.surface = newReader.surface
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
            oldReader?.close()
        }
        clearResults()
        if (button.isAttachedToWindow) {
            buttonParams.x = buttonParams.x.coerceIn(0, (screenWidth - buttonParams.width).coerceAtLeast(0))
            buttonParams.y = buttonParams.y.coerceIn(0, (screenHeight - buttonParams.height).coerceAtLeast(0))
            windowManager.updateViewLayout(button, buttonParams)
        }
    }

    // ---------- เริ่มต้น ----------

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
    }

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        densityDpi = resources.displayMetrics.densityDpi
    }

    private fun startProjection(resultCode: Int, data: Intent): Boolean {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data) ?: return false
        projection = mp
        // Android 14 ขึ้นไปต้อง registerCallback ก่อน createVirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!destroyed) stopSelf()
            }
        }, main)
        val reader = createImageReader()
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "ScreenTranslator", screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
        return true
    }

    private fun createImageReader(): ImageReader {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = image
                frameCount++
            }
        }, Handler(imageThread.looper))
        return reader
    }

    private fun setUpModels(language: SourceLanguage) {
        recognizer = language.createRecognizer()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(language.translateCode)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
        val tr = Translation.getClient(options)
        translator = tr
        toast(R.string.model_downloading)
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (destroyed) return@addOnSuccessListener
                modelReady = true
                toast(R.string.model_ready)
            }
            .addOnFailureListener {
                if (destroyed) return@addOnFailureListener
                toast(R.string.model_failed)
                stopSelf()
            }
    }

    // ---------- หน้าต่างลอย ----------

    private fun createResultLayer() {
        resultLayer = FrameLayout(this).apply {
            setOnClickListener { if (!continuous) clearResults() }
        }
        resultParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12 ขึ้นไปจะบล็อกการแตะทะลุ overlay ที่ทึบเกิน 0.8 ทำให้เล่นเกมไม่ได้
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createButton() {
        val size = (56 * density).toInt()
        buttonBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
            setStroke((2 * density).toInt(), Color.WHITE)
        }
        button = TextView(this).apply {
            text = getString(R.string.button_label)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = buttonBackground
            elevation = 6 * density
        }
        buttonParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - (16 * density).toInt()
            y = screenHeight / 3
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        button.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = buttonParams.x
                        startY = buttonParams.y
                        dragging = false
                        main.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && hypot(dx, dy) > touchSlop) {
                            dragging = true
                            main.removeCallbacks(longPressRunnable)
                        }
                        if (dragging) {
                            buttonParams.x = startX + dx.toInt()
                            buttonParams.y = startY + dy.toInt()
                            windowManager.updateViewLayout(button, buttonParams)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longPressRunnable)
                        if (!dragging) onButtonTap()
                    }
                    MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPressRunnable)
                }
                return true
            }
        })
    }

    private fun onButtonTap() {
        if (destroyed) return
        if (!modelReady) {
            toast(R.string.model_downloading)
            return
        }
        if (continuous) {
            running = !running
            if (running) {
                captureAndTranslate()
            } else {
                main.removeCallbacks(loopRunnable)
                clearResults()
            }
        } else if (resultLayer.childCount > 0) {
            clearResults()
        } else {
            captureAndTranslate()
        }
        updateButtonColor()
    }

    private fun updateButtonColor() {
        buttonBackground.setColor(
            when {
                running -> COLOR_RUNNING
                busy -> COLOR_BUSY
                else -> COLOR_IDLE
            }
        )
    }

    private fun setResultTouchable(touchable: Boolean) {
        val flags = if (touchable) {
            resultParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            resultParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == resultParams.flags) return
        resultParams.flags = flags
        if (resultLayer.isAttachedToWindow) windowManager.updateViewLayout(resultLayer, resultParams)
    }

    private fun clearResults() {
        resultLayer.removeAllViews()
        setResultTouchable(false)
        lastText = ""
    }

    // ---------- จับภาพ -> OCR -> แปล ----------

    private fun captureAndTranslate() {
        if (busy || destroyed) return
        busy = true
        updateButtonColor()
        // ซ่อนคำแปลเดิมก่อนจับภาพ ไม่งั้นจะ OCR คำแปลของตัวเองซ้ำ
        val needFreshFrame = resultLayer.childCount > 0
        resultLayer.visibility = View.INVISIBLE
        waitForFrame(frameCount, needFreshFrame, SystemClock.uptimeMillis())
    }

    private fun waitForFrame(startCount: Long, needFreshFrame: Boolean, startTime: Long) {
        main.postDelayed({
            if (destroyed) return@postDelayed
            val elapsed = SystemClock.uptimeMillis() - startTime
            val fresh = !needFreshFrame || frameCount > startCount
            if ((fresh && elapsed >= MIN_CAPTURE_DELAY_MS) || elapsed >= MAX_CAPTURE_WAIT_MS) {
                processFrame()
            } else {
                waitForFrame(startCount, needFreshFrame, startTime)
            }
        }, 50)
    }

    private fun takeScreenshot(): Bitmap? = synchronized(imageLock) {
        val image = latestImage ?: return null
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    private fun processFrame() {
        val bitmap = takeScreenshot()
        val rec = recognizer
        if (bitmap == null || rec == null) {
            finishPass(null)
            return
        }
        rec.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> if (!destroyed) translateBlocks(text) }
            .addOnFailureListener { finishPass(null) }
    }

    private fun translateBlocks(text: Text) {
        val loc = IntArray(2)
        button.getLocationOnScreen(loc)
        val buttonRect = Rect(loc[0], loc[1], loc[0] + button.width, loc[1] + button.height)

        val blocks = text.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val source = block.text.replace('\n', ' ').trim()
            if (source.length < 2 || Rect.intersects(box, buttonRect)) null
            else Block(box, source, block.lines.size)
        }

        // โหมดต่อเนื่อง: ข้อความบนจอเหมือนเดิม ไม่ต้องแปลใหม่
        val key = blocks.joinToString("\n") { it.source }
        if (continuous && key == lastText) {
            finishPass(null)
            return
        }
        lastText = key

        val tr = translator
        if (blocks.isEmpty() || tr == null) {
            if (!continuous) toast(R.string.no_text)
            finishPass(emptyList())
            return
        }
        if (cache.size > MAX_CACHE) cache.clear()

        var remaining = blocks.size
        val onDone: () -> Unit = { if (--remaining == 0) finishPass(blocks) }
        for (block in blocks) {
            val cached = cache[block.source]
            if (cached != null) {
                block.translated = cached
                onDone()
            } else {
                tr.translate(block.source)
                    .addOnSuccessListener {
                        block.translated = it
                        cache[block.source] = it
                    }
                    .addOnCompleteListener { onDone() }
            }
        }
    }

    /** blocks = null แปลว่าคงคำแปลเดิมไว้ */
    private fun finishPass(blocks: List<Block>?) {
        if (destroyed) return
        busy = false
        if (continuous && !running) {
            // ผู้ใช้กดหยุดระหว่างกำลังแปล
            clearResults()
        } else if (blocks != null) {
            showResults(blocks)
            if (!continuous) setResultTouchable(resultLayer.childCount > 0)
        }
        resultLayer.visibility = View.VISIBLE
        updateButtonColor()
        if (running) main.postDelayed(loopRunnable, CONTINUOUS_INTERVAL_MS)
    }

    private fun showResults(blocks: List<Block>) {
        resultLayer.removeAllViews()
        val origin = IntArray(2)
        resultLayer.getLocationOnScreen(origin)
        val minWidth = (120 * density).toInt()
        val pad = (4 * density).toInt()

        for (block in blocks) {
            val translated = block.translated ?: continue
            val lineHeight = block.box.height().toFloat() / block.lines.coerceAtLeast(1)
            val view = TextView(this).apply {
                text = translated
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6000000.toInt())
                setPadding(pad, pad / 2, pad, pad / 2)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (lineHeight * 0.75f).coerceIn(12 * density, 28 * density))
            }
            val width = block.box.width().coerceAtLeast(minWidth).coerceAtMost(screenWidth)
            val params = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (block.box.left - origin[0]).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
                topMargin = (block.box.top - origin[1]).coerceAtLeast(0)
            }
            resultLayer.addView(view, params)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
