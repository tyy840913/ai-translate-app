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

    /**
     * 内置语言 + 用户自定义语言。自定义语言追加在最后，
     * 并过滤掉与内置语言/SMART/AUTO 重名的项。
     */
    fun targetWith(custom: List<String>): List<String> {
        val extra = custom
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != SMART && it != AUTO && !languages.contains(it) }
            .distinct()
        return target + extra
    }

    /**
     * 给模型看的目标语言名称。中英文都给，减少模型对「中文」等词的歧义。
     */
    private val promptNames: Map<String, String> = mapOf(
        "中文" to "Simplified Chinese (简体中文)",
        "繁体中文" to "Traditional Chinese (繁體中文)",
        "英语" to "English",
        "日语" to "Japanese (日本語)",
        "韩语" to "Korean (한국어)",
        "法语" to "French (Français)",
        "德语" to "German (Deutsch)",
        "西班牙语" to "Spanish (Español)",
        "俄语" to "Russian (Русский)",
        "葡萄牙语" to "Portuguese (Português)",
        "意大利语" to "Italian (Italiano)",
        "阿拉伯语" to "Arabic (العربية)",
        "泰语" to "Thai (ไทย)",
        "越南语" to "Vietnamese (Tiếng Việt)",
        "印尼语" to "Indonesian (Bahasa Indonesia)",
        "土耳其语" to "Turkish (Türkçe)",
        "荷兰语" to "Dutch (Nederlands)",
        "波兰语" to "Polish (Polski)",
        "乌克兰语" to "Ukrainian (Українська)",
        "印地语" to "Hindi (हिन्दी)",
        "马来语" to "Malay (Bahasa Melayu)",
        "瑞典语" to "Swedish (Svenska)",
        "希腊语" to "Greek (Ελληνικά)",
        "希伯来语" to "Hebrew (עברית)",
        "捷克语" to "Czech (Čeština)",
        "罗马尼亚语" to "Romanian (Română)",
        "匈牙利语" to "Hungarian (Magyar)",
        "芬兰语" to "Finnish (Suomi)",
        "丹麦语" to "Danish (Dansk)",
        "挪威语" to "Norwegian (Norsk)"
    )

    fun promptName(language: String): String = promptNames[language] ?: language

    /** 目标语言是否使用中日韩文字，用于选择释义之间的分隔符。 */
    fun isCjkTarget(target: String): Boolean =
        target == "中文" || target == "繁体中文" || target == "日语" || target == "韩语"

    fun normalizeTarget(value: String?, custom: List<String> = emptyList()): String {
        return if (value != null && targetWith(custom).contains(value)) value else SMART
    }

    /**
     * 智能模式的目标语言：
     * 纯中文（只有汉字，无其他语言文字）→ 英语；
     * 其他情况（混合语言、纯外文、含中文夹杂其他语言）→ 中文。
     */
    fun resolveTarget(
        text: String,
        selectedTarget: String,
        custom: List<String> = emptyList()
    ): String {
        if (selectedTarget != SMART) return normalizeTarget(selectedTarget, custom)
        return if (isPureChinese(text)) "英语" else "中文"
    }

    /**
     * 判断文本是否"纯中文"：含汉字，且不含其他语言的字母（英文 a-z、日文假名、韩文）。
     * 纯中文 → 翻译成英文；含其他语言 → 翻译成中文。
     */
    fun isPureChinese(text: String): Boolean {
        var han = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                isHan(codePoint) -> han++
                // 英文字母、日文假名、韩文、希腊字母、西里尔字母等 → 算"其他语言"
                isLatin(codePoint) || isKana(codePoint) || isHangul(codePoint) ||
                    isCyrillic(codePoint) || isGreek(codePoint) -> other++
            }
            index += Character.charCount(codePoint)
        }
        return han > 0 && other == 0
    }

    /**
     * 粗略判断译文是否「没有翻译」（整段仍是源语言）。
     * 只对中文/英语这两个有明确文字系统的目标做检测；其他语种不做判断。
     * 混合文本（既有汉字又有拉丁字母）视为已翻译，不重试。
     */
    fun looksUntranslated(text: String, target: String): Boolean {
        var han = 0
        var latin = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                isHan(codePoint) -> han++
                isLatin(codePoint) -> latin++
            }
            index += Character.charCount(codePoint)
        }
        return when (target) {
            // 目标是中文，却一个汉字都没有、只有拉丁字母 → 没翻译
            "中文", "繁体中文" -> latin > 0 && han == 0
            // 目标是英文，却一个拉丁字母都没有、只有汉字 → 没翻译
            "英语" -> han > 0 && latin == 0
            else -> false
        }
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

    private fun isLatin(codePoint: Int): Boolean {
        // ASCII 之外还要认带变音符的拉丁字母（café 的 é、naïve 的 ï）：
        // Latin-1 Supplement + Extended-A/B（U+00C0..U+024F）、
        // Latin Extended Additional（U+1E00..U+1EFF，越南语等）。
        // 只认 ASCII 时「中文é」会被误判成纯中文，智能模式方向译反。
        return codePoint in 0x0041..0x005A || codePoint in 0x0061..0x007A ||
            codePoint in 0x00C0..0x024F || codePoint in 0x1E00..0x1EFF
    }

    private fun isCyrillic(codePoint: Int): Boolean {
        return codePoint in 0x0400..0x04FF || codePoint in 0x0500..0x052F
    }

    private fun isGreek(codePoint: Int): Boolean {
        return codePoint in 0x0370..0x03FF || codePoint in 0x1F00..0x1FFF
    }
}
