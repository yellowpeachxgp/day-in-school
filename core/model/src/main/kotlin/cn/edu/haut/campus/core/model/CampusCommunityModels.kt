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
    val title: String = "河工大社团纳新与校园频道云端直通车",
    val content: String = "全校学生社团招新 QQ 群与校园互助频道已接入云端实时下发，点击即可一键呼起手机 QQ 加群或进入频道。",
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
    val version: Long = 1L,
    val updatedAtIso: String = "",
    val bulletin: CampusCommunityBulletin = CampusCommunityBulletin(),
    val channels: List<PresetQqChannelItem> = PresetCampusCommunityCatalog.defaultQqChannels,
    val clubs: List<CampusClubItem> = PresetCampusCommunityCatalog.defaultCampusClubs,
    val isFromCloud: Boolean = false
)

object PresetCampusCommunityCatalog {

    /**
     * 预置 QQ 频道列表：
     * 后续正式 QQ 频道邀请链接（如 https://pd.qq.com/s/xxxxxx）可直接写入此处 defaultInviteUrl，
     * 或在 App「我的 -> 预置 QQ 频道矩阵」卡片中点击编辑按钮动态写入并保存。
     */
    val defaultQqChannels: List<PresetQqChannelItem> = listOf(
        PresetQqChannelItem(
            id = "ch_official_main",
            title = "工大主频道",
            subtitle = "版本更新通知 · 校园消息 · 问题反馈",
            tagLabel = "主频道",
            defaultInviteUrl = "https://pd.qq.com/s/hautcampus",
            channelNumberHint = "点击加入"
        ),
        PresetQqChannelItem(
            id = "ch_freshman_guide",
            title = "新生答疑频道",
            subtitle = "报到流程 · 选课答疑 · 宿舍与校园卡使用",
            tagLabel = "新生",
            defaultInviteUrl = "https://pd.qq.com/s/hautfreshman",
            channelNumberHint = "点击加入"
        ),
        PresetQqChannelItem(
            id = "ch_academic_contest",
            title = "课程资料与竞赛组队",
            subtitle = "期末复习资料 · 大创/挑战杯/数模/ACM 组队",
            tagLabel = "学习",
            defaultInviteUrl = "https://pd.qq.com/s/hautstudy",
            channelNumberHint = "点击加入"
        ),
        PresetQqChannelItem(
            id = "ch_campus_life",
            title = "失物招领与二手闲置",
            subtitle = "校园卡失物招领 · 二手书流转 · 拼车同行",
            tagLabel = "生活",
            defaultInviteUrl = "https://pd.qq.com/s/hautlife",
            channelNumberHint = "点击加入"
        )
    )

