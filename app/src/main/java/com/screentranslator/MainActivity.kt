package com.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var languageSpinner: Spinner
    private lateinit var modeGroup: RadioGroup

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
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            SourceLanguage.values().map { it.label },
        )

        val prefs = getPreferences(MODE_PRIVATE)
        languageSpinner.setSelection(prefs.getInt(PREF_LANGUAGE, 0))
        modeGroup.check(if (prefs.getBoolean(PREF_CONTINUOUS, true)) R.id.mode_continuous else R.id.mode_once)

        findViewById<Button>(R.id.start).setOnClickListener {
            prefs.edit()
                .putInt(PREF_LANGUAGE, languageSpinner.selectedItemPosition)
                .putBoolean(PREF_CONTINUOUS, isContinuous())
                .apply()
            start()
        }
    }

    private fun isContinuous() = modeGroup.checkedRadioButtonId == R.id.mode_continuous

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

    private companion object {
        const val PREF_LANGUAGE = "language"
        const val PREF_CONTINUOUS = "continuous"
    }
}
