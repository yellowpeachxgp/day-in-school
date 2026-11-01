package cn.edu.haut.campus.core.protocol.jinzhi

import java.net.URI

/**
 * 金智（jinzhi）部署差异几乎全在路径前缀上：同一个实现既可能挂在 /academic，
 * 也可能挂在 /jwglxt/jinzhi 之下。因此把 basePath 做成显式配置，
 * 由 SchoolProfile → AcademicSystemProfile.baseUrl 的**路径部分**填充。
 *
 * UNVERIFIED（未验证）：路径形态按公开部署惯例推导，未在任何真实学校站点验证。
 */
class JinzhiEndpoints(
    basePath: String,
    val loginEntry: String = "auth/login",
    val termsPath: String = "terms",
    val timetablePath: String = "timetable",
    val gradesPath: String = "grades",
    val examsPath: String = "exams"
) {

    /** 归一化后的前缀：以 / 开头、不以 / 结尾、根路径为空串。 */
    val basePath: String = normalizeBasePath(basePath)

    init {
        require(loginEntry.isNotBlank() && timetablePath.isNotBlank() && gradesPath.isNotBlank()) {
            "金智各功能路径不能为空"
        }
    }

    /** 相对路径 → 以 basePath 开头的绝对路径。 */
    fun path(relative: String): String {
        val tail = relative.trim().removePrefix("/")
        if (tail.isEmpty()) return basePath.ifEmpty { "/" }
        return if (basePath.isEmpty()) "/" + tail else basePath + "/" + tail
    }

    /** 拼出完整 URL；host 来自会话 origin，适配器本身不解析域名。 */
    fun url(origin: String, relative: String): String = origin.trimEnd('/') + path(relative)

    companion object {
        /** 从教务系统根地址推导 basePath；必须是 https，且不能带查询串或片段。 */
        fun fromBaseUrl(baseUrl: String): JinzhiEndpoints {
            val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("教务系统根地址不是合法 URL")
            require(uri.scheme == "https") { "教务系统根地址必须使用 HTTPS" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "教务系统根地址不能带查询串或片段" }
            require(uri.rawUserInfo == null) { "教务系统根地址不能包含用户信息" }
            return JinzhiEndpoints(uri.rawPath.orEmpty())
        }

        fun normalizeBasePath(raw: String): String {
            val trimmed = raw.trim().trim('/')
            return if (trimmed.isEmpty()) "" else "/" + trimmed
        }
    }
}
