/*
 * Clean-room refactored parcel parsing and deduplication engine.
 * Reference studied: ExpressAssistant (https://github.com/Halo0sama/ExpressAssistant, commit b892565502d381a58a5a2c57e6d9068e07648c7a, MIT License).
 * See THIRD_PARTY_NOTICES.md and docs/provenance/PARCEL_HUB_UPSTREAM.md.
 */
package cn.edu.haut.campus.core.campusservices

enum class ParcelAuthorityStatus(val label: String) {
    PRODUCTION_ENABLED("正式启用"),
    MANUAL_IMPORT_ONLY("仅限手动/客户端导入"),
    RESEARCH_SANDBOX_ONLY("仅限沙箱研究 (生产禁用)"),
    DISABLED("已停用")
}

data class ParcelProviderSpec(
    val providerId: String,
    val displayName: String,
    val authorityStatus: ParcelAuthorityStatus,
    val allowBackgroundCloudPoll: Boolean,
    val allowClientImport: Boolean,
    val sourcePriority: Int,
    val description: String
)

enum class ParcelLifecycleStatus(val wireName: String, val label: String, val rank: Int) {
    CREATED("CREATED", "已揽收", 10),
    IN_TRANSIT("IN_TRANSIT", "运输中", 20),
    OUT_FOR_DELIVERY("OUT_FOR_DELIVERY", "派送中", 30),
    EXCEPTION("EXCEPTION", "物流异常", 35),
    READY_FOR_PICKUP("READY_FOR_PICKUP", "待取件", 40),
    PICKED_UP("PICKED_UP", "已取件", 50);

    companion object {
        fun fromWire(raw: String?): ParcelLifecycleStatus =
            entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) } ?: IN_TRANSIT
    }
}

data class ExtractedPickupInfo(
    val pickupCode: String,
    val codeType: String, // SHELF_CODE | CABINET_PIN
    val stationName: String?
)

object ParcelHubEngine {

    val PROVIDER_AUTHORITY_MATRIX: Map<String, ParcelProviderSpec> = linkedMapOf(
        "parcel.campus-station" to ParcelProviderSpec(
            providerId = "parcel.campus-station",
            displayName = "授权校园驿站插件数据源",
            authorityStatus = ParcelAuthorityStatus.PRODUCTION_ENABLED,
            allowBackgroundCloudPoll = true,
            allowClientImport = true,
            sourcePriority = 100,
            description = "通过已签名驿站插件同步莲花街/嵩山路校区驿站状态"
        ),
        "parcel.notification-parser" to ParcelProviderSpec(
            providerId = "parcel.notification-parser",
            displayName = "本地短信/通知取件码解析",
            authorityStatus = ParcelAuthorityStatus.PRODUCTION_ENABLED,
            allowBackgroundCloudPoll = false,
            allowClientImport = true,
            sourcePriority = 90,
            description = "设备端离线解析驿站/丰巢取件短信，自动过滤银行与登录验证码"
        ),
        "parcel.manual" to ParcelProviderSpec(
            providerId = "parcel.manual",
            displayName = "手动录入 / 运单号添加",
            authorityStatus = ParcelAuthorityStatus.PRODUCTION_ENABLED,
            allowBackgroundCloudPoll = false,
            allowClientImport = true,
            sourcePriority = 80,
            description = "用户手动输入运单号、取件码与商品备注"
        ),
        "parcel.kd100" to ParcelProviderSpec(
            providerId = "parcel.kd100",
            displayName = "快递100 (用户自带授权 Key)",
            authorityStatus = ParcelAuthorityStatus.PRODUCTION_ENABLED,
            allowBackgroundCloudPoll = false,
            allowClientImport = true,
            sourcePriority = 70,
            description = "用户配置自有授权 API Key 后按需查询物流轨迹"
        ),
        "parcel.xiaomi.personal-assistant" to ParcelProviderSpec(
            providerId = "parcel.xiaomi.personal-assistant",
            displayName = "小米智能助理 (手机号绑定快递聚合)",
            authorityStatus = ParcelAuthorityStatus.PRODUCTION_ENABLED,
            allowBackgroundCloudPoll = false,
            allowClientImport = true,
            sourcePriority = 95,
            description = "支持应用内登录小米账号并通过短信验证码绑定收件手机号，直接拉取手机号名下菜鸟/顺丰/京东/小米等全渠道快递"
        ),
        "parcel.jd.orders" to ParcelProviderSpec(
            providerId = "parcel.jd.orders",
            displayName = "京东订单物流快照",
            authorityStatus = ParcelAuthorityStatus.MANUAL_IMPORT_ONLY,
            allowBackgroundCloudPoll = false,
            allowClientImport = true,
            sourcePriority = 60,
            description = "仅支持用户手动粘贴/导入订单物流快照，独立会话隔离"
        ),
        "parcel.taobao.cainiao" to ParcelProviderSpec(
            providerId = "parcel.taobao.cainiao",
            displayName = "淘宝/菜鸟包裹 (仅限沙箱)",
            authorityStatus = ParcelAuthorityStatus.RESEARCH_SANDBOX_ONLY,
            allowBackgroundCloudPoll = false,
            allowClientImport = false,
            sourcePriority = 55,
            description = "未获平台商业授权前生产环境默认禁用 (Fail-Closed)"
        ),
        "parcel.pdd.orders" to ParcelProviderSpec(
            providerId = "parcel.pdd.orders",
            displayName = "拼多多包裹 (仅限沙箱)",
            authorityStatus = ParcelAuthorityStatus.RESEARCH_SANDBOX_ONLY,
            allowBackgroundCloudPoll = false,
            allowClientImport = false,
            sourcePriority = 50,
            description = "未获平台商业授权前生产环境默认禁用 (Fail-Closed)"
        )
    )

