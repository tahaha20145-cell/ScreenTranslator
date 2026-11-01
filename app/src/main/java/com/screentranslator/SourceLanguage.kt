package com.screentranslator

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** ภาษาต้นฉบับที่รองรับ (แปลเป็นภาษาไทยเสมอ) */
enum class SourceLanguage(val label: String, val translateCode: String) {
    ENGLISH("อังกฤษ (English)", TranslateLanguage.ENGLISH),
    JAPANESE("ญี่ปุ่น (日本語)", TranslateLanguage.JAPANESE),
    CHINESE("จีน (中文)", TranslateLanguage.CHINESE),
    KOREAN("เกาหลี (한국어)", TranslateLanguage.KOREAN);

    fun createRecognizer(): TextRecognizer = when (this) {
        ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
}
