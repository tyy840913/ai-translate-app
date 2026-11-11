package com.tyy.aitranslate

data class ApiProfile(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
)

data class HistoryEntry(
    val id: Long,
    val source: String,
    val target: String,
    val input: String,
    val output: String,
    val time: Long
)
