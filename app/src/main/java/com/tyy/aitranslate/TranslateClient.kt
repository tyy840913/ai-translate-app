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

    /** 智能模式（图片）的目标语言描述，替换 {{TARGET}} 用。 */
    private const val SMART_IMAGE_TARGET =
        "the automatically determined target language (English if all text is purely Chinese, " +
            "otherwise Simplified Chinese)"

    fun translate(
        profile: ApiProfile,
        text: String,
        target: String,
        promptTemplate: String = Prompts.DEFAULT_TEXT
    ): Result<String> {
        val targetName = Languages.promptName(target)
        val first = request(
            profile,
            textMessages(text, target, targetName, promptTemplate, strict = false)
        )
        val translated = first.getOrNull() ?: return first
        // 兜底：明显没翻译（如目标中文却全是英文）时，用更强的提示重试一次
        if (!Languages.looksUntranslated(translated, target)) return first
        val retried = request(
            profile,
            textMessages(text, target, targetName, promptTemplate, strict = true)
        )
        return retried.getOrNull()?.let { Result.success(it) } ?: first
    }

    private fun textMessages(
        text: String,
        target: String,
        targetName: String,
        promptTemplate: String,
        strict: Boolean
    ): JSONArray {
        // 单词和文本的期望输出不同，直接在 user message 里点明，比让模型自己判断更可靠
        val separator = if (Languages.isCjkTarget(target)) "；" else "; "
        val modeHint = if (isSingleWord(text)) {
            "This input is a single word or short term. " +
                "List ALL major meanings separated by \"$separator\". " +
                "If it is a misspelled or non-existent word (e.g., \"theer\"), do NOT guess meanings: " +
                "output one line starting with [INVALID_WORD], followed by a short note in $targetName " +
                "naming the invalid word and the most likely intended word(s)."
        } else {
            "This input is a sentence or paragraph. " +
                "Give exactly ONE accurate, context-appropriate translation. " +
                "Do not list alternatives or add notes."
        }
        var system = Prompts.apply(
            promptTemplate.ifBlank { Prompts.DEFAULT_TEXT },
            targetName
        )
        if (system.isBlank()) {
            system = Prompts.apply(Prompts.DEFAULT_TEXT, targetName)
        }
        if (strict) {
            system += "\n\n" +
                "CRITICAL: Your previous answer was not in $targetName. This is forbidden. " +
                "Rewrite the entire translation in $targetName. Do not answer in the source language."
        }
        return JSONArray().apply {
            put(message("system", system))
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
     * 是否为单个词/短词：无空白、无句读标点，且长度很短。
     * 中文等多字节语言收得更紧（2 个码点），避免把「人工智能」这类术语
     * 误判成多义词而输出一堆释义；拉丁等按 20 个码点。
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
        return if (han > 0) codePoints <= 2 else codePoints <= 20
    }

    fun translateImage(
        profile: ApiProfile,
        imageBase64: String,
        target: String,
        smart: Boolean,
        promptTemplate: String = Prompts.DEFAULT_IMAGE
    ): Result<String> {
        val targetDesc = if (smart) SMART_IMAGE_TARGET else Languages.promptName(target)
        var system = Prompts.apply(
            promptTemplate.ifBlank { Prompts.DEFAULT_IMAGE },
            targetDesc
        )
        if (system.isBlank()) {
            system = Prompts.apply(Prompts.DEFAULT_IMAGE, targetDesc)
        }
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
            put(message("system", system))
            put(JSONObject().put("role", "user").put("content", content))
        }
        return request(profile, messages)
    }

    private fun message(role: String, content: String): JSONObject {
        return JSONObject().put("role", role).put("content", content)
    }

    private fun request(profile: ApiProfile, messages: JSONArray): Result<String> {
        val first = execute(profile, messages, extras = true)
        val error = first.exceptionOrNull()
        // 部分 OpenAI 兼容服务不认 reasoning_effort / chat_template_kwargs，
        // 会直接返回 400；去掉扩展字段后重试一次，提高兼容性。
        if (error is IOException && error.message?.startsWith("HTTP 400") == true) {
            return execute(profile, messages, extras = false)
        }
        return first
    }

    private fun execute(
        profile: ApiProfile,
        messages: JSONArray,
        extras: Boolean
    ): Result<String> {
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
                if (extras) {
                    // 关闭思考模式的三类平台写法，全部带上；不认识多余字段的平台
                    // 会报 400，request() 会去掉全部扩展字段重试一次兜底。
                    // thinking.type：GLM/智谱系（文档默认 enabled，必须显式 disabled）
                    put("thinking", JSONObject().put("type", "disabled"))
                    // reasoning_effort：OpenAI 系，none = 关闭思考
                    put("reasoning_effort", "none")
                    // chat_template_kwargs.enable_thinking：vLLM / Qwen 系
                    put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }
                put("messages", messages)
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AITranslate/2.2 (Android)")
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
                val cleaned = clean(content)
                if (cleaned.isBlank()) {
                    Result.failure(IOException("模型返回内容为空，请重试或检查自定义提示词"))
                } else {
                    Result.success(cleaned)
                }
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
            // 围栏语言标记只可能是 ASCII 字母数字（json/text/bash…）。
            // 之前只看「短且无空格」，"你好"、{"a":1} 这类译文首行会被误当成
            // 语言标记剥掉，导致译文首行丢失甚至整条译文变空。
            val looksLikeLangTag = firstLine.length in 1..12 &&
                firstLine.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "+-#" }
            if (looksLikeLangTag) {
                text = text.substringAfter('\n', "").trim()
            }
        }
        // 过滤后为空时返回空串，由调用方按失败处理，避免把思考内容当成译文
        return text
    }
}
