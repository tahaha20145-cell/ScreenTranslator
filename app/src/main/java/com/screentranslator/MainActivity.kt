package com.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var languageSpinner: Spinner
    private lateinit var modeGroup: RadioGroup
    private lateinit var engineGroup: RadioGroup
    private lateinit var apiKeySection: View
    private lateinit var apiKeyInput: EditText
    private lateinit var glossaryInput: EditText

    // ขอสิทธิ์ตามลำดับ: แจ้งเตือน -> แสดงทับแอปอื่น -> บันทึกหน้าจอ
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkOverlayPermission() }

    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
            else toast(R.string.need_overlay)
        }

    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                toast(R.string.need_capture)
                return@registerForActivityResult
            }
            val intent = Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(OverlayService.EXTRA_DATA, data)
                .putExtra(OverlayService.EXTRA_LANGUAGE, languageSpinner.selectedItemPosition)
                .putExtra(OverlayService.EXTRA_CONTINUOUS, isContinuous())
            ContextCompat.startForegroundService(this, intent)
            moveTaskToBack(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        languageSpinner = findViewById(R.id.language)
        modeGroup = findViewById(R.id.mode)
        engineGroup = findViewById(R.id.engine)
        apiKeySection = findViewById(R.id.api_key_section)
        apiKeyInput = findViewById(R.id.api_key)
        glossaryInput = findViewById(R.id.glossary)
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            SourceLanguage.values().map { it.label },
        )

        val prefs = Prefs.get(this)
        languageSpinner.setSelection(prefs.getInt(Prefs.LANGUAGE, 0))
        modeGroup.check(if (prefs.getBoolean(Prefs.CONTINUOUS, true)) R.id.mode_continuous else R.id.mode_once)
        engineGroup.check(if (prefs.getBoolean(Prefs.USE_CLAUDE, false)) R.id.engine_claude else R.id.engine_free)
        apiKeyInput.setText(prefs.getString(Prefs.API_KEY, ""))
        glossaryInput.setText(prefs.getString(Prefs.GLOSSARY, ""))

        findViewById<Button>(R.id.load_czn).setOnClickListener { loadGamePreset(R.raw.glossary_chaos_zero_nightmare) }

        // ช่อง API key แสดงเฉพาะตอนเลือก Claude
        updateApiKeyVisibility()
        engineGroup.setOnCheckedChangeListener { _, _ -> updateApiKeyVisibility() }

        findViewById<Button>(R.id.start).setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            if (useClaude() && apiKey.isEmpty()) {
                toast(R.string.need_api_key)
                return@setOnClickListener
            }
            prefs.edit()
                .putInt(Prefs.LANGUAGE, languageSpinner.selectedItemPosition)
                .putBoolean(Prefs.CONTINUOUS, isContinuous())
                .putBoolean(Prefs.USE_CLAUDE, useClaude())
                .putString(Prefs.API_KEY, apiKey)
                .putString(Prefs.GLOSSARY, glossaryInput.text.toString())
                .apply()
            start()
        }
    }

    private fun isContinuous() = modeGroup.checkedRadioButtonId == R.id.mode_continuous

    private fun useClaude() = engineGroup.checkedRadioButtonId == R.id.engine_claude

    /** ต่อคำศัพท์สำเร็จรูปของเกมท้ายอภิธานศัพท์ที่ผู้ใช้มีอยู่ */
    private fun loadGamePreset(rawRes: Int) {
        val preset = resources.openRawResource(rawRes).bufferedReader().use { it.readText() }.trim()
        val current = glossaryInput.text.toString()
        val header = preset.lineSequence().first()
        if (current.contains(header)) {
            toast(R.string.czn_already_loaded)
            return
        }
        glossaryInput.setText(if (current.isBlank()) preset else current.trimEnd() + "\n\n" + preset)
        toast(R.string.czn_loaded)
    }

    private fun updateApiKeyVisibility() {
        apiKeySection.visibility = if (useClaude()) View.VISIBLE else View.GONE
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCapture()
        } else {
            toast(R.string.need_overlay)
            overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCapture.launch(manager.createScreenCaptureIntent())
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
