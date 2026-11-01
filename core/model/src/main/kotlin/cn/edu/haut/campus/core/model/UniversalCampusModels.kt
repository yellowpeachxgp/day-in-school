package cn.edu.haut.campus.core.model

import java.time.Instant
import java.time.ZoneId

/** 稳定的学校目录 ID；不能使用教务域名或学号作为学校主键。 */
@JvmInline
value class SchoolId(val value: String) {
    init { requireValidIdentifier(value) }
}

/** 校区目录 ID。 */
@JvmInline
value class CampusId(val value: String) {
    init { requireValidIdentifier(value) }
}

/** 本地不透明账号 ID；不得把真实学号放入此值。 */
@JvmInline
value class AccountId(val value: String) {
    init { requireValidIdentifier(value) }
}

/** 学校、账号和可选校区组成的本地 Profile ID。 */
@JvmInline
value class ProfileId(val value: String) {
    init { requireValidIdentifier(value) }
}

/** 教务系统的原始学期 ID，和本地 Term.id 分开保存。 */
@JvmInline
value class SourceTermId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 256) { "来源学期 ID 为空或过长" } }
}

private fun requireValidIdentifier(value: String) {
    require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "_-" }) {
        "标识符必须由字母、数字、下划线或短横线组成，且长度不超过 128"
    }
}

data class SchoolAccountScope(
    val schoolId: SchoolId,
    val accountId: AccountId,
    val profileId: ProfileId,
    val campusId: CampusId? = null
)

data class CampusProfile(
    val id: CampusId,
    val name: String,
    val aliases: List<String> = emptyList(),
    val timeZone: ZoneId = ZoneId.of("Asia/Shanghai"),
    val isPrimary: Boolean = false
) {
    init { require(name.isNotBlank()) { "校区名称不能为空" } }
}

/** 蓝图中的九类协议只是可识别的协议家族类型，不表示项目已经实现了该适配器。 */
enum class AcademicProtocolFamily {
    ZF_MODERN,
    ZF_LEGACY,
    QZ_MODERN,
    QZ_LEGACY,
    JINZHI,
    CHENGFANG,
    KINGOSOFT,
    EAMS,
    CHAOXING_ACADEMIC
}

enum class CapabilityKind {
    ACADEMIC_TERMS_READ,
    ACADEMIC_CALENDAR_READ,
    TIMETABLE_READ,
    GRADES_READ,
    GRADE_DETAIL_READ,
    EXAMS_READ,
    TRAINING_PLAN_READ,
    SELECTION_QUERY,
    SELECTION_WRITE,
    CAMPUS_MAP,
    CAMPUS_BUILDINGS,
    LIBRARY_CATALOG,
    LIBRARY_SEATS,
    UTILITIES,
    ROOM_FINDER,
    COMMUNITY
}

/** 证据等级只描述证据，不等价于当前账号已经可以使用。 */
enum class VerificationLevel {
    SOURCE_CONFIRMED,
    MOCK_TESTED,
    DEVICE_TESTED,
    SCHOOL_VERIFIED,
    DISTRIBUTION_READY
}

enum class CapabilityState {
    ENABLED,
    NEEDS_USER_CONSENT,
    NEEDS_USER_LOGIN,
    NEEDS_VERIFICATION,
    NOT_OPEN,
    UNSUPPORTED,
    REVOKED
}

data class CapabilityEvidence(
    val level: VerificationLevel,
    val verifiedAt: Instant? = null,
    val evidenceId: String? = null,
    val sourceReference: String? = null,
    val note: String? = null
)

/**
 * ProviderSupports、SchoolVerified、UserConsent 和 prerequisitesReady 四个维度必须分开保存。
 * 只有四者均满足时，isAvailable 才为 true。
 */
data class CapabilityStatus(
    val kind: CapabilityKind,
    val providerSupports: Boolean,
    val verifiedForAccount: Boolean = false,
    val userConsent: Boolean = false,
    val prerequisitesReady: Boolean = false,
    val evidence: CapabilityEvidence? = null,
    val explicitState: CapabilityState? = null
) {
    val isAvailable: Boolean
        get() = providerSupports && verifiedForAccount && userConsent && prerequisitesReady &&
            state == CapabilityState.ENABLED

    val state: CapabilityState
        get() = when {
            explicitState != null && explicitState != CapabilityState.ENABLED -> explicitState
            !providerSupports -> CapabilityState.UNSUPPORTED
            !verifiedForAccount -> CapabilityState.NEEDS_VERIFICATION
            !userConsent -> CapabilityState.NEEDS_USER_CONSENT
            !prerequisitesReady -> CapabilityState.NEEDS_USER_LOGIN
            else -> CapabilityState.ENABLED
        }
}

