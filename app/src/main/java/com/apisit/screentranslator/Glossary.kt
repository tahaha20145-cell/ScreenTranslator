package com.apisit.screentranslator

/**
 * คลังคำศัพท์ Star Wars: KOTOR 2 (อังกฤษ -> ไทย)
 * แก้ไขคำไทยได้ตามต้องการ แล้ว build ใหม่
 * ใช้กับทั้งโหมดออฟไลน์ (ML Kit) และส่งให้ Claude เป็นแนวทางการใช้คำ
 */
object Glossary {
    val terms: LinkedHashMap<String, String> = linkedMapOf(
        // แนวคิด / ศัพท์ทั่วไป
        "the Force" to "ฟอร์ซ",
        "Force" to "ฟอร์ซ",
        "Dark Side" to "ด้านมืด",
        "Light Side" to "ด้านสว่าง",
        "Jedi Master" to "เจไดมาสเตอร์",
        "Jedi Knight" to "อัศวินเจได",
        "Jedi Council" to "สภาเจได",
        "Jedi Order" to "ออร์เดอร์เจได",
        "Jedi" to "เจได",
        "Sith Lord" to "ลอร์ดซิธ",
        "Sith Lords" to "ลอร์ดซิธ",
        "Sith" to "ซิธ",
        "lightsaber" to "ไลท์เซเบอร์",
        "padawan" to "พาดาวัน",
        "holocron" to "โฮโลครอน",
        "credits" to "เครดิต",
        "Republic" to "สาธารณรัฐ",
        "Mandalorians" to "แมนดาลอเรียน",
        "Mandalorian" to "แมนดาลอเรียน",
        "Jedi Civil War" to "สงครามกลางเมืองเจได",
        "Mandalorian Wars" to "สงครามแมนดาลอเรียน",
        "Exile" to "ผู้ถูกเนรเทศ",
        // ตัวละคร
        "Kreia" to "เครีอา",
        "Atton Rand" to "แอตตัน แรนด์",
        "Atton" to "แอตตัน",
        "Bao-Dur" to "เบา-เดอร์",
        "Mira" to "ไมรา",
        "Hanharr" to "ฮานฮาร์",
        "Visas Marr" to "วิซาส มาร์",
        "Handmaiden" to "แฮนด์เมเดน",
        "Disciple" to "ดิสไซเพิล",
        "Mical" to "ไมคอล",
        "HK-47" to "HK-47",
        "T3-M4" to "T3-M4",
        "G0-T0" to "G0-T0",
        "Revan" to "รีแวน",
        "Malak" to "มาลัก",
        "Darth Nihilus" to "ดาร์ธ ไนฮิลัส",
        "Darth Sion" to "ดาร์ธ ไซออน",
        "Darth Traya" to "ดาร์ธ ทรายา",
        "Darth" to "ดาร์ธ",
        "Mandalore" to "แมนดาลอร์",
        // สถานที่ / ยานพาหนะ / องค์กร
        "Peragus" to "เพอราเกอส",
        "Telos" to "เทลอส",
        "Citadel Station" to "สถานีซิทาเดล",
        "Nar Shaddaa" to "นาร์ ชัดดา",
        "Dantooine" to "แดนทูอีน",
        "Onderon" to "ออนเดอรอน",
        "Dxun" to "ดซัน",
        "Korriban" to "คอร์ริบัน",
        "Malachor V" to "มาลาคอร์ 5",
        "Malachor" to "มาลาคอร์",
        "Ebon Hawk" to "อีบอน ฮอว์ก",
        "Czerka" to "เซอร์กา",
        // เผ่าพันธุ์
        "Twi'lek" to "ทวีเล็ก",
        "Wookiee" to "วูคกี้",
        "Hutt" to "ฮัตต์",
        "Zabrak" to "ซาแบรก",
        "Ithorian" to "อิโธเรียน"
    )

    // เรียงจากคำยาวไปสั้น เพื่อให้ "Jedi Master" ถูกจับก่อน "Jedi"
    private val sorted = terms.entries.sortedByDescending { it.key.length }
    private val regs = sorted.map {
        Regex("\\b" + Regex.escape(it.key) + "\\b", RegexOption.IGNORE_CASE)
    }

    // แทนคำศัพท์ด้วยโทเคนก่อนส่งเข้า ML Kit กันถูกแปลเพี้ยน
    fun protect(text: String): String {
        var s = text
        regs.forEachIndexed { i, r -> s = r.replace(s, "QZ${i}ZQ") }
        return s
    }

    // ใส่คำไทยที่กำหนดกลับเข้าไปแทนโทเคน
    fun restore(text: String): String {
        return Regex("(?i)q\\s?z\\s?(\\d+)\\s?z\\s?q").replace(text) { m ->
            sorted.getOrNull(m.groupValues[1].toInt())?.value ?: m.value
        }
    }
}
