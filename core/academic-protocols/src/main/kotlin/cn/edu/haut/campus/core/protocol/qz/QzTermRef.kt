package cn.edu.haut.campus.core.protocol.qz

/**
 * UNVERIFIED（未验证）：强智系列用 (xnm, xqm) 两个参数定位学期，但不同部署的取值含义并不一致，
 * 常见约定"3=第一学期"只是其中一种说法。因此本类型**只保留原始编号**：
 * 学期名称一律取页面上的可见文本，绝不把某个编号翻译成"第一/第二学期"。
 */
internal data class QzTermRef(val academicYearCode: String, val termCode: String) {

    /** 原始来源学期 ID：year:code，可无损还原成 xnm/xqm 两个请求参数。 */
    val sourceId: String get() = academicYearCode + ":" + termCode

    init {
        require(academicYearCode.isNotBlank() && termCode.isNotBlank()) { "学期编码不能为空" }
        require(':' !in academicYearCode && ':' !in termCode) { "学期编码不能包含冒号" }
        require(academicYearCode.length <= 32 && termCode.length <= 32) { "学期编码过长" }
    }

    companion object {
        fun of(academicYearCode: String?, termCode: String?): QzTermRef? {
            val year = academicYearCode?.trim().orEmpty()
            val code = termCode?.trim().orEmpty()
            if (year.isEmpty() || code.isEmpty()) return null
            return runCatching { QzTermRef(year, code) }.getOrNull()
        }

        fun parse(sourceId: String?): QzTermRef? {
            if (sourceId.isNullOrBlank()) return null
            val separator = sourceId.indexOf(':')
            if (separator <= 0 || separator >= sourceId.length - 1) return null
            return of(sourceId.substring(0, separator), sourceId.substring(separator + 1))
        }
    }
}

/** 仅解析链接里的查询串：不做相对地址解析，跳转策略由 GatewayUrlPolicy 统一负责。 */
internal object QzQuery {

    fun parameters(raw: String): Map<String, String> {
        val question = raw.indexOf('?')
        if (question < 0) return emptyMap()
        val tail = raw.substring(question + 1).substringBefore('#')
        if (tail.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in tail.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val name = if (separator < 0) pair else pair.substring(0, separator)
            val value = if (separator < 0) "" else pair.substring(separator + 1)
            val decodedName = decode(name)
            if (decodedName.isEmpty()) continue
            result.putIfAbsent(decodedName, decode(value))
        }
        return result
    }

    private fun decode(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw)
}
