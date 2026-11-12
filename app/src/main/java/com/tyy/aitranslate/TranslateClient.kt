package com.tyy.aitranslate

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object TranslateClient {
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000

    fun translate(profile: ApiProfile, text: String, target: String): Result<String> {
        val targetName = Languages.promptName(target)
        val first = request(profile, textMessages(text, targetName, strict = false))
        val translated = first.getOrNull() ?: return first
        // 兜底：明显没翻译（如目标中文却全是英文）时，用更强的提示重试一次
        if (!Languages.looksUntranslated(translated, target)) return first
        val retried = request(profile, textMessages(text, targetName, strict = true))
        return retried.getOrNull()?.let { Result.success(it) } ?: first
    }

    private fun textMessages(text: String, targetName: String, strict: Boolean): JSONArray {
        // 单词和文本的期望输出不同，直接在 user message 里点明，比让模型自己判断更可靠
        val modeHint = if (isSingleWord(text)) {
            "This input is a single word or short term. " +
                "List ALL major meanings separated by \"；\"."
        } else {
            "This input is a sentence or paragraph. " +
                "Give exactly ONE accurate, context-appropriate translation. " +
                "Do not list alternatives or add notes."
        }
        return JSONArray().apply {
            put(message("system", textSystemPrompt(targetName, strict)))
            put(
                message(
                    "user",
                    "Translate the text below into $targetName. $modeHint " +
                        "Output only the translation. Do not repeat or keep the source text.\n\n" +
                        "<text>\n$text\n</text>"
                )
            )
        }
    }

    /**
     * 是否为单个词/短词：无空白、无句读标点，且长度很短
     * （中文/日文等按 4 个码点，拉丁等按 20 个码点）。
     */
    private fun isSingleWord(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it.isWhitespace() }) return false
        if (trimmed.any { it in "。！？.!?，,;；:：、…" }) return false
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        var han = 0
        var index = 0
        while (index < trimmed.length) {
            val codePoint = Character.codePointAt(trimmed, index)
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) han++
            index += Character.charCount(codePoint)
        }
        return if (han > 0) codePoints <= 4 else codePoints <= 20
    }

    fun translateImage(
        profile: ApiProfile,
        imageBase64: String,
        target: String,
        smart: Boolean
    ): Result<String> {
        val content = JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "text")
                    .put("text", "Read all text in this image and translate it.")
            )
            put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
            )
        }
        val messages = JSONArray().apply {
            put(message("system", imageSystemPrompt(target, smart)))
            put(JSONObject().put("role", "user").put("content", content))
        }
        return request(profile, messages)
    }

    private fun message(role: String, content: String): JSONObject {
        return JSONObject().put("role", role).put("content", content)
    }

    private fun request(profile: ApiProfile, messages: JSONArray): Result<String> {
        return try {
            if (profile.baseUrl.isBlank()) {
                return Result.failure(IOException("请先在设置中填写接口地址"))
            }
            if (profile.model.isBlank()) {
                return Result.failure(IOException("请先在设置中填写模型名称"))
            }

            val endpoint = endpoint(profile.baseUrl)
            val payload = JSONObject().apply {
                put("model", profile.model.trim())
                put("stream", false)
                put("temperature", 0.1)
                put("reasoning_effort", "none")
                // GLM / Qwen3 系列认 chat_template_kwargs.enable_thinking；OpenAI 系列忽略该字段，无副作用
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                put("messages", messages)
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AITranslate/1.2 (Android)")
                if (profile.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${profile.apiKey.trim()}")
                }
            }

            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) {
                val message = try {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                } catch (_: Exception) {
                    null
                }
                val detail = if (message.isNullOrBlank()) {
                    body.take(300)
                } else {
                    message
                }
                return Result.failure(
                    IOException("HTTP $code" + if (detail.isBlank()) "" else "：$detail")
                )
            }

            val json = JSONObject(body)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: choice?.optString("text")?.takeIf { it.isNotBlank() }

            if (content.isNullOrBlank()) {
                Result.failure(IOException("接口返回为空，请确认模型名称支持 Chat Completions"))
            } else {
                Result.success(clean(content))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun endpoint(baseUrl: String): String {
        var value = baseUrl.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        value = value.trimEnd('/')
        if (value.endsWith("/chat/completions")) return value
        val path = try {
            URI(value).path.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return if (path.isBlank() || path == "/") {
            "$value/v1/chat/completions"
        } else {
            "$value/chat/completions"
        }
    }

    private fun textSystemPrompt(targetName: String, strict: Boolean): String {
        val base = """
            You are a deterministic translation engine. Translate the user's text into the TARGET LANGUAGE below.

            TARGET LANGUAGE: $targetName

            Hard rules:
            1. The entire output MUST be written in $targetName, except for names, code, URLs and numbers.
            2. Never return the whole source text unchanged when it is written in a different language.
            3. If part of the source is ALREADY in $targetName, keep that part unchanged. Translate only the parts written in other languages. Do not rephrase, polish or rewrite content that is already in $targetName.
            4. If the source mixes multiple languages, translate every non-$targetName part into $targetName.
            5. Output only the translation. No explanations, labels, quotes, Markdown code fences, or notes.
            6. Preserve line breaks, punctuation, numbers, URLs, code, and proper nouns that have no established translation.
            7. Be deterministic: the same input must always produce the same output. Pick the most standard, commonly-used wording and stay consistent.
            8. Never output reasoning, analysis, or commentary. Do not explain typos, ambiguity or word origins outside the translation itself.
            9. Single word vs text:
               - Single word or short term (no spaces, no punctuation): list ALL major meanings separated by "；".
               - Sentence or paragraph: output exactly ONE translation, the most accurate meaning in context. Never list alternatives, never add notes or parenthetical glosses.
            10. For sentences and paragraphs, choose the meaning that fits the surrounding context, not the most common dictionary meaning.

            Examples:
            English → Simplified Chinese (简体中文): "Good morning, how are you?" → "早上好，你好吗？"
            Simplified Chinese (简体中文) → English: "早上好，你好吗？" → "Good morning, how are you?"
            Mixed English + Chinese → Simplified Chinese (简体中文): "这个功能 is very useful." → "这个功能 非常有用。"
            Single word with multiple meanings → Simplified Chinese (简体中文): "bank" → "银行；河岸；堤岸"
        """.trimIndent()
        if (!strict) return base
        return base + "\n\n" +
            "CRITICAL: Your previous answer was not in $targetName. This is forbidden. " +
            "Rewrite the entire translation in $targetName. Do not answer in the source language."
    }

    private fun imageSystemPrompt(target: String, smart: Boolean): String {
        val targetRule = if (smart) {
            "Determine the main language of the text in the image. If all text is purely in Chinese (no other language), translate into English; otherwise translate into Simplified Chinese. If the image contains mixed languages, translate all parts into the target language."
        } else {
            "Translate all text in the image into ${Languages.promptName(target)}. If the image contains mixed languages, translate all parts into the target language."
        }
        return """
            You are a professional OCR and translation engine.
            Read all visible text in the image. $targetRule
            Output only the translation. Do not output the original text, OCR notes, or explanations.
            Preserve line breaks and punctuation. Keep names, code, URLs and numbers unchanged.
            Do not use Markdown fences. If there is no readable text, output: 图片中未识别到文字
        """.trimIndent()
    }

    private fun clean(raw: String): String {
        var text = raw.trim()

        // 过滤模型思考内容：部分模型无视 enable_thinking=false，仍会输出
        // 「思考过程 + </think> + 译文」，甚至把思考重复多遍。
        val closeTags = listOf("</think>", "</thinking>", "</reasoning>", "</thought>")
        var cut = -1
        for (tag in closeTags) {
            val index = text.lastIndexOf(tag)
            if (index >= 0) cut = maxOf(cut, index + tag.length)
        }
        if (cut >= 0) {
            // 取最后一个思考结束标签之后的内容（通常是真正的答案）
            text = text.substring(cut).trim()
        } else {
            // 没有结束标签时，移除成对的思考块
            text = text.replace(
                Regex(
                    "(?is)<(?:think|thinking|reasoning|thought)>" +
                        ".*?</(?:think|thinking|reasoning|thought)>"
                ),
                " "
            ).trim()
        }

        // 去掉 Markdown 代码围栏
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").trim()
            val firstLine = text.lineSequence().firstOrNull().orEmpty()
            if (firstLine.isNotBlank() && firstLine.length <= 12 && !firstLine.contains(" ")) {
                text = text.substringAfter('\n', "").trim()
            }
        }
        // 过滤后为空则回退原文，避免把正常结果误删
        return text.ifBlank { raw.trim() }
    }
}
