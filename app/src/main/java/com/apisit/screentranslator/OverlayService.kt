package com.apisit.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var held: Image? = null // เฟรมล่าสุดของหน้าจอ
    private var capW = 0
    private var capH = 0
    private var dpi = 0

    private var button: TextView? = null
    private var resultLayer: FrameLayout? = null
    private var busy = false
    private var cjk = false

    // โหมดแปลต่อเนื่อง (ซับไตเติล/คัทซีน)
    private var liveMode = true
    private var scanPos = 0 // 0 = ล่าง, 1 = กลาง, 2 = บน
    private var liveOn = false
    private var bar: TextView? = null
    private var shownText = ""
    private var pendingText = ""
    private var pendingCount = 0
    private var votes = 2 // 2 = ข้อความยาวแปลทันที ข้อความสั้นยืนยัน 2 รอบ, 1 = แปลทันทีทุกกรณี
    private var game = 0 // 0 = ทั่วไป, 1 = KOTOR 2
    private var engine = 0 // 0 = ML Kit + คลังคำศัพท์, 1 = Claude API
    private var apiKey = ""
    private var tgtCode = ""
    private var tgtName = "Thai"
    private var errShown = false
    private val history = ArrayDeque<String>()
    private var scanFrac = 0.4f
    private val worker = Executors.newSingleThreadExecutor()
    private var emptyCount = 0
    private val btnBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF1E88E5.toInt())
    }

    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || projection != null) return START_NOT_STICKY
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val srcCode = intent.getStringExtra("src") ?: TranslateLanguage.ENGLISH
        val tgtCode0 = intent.getStringExtra("tgt") ?: TranslateLanguage.THAI
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        cjk = srcCode == TranslateLanguage.JAPANESE || srcCode == TranslateLanguage.CHINESE
        liveMode = intent.getBooleanExtra("live", true)
        scanPos = intent.getIntExtra("pos", 0)
        votes = intent.getIntExtra("votes", 2)
        game = intent.getIntExtra("game", 0)
        engine = intent.getIntExtra("engine", 0)
        apiKey = intent.getStringExtra("key") ?: ""
        tgtCode = tgtCode0
        tgtName = when (tgtCode0) {
            TranslateLanguage.THAI -> "Thai"
            TranslateLanguage.ENGLISH -> "English"
            TranslateLanguage.JAPANESE -> "Japanese"
            TranslateLanguage.CHINESE -> "Chinese"
            TranslateLanguage.KOREAN -> "Korean"
            else -> tgtCode0
        }
        if (game == 1) {
            scanPos = 0 // กล่องบทสนทนาของ KOTOR อยู่ล่างจอ
            scanFrac = 0.5f
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)
        projection = p

        recognizer = when (srcCode) {
            TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(tgtCode0)
                .build()
        )
        translator = tr
        tr.downloadModelIfNeeded()
            .addOnSuccessListener { toast("โหลดโมเดลภาษาเรียบร้อย พร้อมแปลแล้ว") }
            .addOnFailureListener { toast("ต้องต่อเน็ตครั้งแรกเพื่อโหลดโมเดลภาษา") }

        setupDisplay()
        addButton()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("st", "Screen Translator", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "st")
            .setContentTitle("Screen Translator ทำงานอยู่")
            .setContentText("แตะปุ่ม \"แปล\" ที่ลอยอยู่ • กดค้างที่ปุ่มเพื่อปิด")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- จับภาพหน้าจอ ----------
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun setupDisplay() {
        val dm = realMetrics()
        capW = dm.widthPixels
        capH = dm.heightPixels
        dpi = dm.densityDpi
        held?.close()
        held = null
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                held?.close()
                held = img
            }
        }, handler)
        reader = r
        val vd = vdisplay
        if (vd == null) {
            vdisplay = projection!!.createVirtualDisplay(
                "screen-translator", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
        } else {
            vd.resize(capW, capH, dpi)
            vd.surface = r.surface
        }
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) setupDisplay()
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val buf = p.buffer
        buf.rewind()
        val rowPadding = p.rowStride - p.pixelStride * img.width
        val bmp = Bitmap.createBitmap(
            img.width + rowPadding / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    // ---------- ปุ่มลอย ----------
    private fun addButton() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val tv = TextView(this).apply {
            text = "แปล"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            alpha = 0.85f
            background = btnBg
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (16 * d).toInt()
        lp.y = (160 * d).toInt()

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        var downAt = 0L
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    moved = false
                    downAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (SystemClock.uptimeMillis() - downAt > 900) stopSelf() else onTap()
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    // ---------- แปล ----------
    private fun onTap() {
        if (liveMode) {
            toggleLive()
            return
        }
        if (busy) return
        if (resultLayer != null) {
            clearResult()
            return
        }
        val dm = realMetrics()
        if (dm.widthPixels != capW || dm.heightPixels != capH) setupDisplay()
        busy = true
        button?.visibility = View.INVISIBLE // ซ่อนปุ่มก่อนจับภาพ จะได้ไม่ติดไปใน OCR
        handler.postDelayed({ grabAndTranslate() }, 450)
    }

    // ---------- แปลต่อเนื่อง ----------
    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveOn) return
            scanOnce()
            handler.postDelayed(this, 400)
        }
    }

    private fun toggleLive() {
        liveOn = !liveOn
        btnBg.setColor(if (liveOn) 0xFF2E7D32.toInt() else 0xFF1E88E5.toInt())
        button?.text = if (liveOn) "■" else "แปล"
        button?.alpha = if (liveOn) 0.5f else 0.85f
        handler.removeCallbacks(liveTick)
        if (liveOn) {
            ensureBar()
            shownText = ""
            pendingText = ""
            pendingCount = 0
            emptyCount = 0
            busy = false
            toast("เริ่มแปลต่อเนื่อง แตะปุ่มอีกครั้งเพื่อหยุด")
            handler.postDelayed(liveTick, 300)
        } else {
            bar?.visibility = View.INVISIBLE
        }
    }

    // แถบคำแปลอยู่ฝั่งตรงข้ามกับพื้นที่ที่สแกน จึงไม่ถูกอ่านซ้ำ และไม่รับการสัมผัส (เกมกดได้ตามปกติ)
    private fun ensureBar() {
        if (bar != null) return
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 18f
            maxLines = if (scanPos == 1) 3 else 5
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            visibility = View.INVISIBLE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = (if (scanPos == 2) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        wm.addView(tv, lp)
        bar = tv
    }

    private fun scanOnce() {
        if (busy) return
        val dm = realMetrics()
        if (dm.widthPixels != capW || dm.heightPixels != capH) {
            setupDisplay()
            return
        }
        val img = held ?: return
        val rec = recognizer ?: return
        val full = try {
            toBitmap(img)
        } catch (e: Exception) {
            return
        }
        val h = (full.height * scanFrac).toInt()
        val y0 = when (scanPos) {
            0 -> full.height - h
            1 -> (full.height * 0.35f).toInt()
            else -> 0
        }
        val crop = Bitmap.createBitmap(full, 0, y0, full.width, h)
        full.recycle()
        busy = true
        worker.execute {
            val text = try {
                bestText(rec, crop)
            } catch (e: Exception) {
                ""
            }
            crop.recycle()
            handler.post { onScanResult(text) }
        }
    }

    private fun strongScore(): Double = if (cjk) 6.0 else 20.0

    // อ่านหลายแบบ แล้วเลือกผลที่ดูเป็นข้อความจริงที่สุด
    // ถ้าภาพเดิมอ่านชัดอยู่แล้วจะไม่ลองแบบอื่น (เร็วขึ้น)
    private fun bestText(rec: TextRecognizer, crop: Bitmap): String {
        val w = crop.width
        val h = crop.height
        var best = ""
        var bestScore = 0.0
        fun consider(bmp: Bitmap) {
            val t = ocr(rec, bmp)
            val sc = score(t)
            if (sc > bestScore) {
                best = t
                bestScore = sc
            }
        }
        consider(crop)
        if (bestScore >= strongScore() * 2) return best
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        val modes = if (game == 1) intArrayOf(2) else intArrayOf(0, 1)
        for (m in modes) {
            val bmp = maskBitmap(px, w, h, m)
            consider(bmp)
            bmp.recycle()
        }
        return if (bestScore >= 4.0) best else ""
    }

    private fun ocr(rec: TextRecognizer, bmp: Bitmap): String {
        val t = try {
            Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return ""
        }
        val sep = if (cjk) "" else " "
        return t.textBlocks
            .sortedBy { it.boundingBox?.top ?: 0 }
            .joinToString(sep) { b -> b.lines.joinToString(sep) { it.text } }
            .trim()
    }

    // ให้คะแนนว่าข้อความที่อ่านได้ "ดูเป็นคำพูดจริง" แค่ไหน (ขยะจากพื้นหลังลายจะได้คะแนนต่ำ)
    private fun score(s: String): Double {
        val t = s.filter { !it.isWhitespace() }
        if (t.length < (if (cjk) 2 else 4)) return 0.0
        val valid = t.count { it.isLetterOrDigit() }
        val ratio = valid.toDouble() / t.length
        if (ratio < 0.7) return 0.0
        var sc = valid * ratio * ratio
        if (!cjk) {
            val tokens = s.split(' ').filter { it.isNotEmpty() }
            if (tokens.none { it.length >= 3 }) return 0.0
            val shortTok = tokens.count { it.length == 1 && it != "I" && it != "a" && it != "A" }
            sc -= shortTok * 3.0
        }
        return max(0.0, sc)
    }

    // เก็บเฉพาะพิกเซลที่ "สีตัวอักษรซับ" (ขาว/เหลือง/สว่างทุกสี) และมีขอบ/เงาสีเข้มอยู่ใกล้ ๆ
    // ผลลัพธ์: ตัวอักษรสีดำบนพื้นขาว ตัดพื้นหลังลายและสว่างทิ้ง
    private fun maskBitmap(px: IntArray, w: Int, h: Int, mode: Int): Bitmap {
        val n = w * h
        val cand = BooleanArray(n)
        val dark = BooleanArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val y = (r * 299 + g * 587 + b * 114) / 1000
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            dark[i] = y < 80
            cand[i] = when (mode) {
                0 -> y >= 190 && mx - mn <= 60                       // ขาว
                1 -> r >= 190 && g >= 160 && b <= 130 && r - b >= 80 // เหลือง
                else -> y >= 140                                      // สว่างทุกสี (กล่องบทสนทนาเกม)
            }
        }
        val rad = if (mode == 2) 6 else 4
        val near = dilate(dark, w, h, rad)
        val out = IntArray(n)
        for (i in 0 until n) out[i] = if (cand[i] && near[i]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ขยายพื้นที่สีเข้มออกรอบละ rad พิกเซล (แยกแนวนอน/แนวตั้งด้วย prefix sum)
    private fun dilate(src: BooleanArray, w: Int, h: Int, rad: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        val ps = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) ps[x + 1] = ps[x] + (if (src[base + x]) 1 else 0)
            for (x in 0 until w) {
                tmp[base + x] = ps[min(w, x + rad + 1)] - ps[max(0, x - rad)] > 0
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) ps[y + 1] = ps[y] + (if (tmp[y * w + x]) 1 else 0)
            for (y in 0 until h) {
                out[y * w + x] = ps[min(h, y + rad + 1)] - ps[max(0, y - rad)] > 0
            }
        }
        return out
    }

    // ยืนยันผลจากหลายรอบก่อนแปล เพื่อกรองข้อความขยะที่โผล่มารอบเดียว
    private fun onScanResult(s: String) {
        if (!liveOn) {
            busy = false
            return
        }
        if (s.isEmpty()) {
            busy = false
            pendingText = ""
            pendingCount = 0
            emptyCount++
            if (emptyCount >= 3) {
                bar?.visibility = View.INVISIBLE
                shownText = ""
            }
            return
        }
        emptyCount = 0
        if (shownText.isNotEmpty() && similar(s, shownText)) {
            busy = false
            return
        }
        if (pendingText.isNotEmpty() && similar(s, pendingText)) {
            pendingCount++
            if (score(s) > score(pendingText)) pendingText = s
        } else {
            pendingText = s
            pendingCount = 1
        }
        val strong = score(s) >= strongScore()
        if (pendingCount < votes && !(votes > 1 && strong)) {
            busy = false
            return
        }
        val text = pendingText
        shownText = text
        pendingText = ""
        pendingCount = 0
        translateText(text) { t ->
            if (t != null && liveOn) {
                bar?.text = t
                bar?.visibility = View.VISIBLE
                history.addLast(text)
                while (history.size > 4) history.removeFirst()
            }
            busy = false
        }
    }

    // เลือกตัวแปล: Claude API (ถ้าเปิดใช้) ถ้าไม่สำเร็จจะถอยไปใช้ ML Kit
    private fun translateText(text: String, done: (String?) -> Unit) {
        if (engine == 1 && apiKey.isNotBlank()) {
            val ctx = history.toList()
            val useG = tgtCode == TranslateLanguage.THAI
            worker.execute {
                val r = ClaudeTranslate.translate(apiKey, text, ctx, tgtName, game, useG)
                handler.post {
                    if (r != null) {
                        done(r)
                    } else {
                        if (!errShown) {
                            errShown = true
                            toast("Claude API ใช้ไม่ได้ (" + ClaudeTranslate.lastError + ") ใช้ ML Kit แทน")
                        }
                        mlTranslate(text, done)
                    }
                }
            }
        } else {
            mlTranslate(text, done)
        }
    }

    // ML Kit + คลังคำศัพท์ (เฉพาะแปลเป็นไทย)
    private fun mlTranslate(text: String, done: (String?) -> Unit) {
        val tr = translator
        if (tr == null) {
            done(null)
            return
        }
        val useG = tgtCode == TranslateLanguage.THAI && game == 1
        val input = if (useG) Glossary.protect(text) else text
        tr.downloadModelIfNeeded()
            .addOnSuccessListener {
                tr.translate(input)
                    .addOnSuccessListener { t -> done(if (useG) Glossary.restore(t) else t) }
                    .addOnFailureListener { done(null) }
            }
            .addOnFailureListener {
                toast("ต้องต่อเน็ตเพื่อโหลดโมเดลภาษาก่อน")
                done(null)
            }
    }

    private fun similar(a: String, b: String): Boolean {
        val x = a.take(200)
        val y = b.take(200)
        val m = max(x.length, y.length)
        if (m == 0) return true
        val need = if (m < 30) 0.88 else 0.75 // ประโยคสั้นต้องเหมือนกันมากขึ้น กันพลาดตอนเปลี่ยนบท
        return 1.0 - lev(x, y).toDouble() / m >= need
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun grabAndTranslate() {
        val img = held
        val bmp = try {
            img?.let { toBitmap(it) }
        } catch (e: Exception) {
            null
        }
        button?.visibility = View.VISIBLE
        if (bmp == null) {
            busy = false
            toast("ยังไม่ได้ภาพหน้าจอ ลองแตะอีกครั้ง")
            return
        }
        val rec = recognizer
        val tr = translator
        if (rec == null || tr == null) {
            busy = false
            return
        }
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val blocks = text.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
                if (blocks.isEmpty()) {
                    busy = false
                    toast("ไม่พบข้อความบนหน้าจอ")
                    return@addOnSuccessListener
                }
                tr.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        val layer = createLayer()
                        var pending = blocks.size
                        for (b in blocks) {
                            val joined = b.lines.joinToString(if (cjk) "" else " ") { it.text }
                            mlTranslate(joined) { t ->
                                if (t != null) addBox(layer, b, t)
                                pending--
                                if (pending == 0) busy = false
                            }
                        }
                    }
                    .addOnFailureListener {
                        busy = false
                        toast("ต้องต่อเน็ตเพื่อโหลดโมเดลภาษาก่อน")
                    }
            }
            .addOnFailureListener {
                busy = false
                toast("อ่านข้อความไม่สำเร็จ: ${it.message}")
            }
    }

    private fun createLayer(): FrameLayout {
        val layer = FrameLayout(this)
        layer.setOnClickListener { clearResult() }
        val lp = WindowManager.LayoutParams(
            capW, capH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(layer, lp)
        resultLayer = layer
        return layer
    }

    private fun addBox(layer: FrameLayout, b: Text.TextBlock, translated: String) {
        val r = b.boundingBox ?: return
        val tv = TextView(this)
        tv.text = translated
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(0xDD000000.toInt())
        val lines = max(1, b.lines.size)
        val px = (r.height().toFloat() / lines * 0.75f).coerceIn(22f, 46f)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        tv.setPadding(8, 4, 8, 4)
        val w = max(r.width() + 16, (capW * 0.25f).toInt()).coerceAtMost(capW)
        val lp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = r.left.coerceIn(0, max(0, capW - w))
        lp.topMargin = r.top.coerceIn(0, max(0, capH - 20))
        layer.addView(tv, lp)
    }

    private fun clearResult() {
        resultLayer?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        resultLayer = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        liveOn = false
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        bar?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bar = null
        clearResult()
        button?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        button = null
        held?.close()
        held = null
        vdisplay?.release()
        reader?.close()
        projection?.stop()
        recognizer?.close()
        translator?.close()
        super.onDestroy()
    }
}
