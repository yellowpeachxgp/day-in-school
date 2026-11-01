package cn.edu.haut.campus.core.campusservices

import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.CapabilityEvidence
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.CapabilityState
import cn.edu.haut.campus.core.model.CapabilityStatus
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import org.json.JSONObject

/**
 * 校园非教务服务（馆藏检索、图书馆选座、宿舍水电）的通用 Provider 描述与契约。
 *
 * 设计约束：
 * 1. 非工大学校不会隐式继承工大域名或工大水电 `customercode=43`；
 * 2. 未绑定对应 Provider 的学校显式返回 [CapabilityState.UNSUPPORTED]；
 * 3. 工大自带 Provider 标记为 [VerificationLevel.SOURCE_CONFIRMED]，需账号/设备就绪后启用。
 */
data class CampusServiceDescriptor(
    val providerId: String,
    val schoolId: SchoolId,
    val kind: CapabilityKind,
    val displayName: String,
    val allowedHosts: Set<String>,
    val evidence: CapabilityEvidence
)

interface LibraryCatalogProvider {
    val descriptor: CampusServiceDescriptor
    val indexUrl: String
    val searchUrl: String
    fun isAllowedMainFrameUrl(rawUrl: String): Boolean
    fun isAllowedResourceUrl(rawUrl: String): Boolean
    fun sanitizeCoverUrl(rawUrl: String?): String
    fun parsePageSnapshot(rawJson: String, pageGeneration: Long): CatalogPageSnapshot?
}

interface LibrarySeatProvider {
    val descriptor: CampusServiceDescriptor
    val entryUrl: String
    fun isAllowedMainFrameUrl(rawUrl: String): Boolean
    fun isSeatIndexDocument(rawUrl: String?): Boolean
    fun isCasAuthUrl(rawUrl: String?): Boolean
    val returnToSeatsScript: String
    val forceContinueSeatsScript: String
}

interface UtilityServiceProvider {
    val descriptor: CampusServiceDescriptor
    val customerCode: String
    val endpointUrl: String
    fun parseEnvelopeBody(rawJson: String): JSONObject
    fun parseBoundRooms(body: JSONObject): List<WanxiaoBoundRoomCredential>
    fun parseDevices(body: JSONObject): List<OnlineUtilityDevice>
}

object HautLibraryCatalogProvider : LibraryCatalogProvider {
    override val descriptor = CampusServiceDescriptor(
        providerId = "haut-libspace-catalog",
        schoolId = SchoolDirectory.HAUT_SCHOOL_ID,
        kind = CapabilityKind.LIBRARY_CATALOG,
        displayName = "河南工业大学图书馆馆藏检索 (LibSpace)",
        allowedHosts = setOf(OfficialServiceRoutePolicy.LIBSPACE_HOST),
        evidence = CapabilityEvidence(
            level = VerificationLevel.SOURCE_CONFIRMED,
            sourceReference = "core/campusservices/src/main/kotlin/cn/edu/haut/campus/core/campusservices/LibraryCatalogSnapshot.kt",
            note = "工大 LibSpace 馆藏只读快照适配"
        )
    )
    override val indexUrl: String = OfficialServiceRoutePolicy.CATALOG_INDEX_URL
    override val searchUrl: String = OfficialServiceRoutePolicy.CATALOG_SEARCH_URL

    override fun isAllowedMainFrameUrl(rawUrl: String): Boolean =
        OfficialServiceRoutePolicy.isAllowedMainFrameUrl(rawUrl)

    override fun isAllowedResourceUrl(rawUrl: String): Boolean =
        OfficialServiceRoutePolicy.isAllowedCatalogResourceUrl(rawUrl)

    override fun sanitizeCoverUrl(rawUrl: String?): String =
        OfficialServiceRoutePolicy.sanitizeCoverUrl(rawUrl)

    override fun parsePageSnapshot(rawJson: String, pageGeneration: Long): CatalogPageSnapshot? =
        LibraryCatalogSnapshot.parseSnapshot(rawJson, pageGeneration)
}

object HautLibrarySeatProvider : LibrarySeatProvider {
    override val descriptor = CampusServiceDescriptor(
        providerId = "haut-wslib-seats",
        schoolId = SchoolDirectory.HAUT_SCHOOL_ID,
        kind = CapabilityKind.LIBRARY_SEATS,
        displayName = "河南工业大学图书馆座位预约 (wslib)",
        allowedHosts = setOf(
            OfficialServiceRoutePolicy.WSLIB_HOST,
            OfficialServiceRoutePolicy.MAPP_CAS_HOST
        ),
        evidence = CapabilityEvidence(
            level = VerificationLevel.SOURCE_CONFIRMED,
            sourceReference = "core/campusservices/src/main/kotlin/cn/edu/haut/campus/core/campusservices/OfficialLibraryWebAdapter.kt",
            note = "工大图书馆 H5 选座与统一认证跳转适配"
        )
    )
    override val entryUrl: String = OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL

