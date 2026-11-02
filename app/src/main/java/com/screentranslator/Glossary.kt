package com.screentranslator

/**
 * อภิธานศัพท์ที่ผู้ใช้กำหนดเอง บรรทัดละคำ รูปแบบ "ต้นฉบับ = คำแปล" เช่น "Hikari = ฮิคาริ"
 */
class Glossary private constructor(private val entries: List<Pair<String, String>>) {

    val isEmpty get() = entries.isEmpty()

    private val patterns = entries.map { toPattern(it.first) }

    /** สำหรับใส่ใน prompt ของ Claude */
    fun asPromptLines(): String = entries.joinToString("\n") { "${it.first} = ${it.second}" }

    /**
     * แทนคำในอภิธานศัพท์ด้วยตัวเลข เพราะ ML Kit จะคัดลอกตัวเลขไปตรงๆ ไม่แปล
     * แล้วค่อยใส่คำแปลที่ผู้ใช้กำหนดกลับเข้าไปด้วย [restore]
     */
    fun protect(text: String): Pair<String, Map<String, String>> {
        var result = text
        val tokens = LinkedHashMap<String, String>()
        entries.forEachIndexed { i, (_, target) ->
            val pattern = patterns[i]
            if (pattern.containsMatchIn(result)) {
                val token = (TOKEN_BASE + i).toString()
                result = pattern.replace(result, token)
                tokens[token] = target
            }
        }
        return result to tokens
    }

    fun restore(text: String, tokens: Map<String, String>): String =
        tokens.entries.fold(text) { acc, (token, target) -> acc.replace(token, target) }

    companion object {
        private const val TOKEN_BASE = 90100
        private val LATIN = Regex("[A-Za-z0-9 '\\-]+")

        /**
         * คำภาษาอังกฤษต้องตรงทั้งคำ ไม่งั้น "Rin" จะไปแทนที่ใน "Bring"
         * และยอมให้มี s ต่อท้าย เช่น "Memory Fragment" ตรงกับ "Memory Fragments"
         */
        private fun toPattern(source: String): Regex {
            val escaped = Regex.escape(source)
            val pattern = if (LATIN.matches(source)) "\\b$escaped(?:s|es)?\\b" else escaped
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }

        fun parse(raw: String?): Glossary = Glossary(
            raw.orEmpty().lines()
                .mapNotNull { line ->
                    if (line.trimStart().startsWith("#")) return@mapNotNull null
                    val i = line.indexOf('=')
                    if (i <= 0) return@mapNotNull null
                    val source = line.substring(0, i).trim()
                    val target = line.substring(i + 1).trim()
                    if (source.isEmpty() || target.isEmpty()) null else source to target
                }
                // คำยาวก่อน เพื่อไม่ให้คำสั้นที่อยู่ข้างในไปแทนที่ก่อน
                .sortedByDescending { it.first.length }
        )
    }
}
