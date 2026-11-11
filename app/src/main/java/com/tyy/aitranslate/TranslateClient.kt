package com.tyy.aitranslate

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object TranslateClient {
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 90_000

    fun translate(
        profile: ApiProfile,
        text: String,
        source: String,
        target: String
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
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", systemPrompt(source, target))
                        )
                        put(JSONObject().put("role", "user").put("content", text))
                    }
                )
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AITranslate/1.0 (Android)")
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

    private fun systemPrompt(source: String, target: String): String {
        val sourceLine = if (source == Languages.AUTO) {
            "源语言：自动检测"
        } else {
            "源语言：$source"
        }
        return """
            你是专业翻译引擎。请把用户提供的文本翻译成$target。
            $sourceLine
            要求：
            1. 只输出译文，不要解释、不要加引号、不要添加任何前后缀。
            2. 保留原文的换行、标点和段落格式。
            3. 人名、品牌、代码、URL、数字保持原样，除非存在约定俗成的译名。
            4. 如果原文已经是$target，原样返回。
            5. 不要使用 Markdown 代码块包裹译文。
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
