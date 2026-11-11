package com.tyy.aitranslate

object Languages {
    const val AUTO = "自动检测"
    const val SMART = "智能"

    private val languages: List<String> = listOf(
        "中文",
        "繁体中文",
        "英语",
        "日语",
        "韩语",
        "法语",
        "德语",
        "西班牙语",
        "俄语",
        "葡萄牙语",
        "意大利语",
        "阿拉伯语",
        "泰语",
        "越南语",
        "印尼语",
        "土耳其语",
        "荷兰语",
        "波兰语",
        "乌克兰语",
        "印地语",
        "马来语",
        "瑞典语",
        "希腊语",
        "希伯来语",
        "捷克语",
        "罗马尼亚语",
        "匈牙利语",
        "芬兰语",
        "丹麦语",
        "挪威语"
    )

    val source: List<String> = listOf(AUTO) + languages
    val target: List<String> = listOf(SMART) + languages

    fun normalizeTarget(value: String?): String {
        return if (value != null && target.contains(value)) value else SMART
    }

    /**
     * 智能模式的目标语言：
     * 中文 -> 英语；其他语言 -> 中文。
     */
    fun resolveTarget(text: String, selectedTarget: String): String {
        if (selectedTarget != SMART) return normalizeTarget(selectedTarget)
        return if (isChinese(text)) "英语" else "中文"
    }

    /**
     * 判断文本是否以中文为主。含日文假名或韩文时不算中文，避免误判。
     */
    fun isChinese(text: String): Boolean {
        var han = 0
        var kana = 0
        var hangul = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                isHan(codePoint) -> han++
                isKana(codePoint) -> kana++
                isHangul(codePoint) -> hangul++
            }
            index += Character.charCount(codePoint)
        }
        return han > 0 && kana == 0 && hangul == 0
    }

    private fun isHan(codePoint: Int): Boolean {
        return codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F ||
            codePoint in 0x30000..0x3134F
    }

    private fun isKana(codePoint: Int): Boolean {
        return codePoint in 0x3040..0x309F ||
            codePoint in 0x30A0..0x30FF ||
            codePoint in 0x31F0..0x31FF ||
            codePoint in 0xFF66..0xFF9F
    }

    private fun isHangul(codePoint: Int): Boolean {
        return codePoint in 0x1100..0x11FF ||
            codePoint in 0x3130..0x318F ||
            codePoint in 0xA960..0xA97F ||
            codePoint in 0xAC00..0xD7AF ||
            codePoint in 0xD7B0..0xD7FF
    }
}
