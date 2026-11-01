package cn.edu.haut.campus.core.protocol.zf

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.util.Locale

/** 协议层需要的四类只读入口。 */
internal enum class ZfPage(val label: String) {
    TERMS("学期"),
    TIMETABLE("课表"),
    GRADES("成绩"),
    EXAMS("考试")
}

/**
 * 从首页 HTML 里发现的菜单入口（地址已解析成绝对 URL）。
 *
 * 菜单 ID（gnmkdm 一类）永远来自页面本身，不在代码里写死；这也是“学校升级后菜单 ID 变化”
 * 时唯一可靠的来源。UNVERIFIED：识别规则按公开页面形态推导，未在真实学校核对。
 */
internal class ZfMenuIndex(private val links: Map<ZfPage, String>) {

    fun link(page: ZfPage): String? = links[page]

    fun isEmpty(): Boolean = links.isEmpty()

    fun size(): Int = links.size

    /**
     * 缺少入口时区分两种情况：
     * 整页没有任何可识别菜单 → 页面结构变了（PAGE_CHANGED）；
     * 有其它菜单但没有这一项 → 该部署没有开放此功能（UNSUPPORTED）。
     */
    fun require(page: ZfPage): String {
        links[page]?.let { return it }
        if (links.isEmpty()) {
            throw TransportFailure(AcademicError.PAGE_CHANGED, "教务首页未发现可识别的功能菜单，页面结构可能已变化")
        }
        throw TransportFailure(AcademicError.UNSUPPORTED, "当前教务部署没有提供" + page.label + "入口")
    }
}

internal object ZfMenu {

    private val SKIPPED_PREFIXES = listOf("javascript:", "#", "mailto:", "tel:", "data:")

    /** 路径片段命中优先，链接文本兜底；顺序即优先级。 */
    private val RULES: Map<ZfPage, List<String>> = linkedMapOf(
        ZfPage.TERMS to listOf("cxxnxq", "xqcx", "学期"),
        ZfPage.TIMETABLE to listOf("xskbcx", "kbcx", "课表"),
        ZfPage.GRADES to listOf("xscjcx", "cjcx", "成绩"),
        ZfPage.EXAMS to listOf("xskscx", "kscx", "考试")
    )

    fun discover(html: String, origin: String): ZfMenuIndex {
        val found = LinkedHashMap<ZfPage, String>()
        for (link in ZfHtml.links(html)) {
            val href = link.href.trim()
            if (href.isEmpty()) continue
            if (SKIPPED_PREFIXES.any { href.lowercase(Locale.ROOT).startsWith(it) }) continue
            val haystack = (href + " " + link.text).lowercase(Locale.ROOT)
            for ((page, patterns) in RULES) {
                if (found.containsKey(page)) continue
                if (patterns.any { haystack.contains(it) }) {
                    found[page] = ZfUrls.absolute(origin, href)
                }
            }
        }
        return ZfMenuIndex(found)
    }
}
