package com.screentranslator

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * ภาษาต้นฉบับที่รองรับ (แปลเป็นภาษาไทยเสมอ)
 * [spaced] = ภาษานี้เว้นวรรคระหว่างคำไหม ใช้ตอนต่อบรรทัดที่ OCR ตัดออกจากกัน
 */
enum class SourceLanguage(
    val label: String,
    val englishName: String,
    val translateCode: String,
    val spaced: Boolean,
) {
    ENGLISH("อังกฤษ (English)", "English", TranslateLanguage.ENGLISH, true),
    JAPANESE("ญี่ปุ่น (日本語)", "Japanese", TranslateLanguage.JAPANESE, false),
    CHINESE("จีน (中文)", "Chinese", TranslateLanguage.CHINESE, false),
    KOREAN("เกาหลี (한국어)", "Korean", TranslateLanguage.KOREAN, true);

    val joiner get() = if (spaced) " " else ""

    fun createRecognizer(): TextRecognizer = when (this) {
        ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
}