    private val OTP_BLACKLIST_KEYWORDS = listOf(
        "验证码",
        "校验码",
        "动态码",
        "登录密码",
        "确认码",
        "verification code",
        "security code",
        "otp"
    )

    private val PICKUP_CONTEXT_KEYWORDS = listOf(
        "取件码",
        "提货码",
        "取货码",
        "凭码",
        "驿站",
        "菜鸟",
        "丰巢",
        "妈妈驿站",
        "兔喜",
        "近邻宝",
        "熊猫快收",
        "货架",
        "快递柜",
        "包裹"
    )

    private val SHELF_CODE_REGEX = Regex(
        """\b([A-Za-z0-9]{1,4}-[0-9]{1,3}-[0-9]{3,6}|[A-Za-z0-9]{1,4}-[0-9]{3,6})\b"""
    )

    private val EXPLICIT_PICKUP_REGEX = Regex(
        """(?:取件码|提货码|取货码|凭码)[:：\s是为【\[]*([A-Za-z0-9-]{4,14})[\]】]?"""
    )

    private val STATION_NAME_REGEX = Regex(
        """【([^】]{2,20}(?:驿站|丰巢|快递柜|服务中心))】|([^\s，,。！!：:]{2,20}(?:菜鸟驿站|妈妈驿站|兔喜生活|兔喜快递超市|丰巢智能柜|近邻宝))"""
    )

    fun normalizeTrackingNumber(raw: String): String {
        val cleaned = raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        require(cleaned.length in 6..32) {
            "运单号格式不合法（需为 6~32 位字母或数字）：$raw"
        }
        return cleaned
    }

    fun enforceProviderAuthority(
        providerId: String,
        isBackgroundPoll: Boolean = false,
        isProduction: Boolean = true,
        sandboxOverride: Boolean = false
    ): ParcelProviderSpec {
        val spec = PROVIDER_AUTHORITY_MATRIX[providerId]
            ?: throw SecurityException("未注册的快递数据源已按 Fail-Closed 策略拒绝：$providerId")
        if (spec.authorityStatus == ParcelAuthorityStatus.DISABLED) {
            throw SecurityException("数据源 $providerId 已停用")
        }
        if (isBackgroundPoll && !spec.allowBackgroundCloudPoll) {
            throw SecurityException("数据源 $providerId 不允许后台自动轮询抓取")
        }
        if (spec.authorityStatus == ParcelAuthorityStatus.RESEARCH_SANDBOX_ONLY && isProduction && !sandboxOverride) {
            throw SecurityException("数据源 $providerId 属于 RESEARCH_SANDBOX_ONLY，生产环境默认禁用")
        }
        return spec
    }

    fun resolvePickupCodeFromText(rawText: String?): ExtractedPickupInfo? {
        if (rawText.isNullOrBlank()) return null
        val text = rawText.trim()
        val lower = text.lowercase()

        val hasOtpKeyword = OTP_BLACKLIST_KEYWORDS.any { lower.contains(it) }
        val hasPickupContext = PICKUP_CONTEXT_KEYWORDS.any { lower.contains(it) }

        if (hasOtpKeyword && !hasPickupContext) {
            return null
        }

        val station = extractStationName(text)

        val shelfMatch = SHELF_CODE_REGEX.find(text)
        if (shelfMatch != null && hasPickupContext) {
            return ExtractedPickupInfo(
                pickupCode = shelfMatch.groupValues[1].uppercase(),
                codeType = "SHELF_CODE",
                stationName = station
            )
        }

        val explicitMatch = EXPLICIT_PICKUP_REGEX.find(text)
        if (explicitMatch != null) {
            val code = explicitMatch.groupValues[1].uppercase()
            return ExtractedPickupInfo(
                pickupCode = code,
                codeType = if (code.contains("-")) "SHELF_CODE" else "CABINET_PIN",
                stationName = station
            )
        }

        return null
    }

    private fun extractStationName(text: String): String? {
        val m = STATION_NAME_REGEX.find(text) ?: return null
        return (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().takeIf { it.isNotEmpty() }
    }
}