    val defaultCampusClubs: List<CampusClubItem> = listOf(
        // 0. 官方合作社团（置顶推荐）
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
            sortOrder = 11
        ),
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
            sortOrder = 12
        ),

        // 1. 科技创新类
        CampusClubItem(
            id = "club_acm",
            name = "ACM 程序设计协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "信息科学与工程学院",
            activityVenue = "8号教学楼机房",
            summary = "开展算法训练、蓝桥杯与 ICPC/CCPC 竞赛备赛，周末有 C++ 与算法入门课。",
            qqGroupNumber = "784512903",
            highlights = listOf("蓝桥杯", "ACM集训", "算法入门")
        ),
        CampusClubItem(
            id = "club_robot",
            name = "机器人与智能车协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "电气工程学院 / 机电工程学院",
            activityVenue = "工程训练中心",
            summary = "参与全国大学生智能汽车竞赛、RoboMaster 与嵌入式开发，包含硬件画板、机械和视觉。",
            qqGroupNumber = "692348150",
            highlights = listOf("智能车竞赛", "STM32", "硬件开发")
        ),
        CampusClubItem(
            id = "club_math_model",
            name = "数学建模协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "理学院",
            activityVenue = "6号教学楼",
            summary = "组织数学建模国赛、美赛培训与跨专业组队。",
            qqGroupNumber = "815620493",
            highlights = listOf("数模组队", "MATLAB", "Python")
        ),
        CampusClubItem(
            id = "club_elec_tech",
            name = "电子科技协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "人工智能与大数据学院",
            activityVenue = "9号教学楼创客空间",
            summary = "单片机学习、电子制作、校园义务维修与全国大学生电子设计竞赛交流。",
            qqGroupNumber = "739104826",
            highlights = listOf("电子设计竞赛", "义务维修", "单片机")
        ),

        // 2. 学术文化类
        CampusClubItem(
            id = "club_debate",
            name = "演讲与辩论协会",
            category = CampusClubCategory.ACADEMIC_CULTURE,
            affiliatedUnit = "法学院 / 新闻与传播学院",
            activityVenue = "5号教学楼 / 钟楼报告厅",
            summary = "举办「新生杯」「秋实杯」校园辩论赛与日常演讲交流。",
            qqGroupNumber = "648291035",
            highlights = listOf("新生杯辩论赛", "演讲交流")
        ),
        CampusClubItem(
            id = "club_english",
            name = "英语协会",
            category = CampusClubCategory.ACADEMIC_CULTURE,
            affiliatedUnit = "外国语学院",
            activityVenue = "钟楼草坪 / 7号教学楼",
            summary = "周五晚钟楼英语角、四六级备考交流与英语演讲、配音活动。",
            qqGroupNumber = "583920174",
            highlights = listOf("四六级备考", "英语角", "配音比赛")
        ),
        CampusClubItem(
            id = "club_erp",
            name = "ERP 沙盘协会",
            category = CampusClubCategory.ACADEMIC_CULTURE,
            affiliatedUnit = "管理学院 / 经济贸易学院",
            activityVenue = "11号教学楼实训室",
            summary = "模拟企业运营与财务预算，参加企业竞争模拟大赛。",
            qqGroupNumber = "710492836",
            highlights = listOf("企业经营模拟", "商科竞赛")
        ),

        // 3. 文艺展演类
        CampusClubItem(
            id = "club_guitar",
            name = "吉他社",
            category = CampusClubCategory.ART_PERFORMANCE,
            activityVenue = "大学生活动中心 / 东操场",
            affiliatedUnit = "校团委社团管理部",
            summary = "木吉他、乐队排练与草坪音乐节演出，周末有新手教学。",
            qqGroupNumber = "864201937",
            highlights = listOf("草坪音乐节", "乐队排练", "吉他教学")
        ),
        CampusClubItem(
            id = "club_hanfu",
            name = "汉服社",
            category = CampusClubCategory.ART_PERFORMANCE,
            affiliatedUnit = "设计艺术学院",
            activityVenue = "中心湖畔 / 美术楼",
            summary = "汉服妆造、传统手工、中秋游园会与校内约拍活动。",
            qqGroupNumber = "629401835",
            highlights = listOf("中秋游园会", "传统手工", "摄影约拍")
        ),
        CampusClubItem(
            id = "club_photo",
            name = "摄影协会",
            category = CampusClubCategory.ART_PERFORMANCE,
            affiliatedUnit = "新闻与传播学院",
            activityVenue = "7号教学楼 / 校内",
            summary = "相机与手机摄影交流、后期修图分享及校园外拍活动。",
            qqGroupNumber = "758291046",
            highlights = listOf("外拍交流", "修图分享")
        ),

        // 4. 体育竞技类
        CampusClubItem(
            id = "club_skate",
            name = "轮滑社",
            category = CampusClubCategory.SPORTS_FITNESS,
            affiliatedUnit = "体育学院",
            activityVenue = "钟楼广场东侧轮滑场",
            summary = "每晚在钟楼轮滑场练习平花、刹停与速滑，新手有老社员带练。",
            qqGroupNumber = "592038471",
            highlights = listOf("新手带练", "高校刷街", "晚间训练")
        ),
        CampusClubItem(
            id = "club_badminton",
            name = "羽毛球协会",
            category = CampusClubCategory.SPORTS_FITNESS,
            affiliatedUnit = "体育学院",
            activityVenue = "体育馆羽毛球场",
            summary = "日常约球、基础步伐练习与「工大杯」羽毛球赛。",
            qqGroupNumber = "830192746",
            highlights = listOf("日常约球", "工大杯比赛")
        ),
        CampusClubItem(
            id = "club_night_run",
            name = "夜跑协会",
            category = CampusClubCategory.SPORTS_FITNESS,
            affiliatedUnit = "体育学院",
            activityVenue = "北区/东区田径场",
            summary = "晚间跑步打卡、体测 800/1000 米练习与郑开马拉松集体报名。",
            qqGroupNumber = "671829304",
            highlights = listOf("体测练习", "郑开马拉松", "晚间夜跑")
        ),

        // 5. 志愿与校级组织类
        CampusClubItem(
            id = "club_volunteer",
            name = "青年志愿者协会",
            category = CampusClubCategory.VOLUNTEER_ORG,
            affiliatedUnit = "校团委",
            activityVenue = "大学生活动中心 204",
            summary = "发布校内外志愿服务活动、迎新志愿、图书馆整理与志愿汇时长录入。",
            qqGroupNumber = "901284756",
            highlights = listOf("志愿汇时长", "迎新服务", "社区支教")
        ),
        CampusClubItem(
            id = "club_art_troupe",
            name = "大学生艺术团",
            category = CampusClubCategory.VOLUNTEER_ORG,
            affiliatedUnit = "校团委",
            activityVenue = "大学生活动中心大礼堂",
            summary = "包含合唱、舞蹈、器乐、话剧与主持队，负责迎新晚会与毕业晚会演出。",
            qqGroupNumber = "746192038",
            highlights = listOf("迎新晚会", "合唱舞蹈器乐", "主持礼仪")
        ),
        CampusClubItem(
            id = "club_broadcast",
            name = "工大之声广播站",
            category = CampusClubCategory.VOLUNTEER_ORG,
            affiliatedUnit = "党委宣传部 / 校团委",
            activityVenue = "行政楼广播室",
            summary = "负责校园早晚广播点歌、公众号采编与学校活动播音主持。",
            qqGroupNumber = "683920417",
            highlights = listOf("广播点歌", "播音主持", "新媒体采编")
        )
    )
}