    override fun isAllowedMainFrameUrl(rawUrl: String): Boolean =
        OfficialServiceRoutePolicy.isAllowedMainFrameUrl(rawUrl)

    override fun isSeatIndexDocument(rawUrl: String?): Boolean =
        OfficialServiceRoutePolicy.isLibrarySeatIndexDocument(rawUrl)

    override fun isCasAuthUrl(rawUrl: String?): Boolean =
        OfficialServiceRoutePolicy.isSchoolCasAuthUrl(rawUrl)

    override val returnToSeatsScript: String = OfficialWebScripts.LIBRARY_RETURN_TO_SEATS_SCRIPT
    override val forceContinueSeatsScript: String = OfficialWebScripts.LIBRARY_FORCE_CONTINUE_SEATS_SCRIPT
}

object HautWanxiaoUtilityProvider : UtilityServiceProvider {
    override val descriptor = CampusServiceDescriptor(
        providerId = "haut-wanxiao-utility",
        schoolId = SchoolDirectory.HAUT_SCHOOL_ID,
        kind = CapabilityKind.UTILITIES,
        displayName = "河南工业大学完美校园智能水电",
        allowedHosts = setOf(OfficialServiceRoutePolicy.WANXIAO_HOST),
        evidence = CapabilityEvidence(
            level = VerificationLevel.SOURCE_CONFIRMED,
            sourceReference = "core/campusservices/src/main/kotlin/cn/edu/haut/campus/core/campusservices/WanxiaoProtocolParser.kt",
            note = "工大水电只读协议解析（在线传输默认待核验门控）"
        )
    )
    override val customerCode: String = WanxiaoFormPostTransport.HAUT_CUSTOMER_CODE
    override val endpointUrl: String = WanxiaoFormPostTransport.WANXIAO_ENDPOINT_URL

    override fun parseEnvelopeBody(rawJson: String): JSONObject =
        WanxiaoProtocolParser.parseEnvelopeBody(rawJson)

    override fun parseBoundRooms(body: JSONObject): List<WanxiaoBoundRoomCredential> =
        WanxiaoProtocolParser.parseBoundRooms(body)

    override fun parseDevices(body: JSONObject): List<OnlineUtilityDevice> =
        WanxiaoProtocolParser.parseDevices(body)
}

class CampusServiceProviderRegistry(
    catalogProviders: List<LibraryCatalogProvider> = listOf(HautLibraryCatalogProvider),
    seatProviders: List<LibrarySeatProvider> = listOf(HautLibrarySeatProvider),
    utilityProviders: List<UtilityServiceProvider> = listOf(HautWanxiaoUtilityProvider)
) {
    private val catalogBySchool = catalogProviders.associateBy { it.descriptor.schoolId }
    private val seatBySchool = seatProviders.associateBy { it.descriptor.schoolId }
    private val utilityBySchool = utilityProviders.associateBy { it.descriptor.schoolId }

    fun libraryCatalog(schoolId: SchoolId): LibraryCatalogProvider? = catalogBySchool[schoolId]

    fun librarySeats(schoolId: SchoolId): LibrarySeatProvider? = seatBySchool[schoolId]

    fun utilities(schoolId: SchoolId): UtilityServiceProvider? = utilityBySchool[schoolId]

    fun capabilityStatus(
        schoolId: SchoolId,
        kind: CapabilityKind,
        verifiedForAccount: Boolean = false,
        userConsent: Boolean = false,
        prerequisitesReady: Boolean = false
    ): CapabilityStatus {
        val descriptor = when (kind) {
            CapabilityKind.LIBRARY_CATALOG -> catalogBySchool[schoolId]?.descriptor
            CapabilityKind.LIBRARY_SEATS -> seatBySchool[schoolId]?.descriptor
            CapabilityKind.UTILITIES -> utilityBySchool[schoolId]?.descriptor
            else -> null
        } ?: return CapabilityStatus(
            kind = kind,
            providerSupports = false,
            explicitState = CapabilityState.UNSUPPORTED
        )
        return CapabilityStatus(
            kind = kind,
            providerSupports = true,
            verifiedForAccount = verifiedForAccount,
            userConsent = userConsent,
            prerequisitesReady = prerequisitesReady,
            evidence = descriptor.evidence
        )
    }

    companion object {
        val Default = CampusServiceProviderRegistry()
    }
}
