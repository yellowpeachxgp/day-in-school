package cn.edu.haut.campus.core.database

/** 校园内容自定义键值映射的换行+Tab 持久化编解码；语义与原私有实现完全一致。 */
internal fun decodeKeyValueMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split("\n").mapNotNull { line ->
        val idx = line.indexOf('\t')
        if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
    }.toMap()
}

internal fun encodeKeyValueMap(map: Map<String, String>): String {
    return map.entries.joinToString("\n") { (k, v) ->
        k.replace("\t", " ").replace("\n", " ") + "\t" + v.replace("\n", "").trim()
    }
}
