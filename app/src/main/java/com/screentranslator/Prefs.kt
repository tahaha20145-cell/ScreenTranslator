package com.screentranslator

import android.content.Context
import android.content.SharedPreferences

/** ค่าที่ผู้ใช้ตั้งไว้ (เก็บในพื้นที่ส่วนตัวของแอป แอปอื่นอ่านไม่ได้) */
object Prefs {
    const val LANGUAGE = "language"
    const val CONTINUOUS = "continuous"
    const val USE_CLAUDE = "use_claude"
    const val API_KEY = "api_key"
    const val GLOSSARY = "glossary"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
