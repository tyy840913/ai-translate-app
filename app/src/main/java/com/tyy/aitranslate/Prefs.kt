package com.tyy.aitranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "ai_translate_prefs"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_HISTORY = "history"
    private const val KEY_SOURCE = "source_lang"
    private const val KEY_TARGET = "target_lang"
    private const val MAX_HISTORY = 200

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
                        time = o.optLong("time")
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
            array.put(o)
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun addHistory(context: Context, source: String, target: String, input: String, output: String) {
        val list = history(context)
        if (list.isNotEmpty()) {
            val newest = list[0]
            if (newest.input == input && newest.source == source && newest.target == target) {
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
                time = System.currentTimeMillis()
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

    fun sourceLang(context: Context): String {
        val value = prefs(context).getString(KEY_SOURCE, Languages.AUTO)
        return if (value != null && Languages.source.contains(value)) value else Languages.AUTO
    }

    fun setSourceLang(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SOURCE, value).apply()
    }

    fun targetLang(context: Context): String {
        return Languages.normalizeTarget(prefs(context).getString(KEY_TARGET, "中文"))
    }

    fun setTargetLang(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TARGET, value).apply()
    }
}
