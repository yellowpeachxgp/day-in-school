package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicSystemProfile
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CampusProfile
import cn.edu.haut.campus.core.model.CapabilityEvidence
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.CapabilityStatus
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.VerificationLevel
import java.text.Normalizer
import java.util.Locale

data class SchoolSearchResult(
    val school: SchoolProfile,
    val matchedTerms: List<String>,
    val approximate: Boolean = false
)

/**
 * 不发网络请求的学校目录。搜索关键词来自显式目录元数据，不猜测学校或协议兼容性。
 * 自定义覆盖只按稳定学校 ID 合并；同名不同校保持独立。
 */
class SchoolDirectory(
    catalog: List<SchoolProfile>,
    localOverrides: List<SchoolProfile> = emptyList()
) {
    init {
        requireUniqueSchools(catalog)
        requireUniqueSchools(localOverrides)
    }

    private val schoolById = LinkedHashMap<SchoolId, SchoolProfile>().apply {
        catalog.forEach { put(it.id, it) }
        localOverrides.forEach { put(it.id, it) }
    }

    val schools: List<SchoolProfile> = schoolById.values.toList()

    fun find(id: SchoolId): SchoolProfile? = schoolById[id]

    /** 精确校名优先，其次别名、前缀、子串；仅对足够长的拉丁词启用一次编辑纠错。 */
    fun search(
        query: String,
        province: String? = null,
        city: String? = null,
        protocolFamily: AcademicProtocolFamily? = null
    ): List<SchoolSearchResult> {
        val normalizedQuery = normalize(query.take(MAX_QUERY_LENGTH))
        val provinceFilter = province?.let(::normalize)?.takeIf(String::isNotBlank)
        val cityFilter = city?.let(::normalize)?.takeIf(String::isNotBlank)
        return schools.asSequence()
            .filter { provinceFilter == null || normalize(it.province.orEmpty()) == provinceFilter }
            .filter { cityFilter == null || normalize(it.city.orEmpty()) == cityFilter }
            .filter { school ->
                protocolFamily == null || school.academicSystems.any { it.protocolFamily == protocolFamily }
            }
            .mapNotNull schoolMatch@ { school ->
                if (normalizedQuery.isEmpty()) {
                    return@schoolMatch RankedResult(SchoolSearchResult(school, emptyList()), 0)
                }
                val matches = school.allSearchTerms().mapNotNull termMatch@ { term ->
                    val normalizedTerm = normalize(term)
                    val score = when {
                        normalizedTerm == normalizedQuery && term == school.displayName -> 500
                        normalizedTerm == normalizedQuery -> 400
                        normalizedTerm.startsWith(normalizedQuery) -> 300
                        normalizedQuery in normalizedTerm -> 200
                        isOneEditLatinMatch(normalizedQuery, normalizedTerm) -> 100
                        else -> return@termMatch null
                    }
                    term to score
                }
                val score = matches.maxOfOrNull { it.second } ?: return@schoolMatch null
                RankedResult(
                    result = SchoolSearchResult(
                        school = school,
                        matchedTerms = matches.filter { it.second == score }.map { it.first },
                        approximate = score == 100
                    ),
                    score = score
                )
            }
            .sortedWith(compareByDescending<RankedResult> { it.score }.thenBy { it.result.school.id.value })
            .map { it.result }
            .toList()
    }

    private data class RankedResult(val result: SchoolSearchResult, val score: Int)

    companion object {
        const val MAX_QUERY_LENGTH = 256
        val HAUT_SCHOOL_ID = SchoolId("haut")
        val HAUT_LIANHUAJIE_CAMPUS_ID = CampusId("haut-lianhuajie")

        /** 仅记录仓库已存在的工大实现；无本轮授权账户与设备证据，不授予学校验证等级。 */
        fun builtIn(localOverrides: List<SchoolProfile> = emptyList()): SchoolDirectory = SchoolDirectory(
            catalog = listOf(hautProfile()),
            localOverrides = localOverrides
        )

        fun hautProfile(): SchoolProfile {
            val evidence = CapabilityEvidence(
                level = VerificationLevel.SOURCE_CONFIRMED,
                sourceReference = "core/academic/src/main/kotlin/cn/edu/haut/campus/core/academic/HautAcademicProvider.kt@fc2adb1",
                note = "已有工大实现；当前仅确认源码，尚未完成本轮设备与授权账号验收"
            )
            val supported = setOf(
                CapabilityKind.TIMETABLE_READ,
                CapabilityKind.GRADES_READ,
                CapabilityKind.EXAMS_READ,
                CapabilityKind.ACADEMIC_CALENDAR_READ,
                CapabilityKind.CAMPUS_MAP,
                CapabilityKind.CAMPUS_BUILDINGS,
                CapabilityKind.ROOM_FINDER,
                CapabilityKind.LIBRARY_CATALOG,
                CapabilityKind.LIBRARY_SEATS,
                CapabilityKind.UTILITIES
            ).associateWith { CapabilityStatus(kind = it, providerSupports = true, evidence = evidence) }
            return SchoolProfile(
                id = HAUT_SCHOOL_ID,
                displayName = "河南工业大学",
                aliases = listOf("河南工大", "Henan University of Technology", "HAUT"),
                searchKeywords = listOf("henangongyedaxue", "hngydx", "henangongda", "hngd"),
                province = "河南",
                city = "郑州",
                campuses = listOf(
                    CampusProfile(
                        id = HAUT_LIANHUAJIE_CAMPUS_ID,
                        name = "莲花街校区",
                        aliases = listOf("lianhuajie", "lhj"),
                        isPrimary = true
                    )
                ),
                academicSystems = listOf(
                    AcademicSystemProfile(
                        providerId = "haut-zf-modern",
                        protocolFamily = AcademicProtocolFamily.ZF_MODERN,
                        baseUrl = "https://jwglxt.haut.edu.cn",
                        allowOrigins = setOf("https://jwglxt.haut.edu.cn", "https://mapp.haut.edu.cn"),
                        capabilityStatuses = supported,
                        sourceReference = evidence.sourceReference,
                        systemId = "haut-academic"
                    )
                ),
                verification = evidence,
                officialWebsite = "https://www.haut.edu.cn"
            )
        }

        private fun requireUniqueSchools(schools: List<SchoolProfile>) {
            require(schools.map { it.id }.distinct().size == schools.size) { "学校目录包含重复学校 ID" }
        }

        private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

        private fun isOneEditLatinMatch(query: String, candidate: String): Boolean {
            if (query.length < 4 || candidate.length < 4) return false
            if (query.any { it !in 'a'..'z' } || candidate.any { it !in 'a'..'z' }) return false
            if (kotlin.math.abs(query.length - candidate.length) > 1) return false
            var queryIndex = 0
            var candidateIndex = 0
            var edits = 0
            while (queryIndex < query.length && candidateIndex < candidate.length) {
                if (query[queryIndex] == candidate[candidateIndex]) {
                    queryIndex++
                    candidateIndex++
                } else {
                    if (++edits > 1) return false
                    when {
                        query.length > candidate.length -> queryIndex++
                        query.length < candidate.length -> candidateIndex++
                        else -> { queryIndex++; candidateIndex++ }
                    }
                }
            }
            return edits + (query.length - queryIndex) + (candidate.length - candidateIndex) <= 1
        }
    }
}
