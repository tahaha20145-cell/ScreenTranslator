package com.apisit.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage

class MainActivity : Activity() {

    private val reqCapture = 1

    private val srcNames = listOf("อังกฤษ / ละติน", "ญี่ปุ่น", "จีน", "เกาหลี")
    private val srcCodes = listOf(
        TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE,
        TranslateLanguage.CHINESE, TranslateLanguage.KOREAN
    )
    private val tgtNames = listOf("ไทย", "อังกฤษ", "ญี่ปุ่น", "จีน", "เกาหลี")
    private val tgtCodes = listOf(
        TranslateLanguage.THAI, TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE,
        TranslateLanguage.CHINESE, TranslateLanguage.KOREAN
    )

    private lateinit var src: Spinner
    private lateinit var tgt: Spinner
    private lateinit var mode: Spinner
    private lateinit var pos: Spinner
    private lateinit var acc: Spinner
    private lateinit var game: Spinner
    private lateinit var engine: Spinner
    private lateinit var keyBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Screen Translator"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "แปลข้อความบนหน้าจอเกมเป็นภาษาไทย (ลอยทับเกม)"
            setPadding(0, 0, 0, pad)
        })

        root.addView(TextView(this).apply { text = "เกม" })
        game = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("ทั่วไป (ซับไตเติลทั่วไป)", "Star Wars: KOTOR 2 (บทสนทนาล่างจอ)")
            )
        }
        root.addView(game)

        root.addView(TextView(this).apply { text = "ภาษาในเกม"; setPadding(0, pad / 2, 0, 0) })
        src = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, srcNames)
        }
        root.addView(src)

        root.addView(TextView(this).apply { text = "แปลเป็น"; setPadding(0, pad / 2, 0, 0) })
        tgt = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tgtNames)
        }
        root.addView(tgt)

        root.addView(TextView(this).apply { text = "โหมด"; setPadding(0, pad / 2, 0, 0) })
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("แปลต่อเนื่อง (ซับไตเติล / คัทซีน)", "แตะแปลทั้งจอ ทีละครั้ง")
            )
        }
        root.addView(mode)

        root.addView(TextView(this).apply { text = "ตำแหน่งซับไตเติลในเกม (โหมดต่อเนื่อง)"; setPadding(0, pad / 2, 0, 0) })
        pos = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("ด้านล่างจอ", "กลางจอ", "ด้านบนจอ")
            )
        }
        root.addView(pos)

        root.addView(TextView(this).apply { text = "ความแม่นยำ (โหมดต่อเนื่อง)"; setPadding(0, pad / 2, 0, 0) })
        acc = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("สมดุล (ประโยคยาวแปลทันที ประโยคสั้นยืนยัน 2 รอบ)", "เร็วสุด (แปลทันทีทุกกรณี อาจแปลขยะบ้าง)")
            )
        }
        root.addView(acc)

        root.addView(TextView(this).apply { text = "ตัวแปล"; setPadding(0, pad / 2, 0, 0) })
        engine = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "ออฟไลน์ (ML Kit + คลังคำศัพท์เกม)",
                    "คุณภาพสูง (Claude API: แก้คำที่ OCR ผิด + แปลตามบริบทเกม)"
                )
            )
        }
        root.addView(engine)
        keyBox = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-...) สำหรับโหมดคุณภาพสูง"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(getSharedPreferences("st", MODE_PRIVATE).getString("key", ""))
        }
        root.addView(keyBox)

        root.addView(Button(this).apply {
            text = "เริ่มใช้งาน"
            setOnClickListener { start() }
        })

        root.addView(TextView(this).apply {
            text = "วิธีใช้\n" +
                "1) กด \"เริ่มใช้งาน\" แล้วอนุญาตการแสดงทับแอปอื่นและการบันทึกหน้าจอ\n" +
                "2) เปิดเกม จะมีปุ่มกลม \"แปล\" ลอยอยู่ (ลากย้ายได้)\n" +
                "3) โหมดต่อเนื่อง: แตะปุ่ม 1 ครั้ง = เริ่มแปลซับไตเติลอัตโนมัติ (ปุ่มเป็นสีเขียว) คำแปลขึ้นที่แถบฝั่งตรงข้ามกับซับ แตะอีกครั้ง = หยุด\n" +
                "    โหมดทีละครั้ง: แตะปุ่ม = แปลทั้งจอ แตะที่ใดก็ได้อีกครั้ง = ปิดคำแปล\n" +
                "4) กดปุ่มค้างประมาณ 1 วินาที = ปิดโปรแกรม\n\n" +
                "ครั้งแรกต้องต่อเน็ตเพื่อโหลดโมเดลภาษา หลังจากนั้นใช้ออฟไลน์ได้"
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    private fun start() {
        val key = keyBox.text.toString().trim()
        if (engine.selectedItemPosition == 1 && key.isEmpty()) {
            Toast.makeText(this, "ใส่ API key ก่อน หรือเลือกตัวแปลแบบออฟไลน์", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("st", MODE_PRIVATE).edit().putString("key", key).apply()
        if (srcCodes[src.selectedItemPosition] == tgtCodes[tgt.selectedItemPosition]) {
            Toast.makeText(this, "ภาษาต้นทางและปลายทางต้องไม่เหมือนกัน", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "เปิดสวิตช์ \"แสดงทับแอปอื่น\" ให้แอปนี้ แล้วกลับมากดเริ่มใหม่", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("src", srcCodes[src.selectedItemPosition])
                .putExtra("tgt", tgtCodes[tgt.selectedItemPosition])
                .putExtra("live", mode.selectedItemPosition == 0)
                .putExtra("pos", pos.selectedItemPosition)
                .putExtra("votes", if (acc.selectedItemPosition == 0) 2 else 1)
                .putExtra("game", game.selectedItemPosition)
                .putExtra("engine", engine.selectedItemPosition)
                .putExtra("key", keyBox.text.toString().trim())
            startForegroundService(i)
            moveTaskToBack(true)
        } else if (requestCode == reqCapture) {
            Toast.makeText(this, "ต้องอนุญาตการบันทึกหน้าจอจึงจะแปลได้", Toast.LENGTH_SHORT).show()
        }
    }
}
