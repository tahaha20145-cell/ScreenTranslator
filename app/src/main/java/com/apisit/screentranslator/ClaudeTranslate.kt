package com.apisit.screentranslator

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** แปลด้วย Claude API (ต้องมีเน็ตและ API key) แก้ข้อผิดพลาดของ OCR และแปลตามบริบทเกม */
object ClaudeTranslate {
    private const val MODEL = "claude-haiku-5-5"

    @Volatile
    var lastError = ""

    private const val KOTOR_CONTEXT =
        "The game is Star Wars: Knights of the Old Republic II - The Sith Lords (set about 3951 BBY, " +
            "after the Jedi Civil War). The player character is the Jedi Exile. The text comes from " +
            "dialogue boxes: a speaker name, an NPC line, and numbered player response options. " +
            "Keep each character's voice: Kreia is cryptic, philosophical and bitter; Atton Rand is " +
            "sarcastic and roguish; HK-47 is a mocking assassin droid who prefixes lines with " +
            "'Statement:', 'Query:', 'Observation:' and calls humans 'meatbags'; Bao-Dur is quiet and " +
            "weary; Mira is a sharp bounty hunter; Visas Marr is solemn; the Handmaiden is formal; " +
            "Mical is earnest and scholarly; Hanharr is a savage Wookiee; the Disciple is gentle. " +
            "Response options may reflect light side or dark side choices, so keep their tone. " +
            "Use natural, vivid language suited to a space-opera RPG, not stiff machine translation, " +
            "and keep Jedi/Sith terminology consistent."

    fun translate(
        key: String,
        text: String,
        history: List<String>,
        targetName: String,
        game: Int,
        useGlossary: Boolean
    ): String? {
        return try {
            val sys = StringBuilder()
            sys.append("You translate on-screen text from video games into ").append(targetName).append(". ")
            sys.append(
                "The text was captured by OCR from the screen and may contain recognition errors " +
                    "(wrong, missing or merged letters, stray symbols, leftover HUD text). " +
                    "Silently fix them using the context and ignore text that is clearly UI noise. "
            )
            if (game == 1) {
                sys.append(KOTOR_CONTEXT).append("\n")
                if (useGlossary) {
                    sys.append("Glossary (English -> preferred rendering; always use these):\n")
                    for ((k, v) in Glossary.terms) sys.append("- ").append(k).append(" -> ").append(v).append("\n")
                }
            }
            sys.append("Output ONLY the translation, with no notes or quotation marks. ")
            sys.append("Keep numbered options (1. 2. 3.) each on its own line.")

            val user = StringBuilder()
            if (history.isNotEmpty()) {
                user.append("Previous lines (context only, do not translate):\n")
                history.forEach { user.append("- ").append(it).append("\n") }
                user.append("\n")
            }
            user.append("Translate this:\n").append(text)

            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 700)
                .put("system", sys.toString())
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", user.toString()))
                )

            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) {
                lastError = "HTTP " + c.responseCode
                return null
            }
            val resp = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            null
        }
    }
}
