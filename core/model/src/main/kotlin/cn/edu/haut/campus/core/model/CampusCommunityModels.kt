package cn.edu.haut.campus.core.model

enum class CampusClubCategory(val label: String) {
    TECH_INNOVATION("科技创新"),
    ACADEMIC_CULTURE("学术文化"),
    ART_PERFORMANCE("文艺展演"),
    SPORTS_FITNESS("体育竞技"),
    VOLUNTEER_ORG("志愿与组织");

    companion object {
        fun fromWireName(raw: String?): CampusClubCategory {
            val cleaned = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) || it.label == cleaned
            } ?: VOLUNTEER_ORG
        }
    }
}

/**
 * 云端下发的社团与校园频道顶部横幅公告配置。
 */
data class CampusCommunityBulletin(
    val enabled: Boolean = true,
    val title: String = "河工大合作社团与交流群云端直通车",
    val content: String = "合作学生社团招新与技术交流 QQ 群已接入云端实时下发，点击即可一键呼起手机 QQ 加群；校园频道暂时置空。",
    val tag: String = "云端实时同步",
    val actionUrl: String = ""
)

/**
 * 预置/云控 QQ 频道配置模型：
 * 支持直接点击唤醒手机 QQ 跳转进入目标频道，也支持通过云端后台动态下发更新。
 */
data class PresetQqChannelItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val tagLabel: String,
    val defaultInviteUrl: String,
    val channelNumberHint: String = "点击加入",
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val sortOrder: Int = 100
)

/**
 * 河南工业大学（莲花街校区）学生社团与校园组织词条模型：
 * 方便新生了解各社团活动方向、常规活动场地，并支持一键复制纳新群号或直接唤醒 QQ 群资料卡加群。
 */
data class CampusClubItem(
    val id: String,
    val name: String,
    val category: CampusClubCategory,
    val affiliatedUnit: String,
    val activityVenue: String,
    val summary: String,
    val qqGroupNumber: String,
    val highlights: List<String> = emptyList(),
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val sortOrder: Int = 100
)

/**
 * 校园频道与学生社团云端配置目录快照。
 */
data class CampusCommunityCloudCatalog(
    val schoolId: String = "haut",
    val version: Long = 2L,
    val updatedAtIso: String = "",
    val bulletin: CampusCommunityBulletin = CampusCommunityBulletin(),
    val channels: List<PresetQqChannelItem> = PresetCampusCommunityCatalog.defaultQqChannels,
    val clubs: List<CampusClubItem> = PresetCampusCommunityCatalog.defaultCampusClubs,
    val isFromCloud: Boolean = false
)

object PresetCampusCommunityCatalog {

    /**
     * 预置 QQ 频道列表：
     * 校园频道当前暂时置空，支持后续通过云端动态下发。
     */
    val defaultQqChannels: List<PresetQqChannelItem> = emptyList()

    val defaultCampusClubs: List<CampusClubItem> = listOf(
        CampusClubItem(
            id = "club_partner_chiyekong_acg",
            name = "炽夜空动漫社",
            category = CampusClubCategory.ART_PERFORMANCE,
            affiliatedUnit = "校团委社团管理部",
            activityVenue = "大学生活动中心",
            summary = "涵盖宅舞、Cosplay、配音、绘画与校园动漫文化节展演活动。",
            qqGroupNumber = "314623763",
            highlights = listOf("合作社团", "动漫文化节", "Cosplay/宅舞"),
            pinned = true,
            sortOrder = 10
        ),
        CampusClubItem(
            id = "club_partner_hardware_alliance",
            name = "硬件技术联盟",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "电气工程学院 / 人工智能与大数据学院",
            activityVenue = "莲花街校区创客空间",
            summary = "专注嵌入式开发、PCB 硬件设计、装机DIY与电子制作技术交流。",
            qqGroupNumber = "789860526",
            highlights = listOf("合作社团", "硬件开发", "装机DIY"),
            pinned = true,
            sortOrder = 20
        ),
        CampusClubItem(
            id = "club_partner_computer_service",
            name = "计算机服务社",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "信息科学与工程学院",
            activityVenue = "莲花街校区 8号教学楼",
            summary = "开展校园电脑义务义诊、系统重装、软硬件维护与计算机技术科普交流。",
            qqGroupNumber = "1043885481",
            highlights = listOf("合作社团", "电脑义诊", "技术互助"),
            pinned = true,
            sortOrder = 30
        )
    )
}
