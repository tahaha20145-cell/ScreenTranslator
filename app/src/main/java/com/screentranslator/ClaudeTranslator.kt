package com.screentranslator

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONArray
import org.json.JSONException

/** แปลข้อความทุกช่องบนจอเป็นภาษาไทยด้วย Claude ในคำขอเดียว */
class ClaudeTranslator(apiKey: String, language: SourceLanguage, glossary: Glossary) : AutoCloseable {

    class InvalidKeyException : Exception()

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    private val systemPrompt = """
        You translate text captured from a video game screen from ${language.englishName} into natural Thai.
        The input is a JSON array of strings; each string is a separate text box on screen.
        The text comes from OCR and may contain small recognition errors: fix them silently.
        Keep character, skill and item names consistent, and keep short UI labels short.
        Reply with only a JSON array of Thai strings, in the same order and with the same length as the input.
    """.trimIndent() + if (glossary.isEmpty) "" else
        "\n\nAlways use these fixed translations set by the player (source = Thai):\n" + glossary.asPromptLines()

    /**
     * บล็อกจนกว่าจะได้คำตอบ ต้องเรียกจาก background thread
     * คืนค่า null ถ้าคำตอบอ่านไม่ได้หรือจำนวนไม่ตรงกับที่ส่งไป
     */
    fun translate(texts: List<String>): List<String>? {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4096L)
            .system(systemPrompt)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(JSONArray(texts).toString())
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 401) throw InvalidKeyException()
            throw e
        }
        val reply = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return parse(reply, texts.size)
    }

    private fun parse(reply: String, expected: Int): List<String>? {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            val array = JSONArray(reply.substring(start, end + 1))
            if (array.length() != expected) null else List(expected) { array.optString(it) }
        } catch (e: JSONException) {
            null
        }
    }

    override fun close() = client.close()

    companion object {
        // Haiku: เร็วและถูก เหมาะกับการแปลซับต่อเนื่อง
        const val MODEL = "claude-haiku-5-5"
    }
}
