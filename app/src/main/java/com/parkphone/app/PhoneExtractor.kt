package com.parkphone.app

/** 从 OCR 识别的原始文本中提取电话号码候选 */
object PhoneExtractor {

    /**
     * 返回去重后的号码候选（已去掉空格、横线等分隔符）。
     * 依次尝试：手机号 → 带区号座机 → 7~12 位数字串兜底。
     */
    fun extract(text: String): List<String> {
        val out = LinkedHashSet<String>()

        // 1) 手机号：以 1 开头的 11 位数字串，可被分隔符打断；可选 +86/86 前缀
        Regex("""(?:\+?86[\s.·-]*)?1[\s.·-]?[3-9](?:[\s.·-]?\d){9}""").findAll(text).forEach { m ->
            val digits = m.value.filter { it.isDigit() }
            val mobile = if (digits.length > 11 && (digits.startsWith("86"))) digits.substring(2) else digits
            if (mobile.length == 11 && mobile[0] == '1') out.add(mobile)
        }

        // 2) 带区号座机：0 + 2~3 位区号 + 7~8 位号码
        Regex("""0[\s.·-]?\d{2,3}[\s.·-]?\d{7,8}""").findAll(text).forEach { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 10..12) out.add(digits)
        }

        // 3) 兜底：任何 7~12 位的数字串
        if (out.isEmpty()) {
            Regex("""\d(?:[\s.·-]?\d){6,11}""").findAll(text).forEach { m ->
                val digits = m.value.filter { it.isDigit() }
                if (digits.length in 7..12) out.add(digits)
            }
        }
        return out.toList()
    }
}
