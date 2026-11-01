package cn.edu.haut.campus.core.protocol.qz

import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * UNVERIFIED（未验证）：老版 jsxsd 长期以 GBK 输出页面，这里按"页面自带 charset 声明"处理，
 * 未在任何真实学校站点上验证过具体声明值。
 *
 * 处理两条路径：
 * 1. 传输层已按声明正确解码 —— 文本含中日韩字符，原样返回；
 * 2. 传输层按 Latin-1/UTF-8 误解码 —— 文本是 GBK 字节被逐字节当字符读出的"乱码"，
 *    重新按单字节还原成字节流后用 GB18030 解码即可恢复，恢复失败则保留原文，
 *    绝不为了"看起来正确"而篡改原始数据。
 */
internal object QzTextCodec {

    private val GBK: Charset = Charset.forName("GBK")
    private val GB18030: Charset = Charset.forName("GB18030")

    /** 从 Content-Type 里取 charset；缺失或不受支持时返回 null。 */
    fun charsetOf(contentType: String?): Charset? {
        if (contentType.isNullOrBlank()) return null
        val index = contentType.lowercase(Locale.ROOT).indexOf("charset=")
        if (index < 0) return null
        val raw = contentType.substring(index + "charset=".length)
            .trim()
            .substringBefore(';')
            .trim()
            .trim('"', '\'')
        if (raw.isEmpty()) return null
        return runCatching { Charset.forName(raw) }.getOrNull()
    }

    fun isGbFamily(charset: Charset): Boolean = when (charset.name().uppercase(Locale.ROOT)) {
        "GBK", "GB2312", "GB18030", "CP936", "X-GBK" -> true
        else -> false
    }

    fun hasCjk(text: String): Boolean = text.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }

    /**
     * 把"GBK 字节被当成单字节字符"的文本还原。不是这种形态、或还原后没有中文时返回 null，
     * 由调用方保留原文，避免把正常文本改坏。
     */
    fun repairMojibake(text: String): String? {
        if (text.isEmpty()) return null
        if (text.any { it.code > 0xFF }) return null
        val bytes = ByteArray(text.length) { text[it].code.toByte() }
        val decoded = runCatching { String(bytes, GB18030) }.getOrNull() ?: return null
        if (!hasCjk(decoded)) return null
        if ('\uFFFD' in decoded) return null
        return decoded
    }

    /** 读取响应正文前的统一入口：只有声明为 GB 系且尚未含中文时才尝试修复。 */
    fun normalizeBody(body: String, contentType: String?): String {
        if (body.isEmpty()) return body
        val charset = charsetOf(contentType) ?: return body
        if (!isGbFamily(charset)) return body
        if (hasCjk(body)) return body
        return repairMojibake(body) ?: body
    }

    /** 表单值按页面字符集做百分号编码：老版 jsxsd 的请求体使用 GBK，而不是 UTF-8。 */
    fun gbkPercentEncode(value: String): String = URLEncoder.encode(value, GBK)

    /** UTF-8 表单编码：新版页面声明为 UTF-8，用 GBK 会破坏非 ASCII 参数。 */
    fun percentEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
