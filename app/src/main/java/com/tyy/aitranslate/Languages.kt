package com.tyy.aitranslate

object Languages {
    const val AUTO = "自动检测"

    val source: List<String> = listOf(
        AUTO,
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

    val target: List<String> = source.drop(1)

    fun normalizeTarget(value: String?): String {
        return if (value != null && target.contains(value)) value else "中文"
    }
}
