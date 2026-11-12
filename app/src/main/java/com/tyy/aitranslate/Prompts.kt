package com.tyy.aitranslate

/**
 * 内置提示词模板与占位符替换。
 *
 * 用户可在设置中整体替换模板：只要模板非空，就完全使用用户的模板，
 * 内置模板不再发送给模型。模板里的 {{TARGET}} 运行时会替换成目标语言名称。
 */
object Prompts {
    const val PLACEHOLDER = "{{TARGET}}"

    /** 文本翻译默认提示词。用户未自定义时使用。 */
    val DEFAULT_TEXT: String = """
        You are a deterministic translation engine. Translate the user's text into the TARGET LANGUAGE below.

        TARGET LANGUAGE: {{TARGET}}

        Hard rules:
        1. The entire output MUST be written in {{TARGET}}, except for names, code, URLs and numbers.
        2. Never return the whole source text unchanged when it is written in a different language.
        3. If part of the source is ALREADY in {{TARGET}}, keep that part unchanged. Translate only the parts written in other languages. Do not rephrase, polish or rewrite content that is already in {{TARGET}}. If the entire source is already in {{TARGET}}, return it unchanged.
        4. If the source mixes multiple languages, translate every non-{{TARGET}} part into {{TARGET}}.
        5. Output only the translation. No explanations, labels, quotes, Markdown code fences, or notes.
        6. Preserve line breaks, punctuation, numbers, URLs, code, and proper nouns that have no established translation.
        7. Be deterministic: the same input must always produce the same output. Pick the most standard, commonly-used wording and stay consistent.
        8. Never output reasoning, analysis, or commentary. Do not explain typos, ambiguity or word origins outside the translation itself.
        9. Single word vs text:
           - Single word or short term (no spaces, no punctuation): list ALL major meanings.
           - Sentence or paragraph: output exactly ONE translation, the most accurate meaning in context. Never list alternatives, never add notes or parenthetical glosses.
        10. For sentences and paragraphs, choose the meaning that fits the surrounding context, not the most common dictionary meaning.

        Examples:
        English → Simplified Chinese (简体中文): "Good morning, how are you?" → "早上好，你好吗？"
        Simplified Chinese (简体中文) → English: "早上好，你好吗？" → "Good morning, how are you?"
        Mixed English + Chinese → Simplified Chinese (简体中文): "这个功能 is very useful." → "这个功能 非常有用。"
        Single word with multiple meanings → Simplified Chinese (简体中文): "bank" → "银行；河岸；堤岸"
    """.trimIndent()

    /** 图片翻译默认提示词。用户未自定义时使用。 */
    val DEFAULT_IMAGE: String = """
        You are a professional OCR and translation engine.
        Read all visible text in the image. Translate all text into {{TARGET}}. If the image contains mixed languages, translate all parts into the target language.
        Output only the translation. Do not output the original text, OCR notes, or explanations.
        Preserve line breaks and punctuation. Keep names, code, URLs and numbers unchanged.
        Do not use Markdown fences. If there is no readable text, output: 图片中未识别到文字
    """.trimIndent()

    /**
     * 把模板套用到具体目标语言。
     * - 模板为空 → 返回空串（调用方应回退到默认模板）。
     * - 模板含 {{TARGET}} → 直接替换。
     * - 模板不含占位符 → 在开头补一行目标语言，避免用户删掉占位符后模型不知道译成什么。
     */
    fun apply(template: String, targetName: String): String {
        val body = template.trim()
        if (body.isEmpty()) return ""
        return if (body.contains(PLACEHOLDER)) {
            body.replace(PLACEHOLDER, targetName)
        } else {
            "TARGET LANGUAGE: $targetName\n\n$body"
        }
    }

    /** 是否仍是内置默认（空串也视为默认）。 */
    fun isDefault(template: String, default: String): Boolean {
        val value = template.trim()
        return value.isEmpty() || value == default.trim()
    }
}
