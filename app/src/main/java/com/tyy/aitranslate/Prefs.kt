package com.tyy.aitranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "ai_translate_prefs"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_HISTORY = "history"
    private const val KEY_TARGET = "target_lang"
    private const val KEY_TARGET_SMART = "target_lang_smart_default"
    private const val KEY_CUSTOM_TARGETS = "custom_targets"
    private const val KEY_PROMPT_TEXT = "prompt_text"
    private const val KEY_PROMPT_IMAGE = "prompt_image"
    private const val MAX_HISTORY = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- API 配置 ----------

    fun profiles(context: Context): MutableList<ApiProfile> {
        val list = mutableListOf<ApiProfile>()
        try {
            val array = JSONArray(prefs(context).getString(KEY_PROFILES, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    ApiProfile(
                        id = o.optLong("id"),
                        name = o.optString("name"),
                        baseUrl = o.optString("baseUrl"),
                        apiKey = o.optString("apiKey"),
                        model = o.optString("model")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveProfiles(context: Context, profiles: List<ApiProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("baseUrl", p.baseUrl)
            o.put("apiKey", p.apiKey)
            o.put("model", p.model)
            array.put(o)
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun addProfile(context: Context, profile: ApiProfile): ApiProfile {
        val list = profiles(context)
        var id = System.currentTimeMillis()
        while (list.any { it.id == id }) id++
        val saved = profile.copy(id = id)
        list.add(saved)
        saveProfiles(context, list)
        if (activeId(context) == 0L) setActiveProfile(context, id)
        return saved
    }

    fun updateProfile(context: Context, profile: ApiProfile) {
        val list = profiles(context)
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            list[index] = profile
            saveProfiles(context, list)
        }
    }

    fun deleteProfile(context: Context, id: Long) {
        val list = profiles(context).filterNot { it.id == id }
        saveProfiles(context, list)
        if (activeId(context) == id) {
            prefs(context).edit().putLong(KEY_ACTIVE, list.firstOrNull()?.id ?: 0L).apply()
        }
    }

    fun activeId(context: Context): Long = prefs(context).getLong(KEY_ACTIVE, 0L)

    fun setActiveProfile(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_ACTIVE, id).apply()
    }

    fun activeProfile(context: Context): ApiProfile? {
        val list = profiles(context)
        if (list.isEmpty()) return null
        return list.firstOrNull { it.id == activeId(context) } ?: list.first()
    }

    // ---------- 历史记录 ----------

    fun history(context: Context): MutableList<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        try {
            val array = JSONArray(prefs(context).getString(KEY_HISTORY, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    HistoryEntry(
                        id = o.optLong("id"),
                        source = o.optString("source"),
                        target = o.optString("target"),
                        input = o.optString("input"),
                        output = o.optString("output"),
                        time = o.optLong("time"),
                        image = o.optBoolean("image", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveHistory(context: Context, history: List<HistoryEntry>) {
        val array = JSONArray()
        for (h in history) {
            val o = JSONObject()
            o.put("id", h.id)
            o.put("source", h.source)
            o.put("target", h.target)
            o.put("input", h.input)
            o.put("output", h.output)
            o.put("time", h.time)
            o.put("image", h.image)
            array.put(o)
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun addHistory(
        context: Context,
        source: String,
        target: String,
        input: String,
        output: String,
        image: Boolean = false
    ) {
        val list = history(context)
        if (list.isNotEmpty()) {
            val newest = list[0]
            if (newest.input == input &&
                newest.source == source &&
                newest.target == target &&
                newest.image == image
            ) {
                list.removeAt(0)
            }
        }
        list.add(
            0,
            HistoryEntry(
                id = System.currentTimeMillis(),
                source = source,
                target = target,
                input = input,
                output = output,
                time = System.currentTimeMillis(),
                image = image
            )
        )
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        saveHistory(context, list)
    }

    fun deleteHistory(context: Context, id: Long) {
        saveHistory(context, history(context).filterNot { it.id == id })
    }

    fun clearHistory(context: Context) {
        saveHistory(context, emptyList())
    }

    // ---------- 语言 ----------

    /**
     * 首次升级到智能版本时，强制把已保存的目标语言切到「智能」；
     * 之后用户手动选择的目标语言会正常保留。
     */
    fun targetLang(context: Context): String {
        val p = prefs(context)
        if (!p.getBoolean(KEY_TARGET_SMART, false)) {
            p.edit()
                .putString(KEY_TARGET, Languages.SMART)
                .putBoolean(KEY_TARGET_SMART, true)
                .apply()
            return Languages.SMART
        }
        return Languages.normalizeTarget(
            p.getString(KEY_TARGET, Languages.SMART),
            customTargets(context)
        )
    }

    fun setTargetLang(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_TARGET, value)
            .putBoolean(KEY_TARGET_SMART, true)
            .apply()
    }

    // ---------- 自定义目标语言 ----------

    fun customTargets(context: Context): List<String> {
        val list = mutableListOf<String>()
        try {
            val array = JSONArray(prefs(context).getString(KEY_CUSTOM_TARGETS, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val name = array.optString(i).trim()
                if (name.isNotEmpty()) list.add(name)
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveCustomTargets(context: Context, list: List<String>) {
        val array = JSONArray()
        for (name in list) array.put(name)
        prefs(context).edit().putString(KEY_CUSTOM_TARGETS, array.toString()).apply()
    }

    /** 返回 false 表示名称为空、与内置语言重名或已存在。 */
    fun addCustomTarget(context: Context, name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty() || value == Languages.SMART || value == Languages.AUTO) return false
        if (Languages.target.contains(value)) return false
        val list = customTargets(context)
        if (list.contains(value)) return false
        saveCustomTargets(context, list + value)
        return true
    }

    fun removeCustomTarget(context: Context, name: String) {
        saveCustomTargets(context, customTargets(context).filterNot { it == name })
        // 当前选中的就是被删语言时，退回「智能」
        if (prefs(context).getString(KEY_TARGET, Languages.SMART) == name) {
            prefs(context).edit().putString(KEY_TARGET, Languages.SMART).apply()
        }
    }

    /** 内置 + 自定义，供选择对话框和校验使用。 */
    fun targetLanguages(context: Context): List<String> =
        Languages.targetWith(customTargets(context))

    // ---------- 提示词 ----------

    /** 空串表示使用内置默认提示词。 */
    fun promptText(context: Context): String =
        prefs(context).getString(KEY_PROMPT_TEXT, "").orEmpty()

    fun setPromptText(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_PROMPT_TEXT, normalizePrompt(value, Prompts.DEFAULT_TEXT))
            .apply()
    }

    fun promptImage(context: Context): String =
        prefs(context).getString(KEY_PROMPT_IMAGE, "").orEmpty()

    fun setPromptImage(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_PROMPT_IMAGE, normalizePrompt(value, Prompts.DEFAULT_IMAGE))
            .apply()
    }

    /** 与内置默认一致或为空时存空串，表示「使用默认」。 */
    private fun normalizePrompt(value: String, default: String): String {
        val body = value.trim()
        return if (body.isEmpty() || body == default.trim()) "" else value
    }
}
