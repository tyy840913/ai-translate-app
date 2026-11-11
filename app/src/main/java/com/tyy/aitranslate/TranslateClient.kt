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
        val messages = JSONArray().apply {
            put(message("system", textSystemPrompt()))
            put(
                message(
                    "user",
                    "Translate the text below into $targetName. " +
                        "Output only the translation.\n\n<text>\n$text\n</text>"
                )
            )
        }
        return request(profile, messages)
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

    private fun textSystemPrompt(): String {
        return """
            You are a professional translation engine with deterministic output.
            The user's text may be in any language or a mixture of multiple languages. Detect the source language(s) automatically, then translate everything into the target language given in the user message.

            Consistency rules (critical):
            - For the same input, always produce the same translation. Do not vary word choice between calls.
            - Use the most standard, widely-accepted translation for each word/phrase. Prefer dictionary-standard usage over creative alternatives.
            - For proper nouns, technical terms, and common vocabulary, use the established canonical translation (e.g. "computer" → 计算机/电脑, not 电脑/计算机/微机 — pick one and stay consistent).
            - Avoid synonyms when a direct equivalent exists. If a word has multiple accepted translations, always pick the most common one.

            Accuracy requirements:
            1. Output only the translated text. No explanations, labels, quotes, or extra text.
            2. Preserve the original line breaks, punctuation and paragraph structure.
            3. Keep names, brands, code, URLs and numbers unchanged unless there is an established translation.
            4. Do not wrap the output in Markdown code fences.
            5. If the source contains multiple languages, translate all of them into the target language (do not leave any part untranslated).
        """.trimIndent()
    }

    private fun imageSystemPrompt(target: String, smart: Boolean): String {
        val targetRule = if (smart) {
            "Determine the main language of the text in the image. If all text is purely in Chinese (no other language), translate into English; otherwise translate into Simplified Chinese."
        } else {
            "Translate all text in the image into ${Languages.promptName(target)}."
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
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").trim()
            val firstLine = text.lineSequence().firstOrNull().orEmpty()
            if (firstLine.isNotBlank() && firstLine.length <= 12 && !firstLine.contains(" ")) {
                text = text.substringAfter('\n', "").trim()
            }
        }
        return text.trim()
    }
}