data class AcademicSystemProfile(
    val providerId: String,
    val protocolFamily: AcademicProtocolFamily,
    val baseUrl: String,
    val allowOrigins: Set<String> = emptySet(),
    val capabilityStatuses: Map<CapabilityKind, CapabilityStatus> = emptyMap(),
    val sourceReference: String? = null,
    val systemId: String = providerId
) {
    init {
        require(providerId.isNotBlank()) { "提供者 ID 不能为空" }
        require(systemId.isNotBlank()) { "教务实例 ID 不能为空" }
        require(capabilityStatuses.all { (kind, status) -> kind == status.kind }) { "能力键与声明不一致" }
    }

    fun capability(kind: CapabilityKind): CapabilityStatus = capabilityStatuses[kind]
        ?: CapabilityStatus(kind = kind, providerSupports = false)
}

data class SchoolProfile(
    val id: SchoolId,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val searchKeywords: List<String> = emptyList(),
    val province: String? = null,
    val city: String? = null,
    val campuses: List<CampusProfile> = emptyList(),
    val academicSystems: List<AcademicSystemProfile> = emptyList(),
    val verification: CapabilityEvidence? = null,
    val officialWebsite: String? = null
) {
    init {
        require(displayName.isNotBlank()) { "学校名称不能为空" }
        require(campuses.map { it.id }.distinct().size == campuses.size) { "学校包含重复校区 ID" }
        require(campuses.count { it.isPrimary } <= 1) { "学校只能设置一个首选校区" }
        require(academicSystems.map { it.systemId }.distinct().size == academicSystems.size) {
            "学校包含重复教务实例 ID"
        }
    }

    val primaryCampus: CampusProfile?
        get() = campuses.firstOrNull { it.isPrimary } ?: campuses.firstOrNull()

    fun allSearchTerms(): List<String> = buildList {
        add(displayName)
        addAll(aliases)
        addAll(searchKeywords)
        addAll(campuses.flatMap { listOf(it.name) + it.aliases })
    }.filter { it.isNotBlank() }.distinct()
}

enum class SnapshotCompleteness {
    COMPLETE,
    EMPTY_CONFIRMED,
    PARTIAL,
    UNKNOWN
}

enum class Staleness {
    FRESH,
    STALE,
    UNKNOWN
}

data class DataProvenance(
    val providerId: String,
    val schoolId: SchoolId,
    val accountId: AccountId,
    val sourceSystem: String,
    val sourceRecordId: String? = null,
    val fetchedAt: Instant? = null,
    val completeness: SnapshotCompleteness = SnapshotCompleteness.UNKNOWN,
    val trust: VerificationLevel = VerificationLevel.SOURCE_CONFIRMED,
    val schemaVersion: Int = 1,
    val contentRevision: String? = null
)

enum class UserAction {
    LOGIN,
    REAUTHENTICATE,
    COMPLETE_CAPTCHA,
    CHOOSE_PROVIDER,
    GRANT_CONSENT,
    OPEN_OFFICIAL_WEB,
    REVIEW_PARTIAL_DATA,
    VERIFY_OFFICIAL_RESULT
}

enum class AcademicError {
    NETWORK_OFFLINE,
    TIMEOUT,
    TLS_ERROR,
    UNTRUSTED_REDIRECT,
    SESSION_EXPIRED,
    AUTH_REJECTED,
    CAPTCHA_REQUIRED,
    MANUAL_VERIFICATION,
    ACCESS_DENIED,
    RATE_LIMITED,
    NOT_OPEN,
    UNSUPPORTED,
    PAGE_CHANGED,
    PARTIAL_RESPONSE,
    PAGINATION_LOOP,
    STALE_SNAPSHOT,
    CONFLICT,
    CAPACITY_UNKNOWN,
    WRITE_RESULT_UNKNOWN,
    SOURCE_DATA_INVALID,
    PERMISSION_REVOKED,
    PLUGIN_UNTRUSTED,
    PLUGIN_CRASHED
}

sealed interface AcademicResult<out T> {
    data class Content<T>(
        val value: T,
        val source: DataProvenance,
        val staleness: Staleness = Staleness.FRESH
    ) : AcademicResult<T>

    data class Empty(
        val reason: String,
        val authoritative: Boolean
    ) : AcademicResult<Nothing>

    data class RequiresAction(
        val action: UserAction,
        val reason: String
    ) : AcademicResult<Nothing>

    data class Failure(
        val code: AcademicError,
        val retryable: Boolean,
        val detail: String? = null
    ) : AcademicResult<Nothing>
}
