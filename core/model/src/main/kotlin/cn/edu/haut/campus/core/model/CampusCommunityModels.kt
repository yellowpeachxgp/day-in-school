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

enum class CampusCompetitionCategory(val label: String, val shortLabel: String) {
    ALGO_PROGRAMMING("算法与程序设计", "算法编程"),
    ELECTRONICS_ROBO("电子与机器人硬件", "硬件机器人"),
    MATH_MODELING("数学建模与数据分析", "数学建模"),
    INNOVATION_ENTREPRENEURSHIP("创新创业", "创新创业"),
    CYBER_SECURITY_AI("网安与人工智能", "网安与AI"),
    DIGITAL_MEDIA_ART("数媒创意与外语综合", "数媒与综合");

    companion object {
        fun fromWireName(raw: String?): CampusCompetitionCategory {
            val cleaned = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.label == cleaned ||
                    it.shortLabel == cleaned
            } ?: INNOVATION_ENTREPRENEURSHIP
        }
    }
}

enum class CampusMajorTrack(
    val label: String,
    val shortLabel: String,
    val collegesHint: String,
    val keywords: List<String>
) {
    ALL_MAJORS(
        label = "全专业通用",
        shortLabel = "全部专业",
        collegesHint = "不限专业，支持跨学科组队",
        keywords = emptyList()
    ),
    CS_AI_SOFTWARE(
        label = "计算机 / 软工 / 人工智能",
        shortLabel = "计算机/软件/AI",
        collegesHint = "信息科学与工程学院、人工智能与大数据学院",
        keywords = listOf("计算机", "软件", "人工智能", "数据", "大数据", "网络", "信息安全", "空间安全", "计科", "软工", "智科")
    ),
    EE_AUTOMATION_IOT(
        label = "电气 / 自动化 / 通信物联",
        shortLabel = "电气/自动化/物联",
        collegesHint = "电气工程学院、信息科学与工程学院（电子/通信/物联）",
        keywords = listOf("电气", "自动化", "电子", "通信", "物联网", "集成电路", "光电", "测控", "微电子")
    ),
    MECH_VEHICLE_ROBO(
        label = "机械 / 机电 / 智能制造",
        shortLabel = "机械/机电/机器人",
        collegesHint = "机电工程学院、工业设计、机器人与车辆工程",
        keywords = listOf("机械", "机电", "车辆", "机器人", "智能制造", "工业设计", "过程装备", "成型")
    ),
    FOOD_BIO_CHEM_MAT(
        label = "粮油食品 / 生工 / 化工材料",
        shortLabel = "粮油/食品/化工",
        collegesHint = "粮油食品学院、生物工程、化学化工、材料、环境学院",
        keywords = listOf("食品", "粮", "生物", "制药", "化学", "化工", "材料", "环境", "高分子", "动科")
    ),
    ECON_MGMT_FINANCE(
        label = "经贸 / 管理 / 金融电商",
        shortLabel = "经管/金融/电商",
        collegesHint = "经济贸易学院、管理学院",
        keywords = listOf("经济", "贸易", "金融", "管理", "会计", "财务", "工商", "电商", "电子商务", "物流", "营销", "管工")
    ),
    DESIGN_MEDIA_FOREIGN(
        label = "设计艺术 / 新传 / 外语文法",
        shortLabel = "艺术/新传/外语",
        collegesHint = "设计艺术学院、新闻与传播学院、外语学院、法学院",
        keywords = listOf("设计", "视觉", "动画", "环境设计", "产品", "新闻", "传播", "广告", "播音", "英语", "外语", "翻译", "法学")
    ),
    MATH_PHYSICS_CIVIL(
        label = "数理统计 / 土木建筑",
        shortLabel = "数理/土木/建筑",
        collegesHint = "理学院、土木工程学院、建筑学院",
        keywords = listOf("数学", "物理", "统计", "力学", "土木", "建筑", "工程管理", "造价", "给排水", "城乡规划")
    );

    companion object {
        fun fromWireName(raw: String?): CampusMajorTrack {
            val cleaned = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.label == cleaned ||
                    it.shortLabel == cleaned
            } ?: ALL_MAJORS
        }

        /**
         * 根据教务系统同步的 StudentProfile.major 自动推断学生的专业方向赛道。
         */
        fun inferFromMajorText(majorText: String?): CampusMajorTrack? {
            val text = majorText?.trim().orEmpty()
            if (text.isEmpty()) return null
            return entries.firstOrNull { track ->
                track != ALL_MAJORS && track.keywords.any { kw -> text.contains(kw, ignoreCase = true) }
            }
        }
    }
}

/**
 * 云端下发的社团、学科竞赛与校园频道顶部横幅公告配置。
 */
data class CampusCommunityBulletin(
    val enabled: Boolean = true,
    val title: String = "合作社团与学科竞赛",
    val content: String = "收录校内合作社团招新群与常见学科竞赛信息，可按专业筛选赛事并查看官网。",
    val tag: String = "社团与竞赛",
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
    val campusName: String = "莲花街校区",
    val buildingName: String = "",
    val roomNumber: String = "",
    val summary: String,
    val qqGroupNumber: String,
    val highlights: List<String> = emptyList(),
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val sortOrder: Int = 100
)

/**
 * 河南工业大学大学生学科竞赛与专业/兴趣发展指南词条模型：
 * 支持按学生专业赛道（CampusMajorTrack）与兴趣发展领域（CampusCompetitionCategory）精准筛选，
 * 提供赛事级别、报名窗口、零基础难度评估、备赛成长路线、官方网站直达及合作社团组队联动。
 */
data class CampusCompetitionItem(
    val id: String,
    val title: String,
    val shortTitle: String,
    val tierBadge: String,
    val category: CampusCompetitionCategory,
    val recommendedMajorTracks: List<CampusMajorTrack> = listOf(CampusMajorTrack.ALL_MAJORS),
    val interestTags: List<String> = emptyList(),
    val organizer: String,
    val hautHostCollege: String,
    val registrationWindow: String,
    val teamMode: String,
    val difficultyLabel: String,
    val beginnerFriendly: Boolean = true,
    val summary: String,
    val skillRoadmap: String,
    val officialWebsiteUrl: String,
    val partnerClubId: String = "",
    val partnerClubName: String = "",
    val partnerClubQq: String = "",
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val sortOrder: Int = 100
) {
    fun matchesMajorTrack(track: CampusMajorTrack?): Boolean {
        if (track == null || track == CampusMajorTrack.ALL_MAJORS) return true
        return recommendedMajorTracks.contains(CampusMajorTrack.ALL_MAJORS) ||
            recommendedMajorTracks.contains(track)
    }

    fun isCoreForMajorTrack(track: CampusMajorTrack?): Boolean {
        if (track == null || track == CampusMajorTrack.ALL_MAJORS) return false
        return recommendedMajorTracks.contains(track)
    }

    fun matchesSeason(season: CompetitionSeasonWindow?): Boolean {
        if (season == null) return true
        return season.matchTokens.any { token -> registrationWindow.contains(token) }
    }

    fun recognitionValueText(): String = when {
        id == "comp_cyds" || id == "comp_tiaozhanbei" ->
            "国家级重点创新创业赛事，常用于综测加分、创新学分认定与推免参考。"
        tierBadge.contains("顶尖") || id == "comp_icpc_ccpc" ->
            "权威程序设计竞赛，受高校推免与软件研发岗位广泛认可。"
        tierBadge.contains("白名单 A 类") || tierBadge.contains("A 类") ->
            "全国普通高校学科竞赛排行榜赛事，适用于综测加分、创新学分认定与推免参考。"
        else ->
            "全国性学科综合赛事，按参赛比例评奖，适合检验专业基础与积累参赛经验。"
    }

    fun targetPersonaText(): String = buildString {
        append(if (beginnerFriendly) "适合低年级或跨专业同学入门；" else "适合具备一定专业基础的同学参加；")
        append("参赛形式：$teamMode，适合 ")
        append(recommendedMajorTracks.joinToString("、") { it.shortLabel })
        append(" 相关专业。")
    }

    fun stagedProgressionRoadmap(): List<Pair<String, String>> = listOf(
        "大一阶段 · 基础入门" to if (beginnerFriendly) {
            "熟悉赛制流程，学习「${skillRoadmap.substringBefore("→").trim()}」，争取校级或省级奖项。"
        } else {
            "参与${partnerClubName.ifBlank { "实验室或技术社团" }}日常训练，掌握「${skillRoadmap.substringBefore("→").trim()}」基础。"
        },
        "大二阶段 · 进阶备赛" to "深入学习「${skillRoadmap.substringAfter("→", skillRoadmap).trim()}」，利用假期完善作品或练习真题，争取省级或国家级奖项。",
        "大三阶段 · 成果整理" to "整理参赛作品与技术文档，可进一步申报软著、专利或作为推免与求职项目经历。"
    )
}

enum class CompetitionGradeStage(
    val label: String,
    val shortLabel: String,
    val strategyAdvice: String
) {
    FRESHMAN(
        label = "大一（基础入门）",
        shortLabel = "大一",
        strategyAdvice = "建议优先参加规则清晰的个人赛或入门级团队赛，熟悉参赛流程并积累专业基础。"
    ),
    SOPHOMORE(
        label = "大二（组队进阶）",
        shortLabel = "大二",
        strategyAdvice = "适合组建 3–5 人跨专业团队，重点备战电赛、数模、智能车等综合性学科竞赛。"
    ),
    JUNIOR(
        label = "大三（推免与求职）",
        shortLabel = "大三",
        strategyAdvice = "建议结合推免或求职时间线，选择周期合适、加分规则明确的重点赛事。"
    ),
    SENIOR_OR_ALL(
        label = "大四 / 研究生",
        shortLabel = "大四及以上",
        strategyAdvice = "可将毕业设计或课题研究成果整理参赛，如创新大赛、挑战杯或计算机设计大赛。"
    )
}

enum class CompetitionCareerGoal(
    val label: String,
    val shortLabel: String,
    val keywords: List<String>
) {
    POSTGRAD_BONUS(
        label = "推免与综测加分",
        shortLabel = "推免 / 综测",
        keywords = listOf("保研", "综测", "白名单", "A 类")
    ),
    HIGH_PAY_JOB(
        label = "求职与项目经历",
        shortLabel = "求职 / 实习",
        keywords = listOf("大厂", "名企", "算法", "工程", "实战", "CTF", "嵌入式")
    ),
    HANDS_ON_RESEARCH(
        label = "工程实践与科研",
        shortLabel = "工程 / 科研",
        keywords = listOf("硬件", "单片机", "机器人", "智能车", "机械", "数学建模", "学术")
    ),
    BIZ_CROSS_TEAM(
        label = "商业策划与跨学科合作",
        shortLabel = "商业 / 跨学科",
        keywords = listOf("双创", "商业", "电商", "路演", "跨学科", "广告", "设计")
    )
}

enum class CompetitionSkillPreference(
    val label: String,
    val shortLabel: String,
    val matchedCategories: Set<CampusCompetitionCategory>,
    val tagKeywords: List<String>
) {
    CODE_ALGO_AI(
        label = "编程 / 算法 / AI",
        shortLabel = "编程 / 算法 / AI",
        matchedCategories = setOf(
            CampusCompetitionCategory.ALGO_PROGRAMMING,
            CampusCompetitionCategory.CYBER_SECURITY_AI
        ),
        tagKeywords = listOf("算法", "编程", "App", "AI", "CTF", "安全")
    ),
    CIRCUIT_MCU_ROBO(
        label = "电路 / 单片机 / 机器人",
        shortLabel = "电路 / 单片机 / 机器人",
        matchedCategories = setOf(CampusCompetitionCategory.ELECTRONICS_ROBO),
        tagKeywords = listOf("单片机", "STM32", "PCB", "硬件", "机器人", "智能车", "机械")
    ),
    MATH_DATA(
        label = "数学建模 / 数据分析",
        shortLabel = "数模 / 数据分析",
        matchedCategories = setOf(CampusCompetitionCategory.MATH_MODELING),
        tagKeywords = listOf("MATLAB", "数据分析", "建模", "可视化")
    ),
    PRODUCT_BP_PITCH(
        label = "产品策划 / 商业计划 / 路演",
        shortLabel = "产品 / 商业计划",
        matchedCategories = setOf(CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP),
        tagKeywords = listOf("商业计划书", "路演", "双创", "电商", "调研")
    ),
    DESIGN_VIDEO_ACG(
        label = "视觉设计 / 视频 / 数媒",
        shortLabel = "设计 / 视频 / 数媒",
        matchedCategories = setOf(CampusCompetitionCategory.DIGITAL_MEDIA_ART),
        tagKeywords = listOf("海报", "动画", "短视频", "数媒", "交互")
    ),
    FOREIGN_CRITICAL(
        label = "外语 / 演讲写作",
        shortLabel = "外语 / 写作",
        matchedCategories = setOf(
            CampusCompetitionCategory.DIGITAL_MEDIA_ART,
            CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP
        ),
        tagKeywords = listOf("英语", "外语", "调研", "演讲")
    )
}

enum class CompetitionSeasonWindow(
    val label: String,
    val shortLabel: String,
    val periodSubtitle: String,
    val matchTokens: List<String>
) {
    AUTUMN_REG(
        label = "秋季学期（9–12月）",
        shortLabel = "秋季 9–12月",
        periodSubtitle = "秋季赛事开展与次年春季赛事报名阶段（数模国赛、蓝桥杯、三创赛、智能车等）",
        matchTokens = listOf("9月", "10月", "11月", "12月")
    ),
    SPRING_PROVINCIAL(
        label = "春季学期（3–5月）",
        shortLabel = "春季 3–5月",
        periodSubtitle = "校级选拔与省级比赛集中阶段（蓝桥杯省赛、英语竞赛、计算机设计大赛、大广赛等）",
        matchTokens = listOf("2月", "3月", "4月", "5月")
    ),
    SUMMER_NATIONAL(
        label = "暑期阶段（6–8月）",
        shortLabel = "暑期 6–8月",
        periodSubtitle = "暑期集中备赛与全国总决赛阶段（全国电赛、智能车国赛、数模培训、创新大赛等）",
        matchTokens = listOf("6月", "7月", "8月")
    )
}

data class CompetitionCompassResult(
    val topCompetitions: List<CampusCompetitionItem>,
    val entryStepOrder: List<Pair<String, CampusCompetitionItem>>,
    val recommendedClubs: List<CampusClubItem>,
    val stageSummary: String
)

object CompetitionCompassEngine {
    fun evaluate(
        competitions: List<CampusCompetitionItem>,
        clubs: List<CampusClubItem>,
        majorTrack: CampusMajorTrack?,
        gradeStage: CompetitionGradeStage,
        careerGoal: CompetitionCareerGoal,
        skillPref: CompetitionSkillPreference
    ): CompetitionCompassResult {
        val scored = competitions.map { comp ->
            var score = 0
            if (comp.isCoreForMajorTrack(majorTrack)) score += 35
            else if (comp.matchesMajorTrack(majorTrack)) score += 15

            if (comp.category in skillPref.matchedCategories) score += 45
            score += skillPref.tagKeywords.count { kw ->
                comp.interestTags.any { it.contains(kw, ignoreCase = true) } ||
                    comp.summary.contains(kw, ignoreCase = true) ||
                    comp.skillRoadmap.contains(kw, ignoreCase = true)
            } * 12

            when (gradeStage) {
                CompetitionGradeStage.FRESHMAN -> {
                    if (comp.beginnerFriendly) score += 28 else score -= 15
                    if (comp.teamMode.contains("个人")) score += 10
                }
                CompetitionGradeStage.SOPHOMORE -> {
                    if (!comp.beginnerFriendly) score += 20 else score += 12
                    if (comp.tierBadge.contains("A 类") || comp.tierBadge.contains("顶尖")) score += 15
                }
                CompetitionGradeStage.JUNIOR -> {
                    if (comp.tierBadge.contains("A 类") || comp.tierBadge.contains("顶尖")) score += 22
                    if (comp.interestTags.any { it.contains("保研") || it.contains("大厂") || it.contains("名企") }) score += 18
                }
                CompetitionGradeStage.SENIOR_OR_ALL -> {
                    if (comp.category == CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP || comp.id == "comp_jsjds_4c") score += 25
                }
            }

            score += careerGoal.keywords.count { kw ->
                comp.tierBadge.contains(kw) ||
                    comp.interestTags.any { it.contains(kw) } ||
                    comp.summary.contains(kw) ||
                    comp.difficultyLabel.contains(kw)
            } * 10

            comp to score
        }.sortedWith(
            compareByDescending<Pair<CampusCompetitionItem, Int>> { it.second }
                .thenBy { it.first.sortOrder }
        )

        val top3 = scored.take(3).map { it.first }
        val orderedForProgression = top3.sortedWith(
            compareByDescending<CampusCompetitionItem> { it.beginnerFriendly }
                .thenByDescending { it.teamMode.contains("个人") }
                .thenBy { it.sortOrder }
        )
        val stepLabels = listOf(
            "第一阶段 · 入门参赛",
            "第二阶段 · 团队进阶",
            "第三阶段 · 重点突破"
        )
        val entryOrder = orderedForProgression.mapIndexed { idx, item ->
            stepLabels.getOrElse(idx) { "进阶推荐" } to item
        }

        val partnerIds = top3.mapNotNull { it.partnerClubId.takeIf(String::isNotBlank) }.toSet()
        val matchedClubs = clubs.filter { it.id in partnerIds }.ifEmpty {
            clubs.take(2)
        }

        return CompetitionCompassResult(
            topCompetitions = top3,
            entryStepOrder = entryOrder,
            recommendedClubs = matchedClubs,
            stageSummary = gradeStage.strategyAdvice
        )
    }
}

/**
 * 官方反馈渠道与公众号云端配置。
 */
data class CampusOfficialChannelsConfig(
    val userFeedbackQqGroup: String = "",
    val userFeedbackChannelUrl: String = "https://github.com/yellowpeachxgp/day-in-school/issues",
    val wechatOfficialAccountName: String = "",
    val wechatOfficialAccountStatus: String = "PENDING"
)

data class CampusPublicWechatAccount(
    val name: String,
    val unit: String,
    val summary: String
)

/**
 * 校园频道、合作社团与学科竞赛云端配置目录快照。
 */
data class CampusCommunityCloudCatalog(
    val schoolId: String = "haut",
    val version: Long = 3L,
    val updatedAtIso: String = "",
    val bulletin: CampusCommunityBulletin = CampusCommunityBulletin(),
    val officialChannels: CampusOfficialChannelsConfig = CampusOfficialChannelsConfig(),
    val channels: List<PresetQqChannelItem> = PresetCampusCommunityCatalog.defaultQqChannels,
    val clubs: List<CampusClubItem> = PresetCampusCommunityCatalog.defaultCampusClubs,
    val competitions: List<CampusCompetitionItem> = PresetCampusCommunityCatalog.defaultCompetitions,
    val isFromCloud: Boolean = false
)

object PresetCampusCommunityCatalog {

    val verifiedSchoolWechatAccounts: List<CampusPublicWechatAccount> = listOf(
        CampusPublicWechatAccount(
            name = "河南工业大学",
            unit = "党委宣传部官方公众号",
            summary = "学校权威通知、校历放假安排、校园要闻与服务导航"
        ),
        CampusPublicWechatAccount(
            name = "河南工业大学教务处",
            unit = "教务处官方公众号",
            summary = "选课通知、考试报名、学籍管理与教学运行公告"
        ),
        CampusPublicWechatAccount(
            name = "河南工业大学图书馆",
            unit = "图书馆官方公众号",
            summary = "座位预约规则、开闭馆时间、馆藏检索与讲座通知"
        ),
        CampusPublicWechatAccount(
            name = "河工大青年",
            unit = "校团委官方公众号",
            summary = "第二课堂、社会实践、志愿服务与校园社团活动资讯"
        )
    )

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
            activityVenue = "莲花街校区 学生宿舍F01 W111",
            campusName = "莲花街校区",
            buildingName = "学生宿舍F01",
            roomNumber = "W111",
            summary = "开展校园电脑义务义诊、系统重装、软硬件维护与计算机技术科普交流。",
            qqGroupNumber = "1043885481",
            highlights = listOf("合作社团", "电脑义诊", "技术互助"),
            pinned = true,
            sortOrder = 30
        )
    )

    val defaultCompetitions: List<CampusCompetitionItem> = listOf(
        CampusCompetitionItem(
            id = "comp_lanqiao",
            title = "蓝桥杯全国软件和信息技术专业人才大赛",
            shortTitle = "蓝桥杯",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.ALGO_PROGRAMMING,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.MATH_PHYSICS_CIVIL
            ),
            interestTags = listOf("算法编程", "单片机/EDA", "大厂笔试", "保研/综测加分"),
            organizer = "工业和信息化部人才交流中心",
            hautHostCollege = "信息科学与工程学院 / 人工智能与大数据学院",
            registrationWindow = "每年 10月–12月报名 · 次年 4月省赛 / 6月国赛",
            teamMode = "个人赛（软件类 C/C++/Java/Python · 电子类单片机/EDA）",
            difficultyLabel = "入门友好 · 个人参赛",
            beginnerFriendly = true,
            summary = "面向高校学生的个人编程与电子设计赛事，设 C/C++、Java、Python、Web 及单片机赛道，适合低年级学生参赛练习。",
            skillRoadmap = "掌握一门编程语言 (C++/Python/Java) → 熟悉基础数据结构、搜索与贪心算法 → 学习动态规划与图论基础并练习历年真题",
            officialWebsiteUrl = "https://dasai.lanqiao.cn",
            partnerClubId = "club_partner_computer_service",
            partnerClubName = "计算机服务社",
            partnerClubQq = "1043885481",
            pinned = true,
            sortOrder = 10
        ),
        CampusCompetitionItem(
            id = "comp_nuedc",
            title = "全国大学生电子设计竞赛 (NUEDC)",
            shortTitle = "全国电赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.ELECTRONICS_ROBO,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.MECH_VEHICLE_ROBO,
                CampusMajorTrack.CS_AI_SOFTWARE
            ),
            interestTags = listOf("STM32/嵌入式", "PCB硬件设计", "电源与信号调理", "视觉控制"),
            organizer = "教育部高等教育司 / 工业和信息化部人事教育司",
            hautHostCollege = "电气工程学院 / 信息科学与工程学院",
            registrationWindow = "每年 5月–6月报名 · 8月四天三夜集中竞赛",
            teamMode = "3人组队（硬件电路 + 嵌入式控制 + 信号算法/报告）",
            difficultyLabel = "较高难度 · 需实验室集训",
            beginnerFriendly = false,
            summary = "电子信息、电气与自动化领域的国家级学科竞赛，采用全国统一命题、四天三夜制作实物的赛制，考察硬件电路与嵌入式开发能力。",
            skillRoadmap = "学习 STM32 单片机与 EDA 电路设计 → 掌握 PID 控制、运放电路与传感器驱动 → 参与社团日常训练与暑期集训",
            officialWebsiteUrl = "https://nuedc-training.com.cn",
            partnerClubId = "club_partner_hardware_alliance",
            partnerClubName = "硬件技术联盟",
            partnerClubQq = "789860526",
            pinned = true,
            sortOrder = 12
        ),
        CampusCompetitionItem(
            id = "comp_cumcm",
            title = "高教社杯全国大学生数学建模竞赛",
            shortTitle = "全国数模国赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.MATH_MODELING,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.ALL_MAJORS,
                CampusMajorTrack.MATH_PHYSICS_CIVIL,
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.ECON_MGMT_FINANCE
            ),
            interestTags = listOf("MATLAB/Python", "数据分析与优化", "LaTeX排版", "保研/综测加分"),
            organizer = "中国工业与应用数学学会 (CSIAM)",
            hautHostCollege = "理学院",
            registrationWindow = "每年 6月–8月校内选拔培训 · 9月上旬三天三夜正赛",
            teamMode = "3人组队（数学建模 + MATLAB/Python 编程 + 论文写作）",
            difficultyLabel = "入门友好 · 适合跨专业组队",
            beginnerFriendly = true,
            summary = "覆盖各专业的基础学科竞赛，通过建立数学模型与计算机仿真解决实际问题，锻炼数据分析与论文写作能力。",
            skillRoadmap = "组建建模、编程与写作团队 → 掌握常见评价、预测与优化模型 → 熟悉 MATLAB/Python 编程与论文排版",
            officialWebsiteUrl = "https://www.mcm.edu.cn",
            partnerClubId = "club_partner_computer_service",
            partnerClubName = "计算机服务社",
            partnerClubQq = "1043885481",
            pinned = true,
            sortOrder = 14
        ),
        CampusCompetitionItem(
            id = "comp_cyds",
            title = "中国国际大学生创新大赛（原“互联网+”）",
            shortTitle = "中国国际大学生创新大赛",
            tierBadge = "教育部白名单顶尖双创赛事",
            category = CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.ALL_MAJORS,
                CampusMajorTrack.FOOD_BIO_CHEM_MAT,
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.ECON_MGMT_FINANCE,
                CampusMajorTrack.MECH_VEHICLE_ROBO
            ),
            interestTags = listOf("双创赛事", "粮油食品赛道", "商业计划书", "路演答辩"),
            organizer = "教育部等十二部委联合主办",
            hautHostCollege = "创新创业学院 / 教务处",
            registrationWindow = "每年 4月–6月校赛网评 · 7月–8月省赛 · 10月全国总决赛",
            teamMode = "3–15人跨学科组队（技术研发 + 商业模式 + 财务法务 + 路演PPT）",
            difficultyLabel = "全专业可参加",
            beginnerFriendly = true,
            summary = "综合性大学生创新创业赛事（含高教主赛道、红旅赛道与产业命题赛道），可结合粮油食品、智能装备、信息技术等学科方向组队参赛。",
            skillRoadmap = "结合科研课题或学科特色确定选题 → 撰写商业计划书 (BP) → 准备专利、软著或应用证明及路演材料",
            officialWebsiteUrl = "https://cy.ncss.cn",
            pinned = true,
            sortOrder = 16
        ),
        CampusCompetitionItem(
            id = "comp_icpc_ccpc",
            title = "ICPC 国际大学生程序设计竞赛 & CCPC 中国大学生程序设计竞赛",
            shortTitle = "ICPC / CCPC 算法赛",
            tierBadge = "教育部白名单顶尖赛事",
            category = CampusCompetitionCategory.ALGO_PROGRAMMING,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.MATH_PHYSICS_CIVIL
            ),
            interestTags = listOf("ACM集训", "算法程序设计", "团队协作", "名企研发认可"),
            organizer = "ICPC 国际大学生程序设计竞赛基金会 / CCPC 组委会",
            hautHostCollege = "信息科学与工程学院",
            registrationWindow = "每年 3月–5月省赛/邀请赛 · 9月–11月网络赛与区域赛",
            teamMode = "3人组队（共用 1 台电脑限时 5 小时完成算法题）",
            difficultyLabel = "高难度 · 需长期算法训练",
            beginnerFriendly = false,
            summary = "国际通用的大学生算法与程序设计竞赛，重点考察算法设计与编程实现能力，受高校与软件研发企业认可。",
            skillRoadmap = "在线题库入门练习 → 系统学习高级数据结构（线段树/树状数组/并查集） → 图论网络流、数论与博弈论 → 3 人组队模拟训练",
            officialWebsiteUrl = "https://ccpc.io",
            partnerClubId = "club_partner_computer_service",
            partnerClubName = "计算机服务社",
            partnerClubQq = "1043885481",
            pinned = false,
            sortOrder = 20
        ),
        CampusCompetitionItem(
            id = "comp_smartcar",
            title = "全国大学生智能汽车竞赛",
            shortTitle = "智能汽车竞赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.ELECTRONICS_ROBO,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.MECH_VEHICLE_ROBO,
                CampusMajorTrack.CS_AI_SOFTWARE
            ),
            interestTags = listOf("自动控制", "PID控制算法", "嵌入式硬件", "机器视觉"),
            organizer = "中国自动化学会",
            hautHostCollege = "电气工程学院 / 机电工程学院",
            registrationWindow = "每年 11月发布赛题 → 次年 7月分赛区赛 / 8月全国总决赛",
            teamMode = "3–5人组队（机械车模调校 + 硬件驱动板 + 视觉/电磁循迹控制）",
            difficultyLabel = "进阶实践 · 软硬件结合",
            beginnerFriendly = true,
            summary = "以智能车为载体的工程实践赛事，涵盖电磁循迹、视觉识别、平衡控制等组别，综合考察机械、硬件与控制算法。",
            skillRoadmap = "学习 C 语言与微控制器 PWM/编码器驱动 → 设计电机驱动与电源稳压 PCB → 图像赛道提取与串级 PID 闭环调参",
            officialWebsiteUrl = "https://smartcar.cdstm.cn",
            partnerClubId = "club_partner_hardware_alliance",
            partnerClubName = "硬件技术联盟",
            partnerClubQq = "789860526",
            pinned = false,
            sortOrder = 22
        ),
        CampusCompetitionItem(
            id = "comp_robomaster",
            title = "全国大学生机器人大赛 (RoboMaster / ROBOCON)",
            shortTitle = "RoboMaster 机器人赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.ELECTRONICS_ROBO,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.MECH_VEHICLE_ROBO,
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.CS_AI_SOFTWARE
            ),
            interestTags = listOf("机器人设计", "SolidWorks机械", "ROS2/机器视觉", "电控开发"),
            organizer = "共青团中央 / 全国学联",
            hautHostCollege = "机电工程学院 / 电气工程学院",
            registrationWindow = "每年 9月–10月招新与赛季注册 · 次年 5月区域赛 / 8月全国赛",
            teamMode = "战队协作（机械结构 + 嵌入式电控 + 机器视觉算法 + 战队运营）",
            difficultyLabel = "较高难度 · 多学科团队协作",
            beginnerFriendly = true,
            summary = "综合性机器人工程竞赛，融合机械结构设计、嵌入式电控与机器视觉算法，适合多专业学生组队参与。",
            skillRoadmap = "按方向分工学习：机械组掌握 SolidWorks 三维建模；电控组掌握 FreeRTOS 与 CAN 总线控制；算法组掌握相机标定与视觉识别",
            officialWebsiteUrl = "https://www.robomaster.com",
            partnerClubId = "club_partner_hardware_alliance",
            partnerClubName = "硬件技术联盟",
            partnerClubQq = "789860526",
            pinned = false,
            sortOrder = 24
        ),
        CampusCompetitionItem(
            id = "comp_tiaozhanbei",
            title = "“挑战杯”全国大学生课外学术科技作品竞赛与创业计划竞赛",
            shortTitle = "“挑战杯”双创赛",
            tierBadge = "教育部白名单顶尖双创赛事",
            category = CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.ALL_MAJORS,
                CampusMajorTrack.FOOD_BIO_CHEM_MAT,
                CampusMajorTrack.MECH_VEHICLE_ROBO,
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.ECON_MGMT_FINANCE
            ),
            interestTags = listOf("学术科技", "科技发明制作", "社会调研报告", "创新创业"),
            organizer = "共青团中央 / 中国科协 / 教育部 / 全国学联",
            hautHostCollege = "校团委",
            registrationWindow = "每年 11月–次年3月校赛立项 · 5月省赛 · 10月全国终审决赛",
            teamMode = "3–10人组队（大挑：学术科技发明制作/哲学社科调查；小挑：创业计划）",
            difficultyLabel = "学术科技与社会调研",
            beginnerFriendly = true,
            summary = "全国性大学生课外学术科技与创业竞赛，奇数年举办学术科技作品竞赛（大挑），偶数年举办创业计划竞赛（小挑），适合各专业学生参加。",
            skillRoadmap = "参与导师课题组或大创项目 → 理工科侧重实物样机与论文专利，人文经管侧重实地调研与分析报告",
            officialWebsiteUrl = "https://www.tiaozhanbei.net",
            pinned = false,
            sortOrder = 26
        ),
        CampusCompetitionItem(
            id = "comp_ciscn",
            title = "全国大学生信息安全竞赛 (CISCN)",
            shortTitle = "全国信息安全竞赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.CYBER_SECURITY_AI,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.EE_AUTOMATION_IOT
            ),
            interestTags = listOf("CTF解题", "Web渗透与逆向", "密码学", "安全创新作品"),
            organizer = "教育部高等学校网络空间安全专业教学指导委员会",
            hautHostCollege = "信息科学与工程学院",
            registrationWindow = "每年 3月–4月报名 · 5月分区赛 · 7月全国总决赛",
            teamMode = "4人组队（创新实践能力赛 CTF 解题/AWD 攻防 · 安全作品赛）",
            difficultyLabel = "进阶难度 · 网络安全实践",
            beginnerFriendly = false,
            summary = "网络空间安全领域的国家级赛事，设 CTF 攻防赛与信息安全作品赛两个方向，考察漏洞分析、逆向工程、密码学与安全开发。",
            skillRoadmap = "学习 Linux 与计算机网络协议 → 选定 Web 渗透、逆向工程、二进制安全或密码学方向 → 结合在线靶场持续练习",
            officialWebsiteUrl = "https://www.ciscn.cn",
            partnerClubId = "club_partner_computer_service",
            partnerClubName = "计算机服务社",
            partnerClubQq = "1043885481",
            pinned = false,
            sortOrder = 28
        ),
        CampusCompetitionItem(
            id = "comp_jsjds_4c",
            title = "中国大学生计算机设计大赛 (4C)",
            shortTitle = "计算机设计大赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.CYBER_SECURITY_AI,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.DESIGN_MEDIA_FOREIGN,
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.ALL_MAJORS
            ),
            interestTags = listOf("软件与小程序", "人工智能应用", "数据可视化", "数字媒体设计"),
            organizer = "教育部高等学校计算机类专业教学指导委员会等",
            hautHostCollege = "人工智能与大数据学院 / 信息科学与工程学院",
            registrationWindow = "每年 3月–4月校赛选拔 · 5月省赛 · 7月–8月全国总决赛",
            teamMode = "1–5人组队（软件应用开发 / 人工智能应用 / 信息可视化 / 数媒动漫游戏）",
            difficultyLabel = "入门友好 · 提交作品参赛",
            beginnerFriendly = true,
            summary = "面向各专业学生的作品类计算机竞赛，通过提交软件、小程序、人工智能应用、数据可视化或数字媒体作品参赛。",
            skillRoadmap = "确定选题方向 → 完成前后端/移动端开发或数字媒体设计 → 录制演示视频并撰写设计说明书",
            officialWebsiteUrl = "https://jsjds.blcu.edu.cn",
            partnerClubId = "club_partner_computer_service",
            partnerClubName = "计算机服务社",
            partnerClubQq = "1043885481",
            pinned = false,
            sortOrder = 30
        ),
        CampusCompetitionItem(
            id = "comp_umic",
            title = "全国大学生机械创新设计大赛",
            shortTitle = "全国机械创新大赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.ELECTRONICS_ROBO,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.MECH_VEHICLE_ROBO,
                CampusMajorTrack.EE_AUTOMATION_IOT
            ),
            interestTags = listOf("机械结构创新", "SolidWorks/UG", "机电一体化", "实物样机"),
            organizer = "全国大学生机械创新设计大赛组委会",
            hautHostCollege = "机电工程学院",
            registrationWindow = "每两年一届（奇数年发布主题，偶数年 4月省赛 / 7月国赛）",
            teamMode = "3–5人组队（机构创新设计 + 力学仿真 + 样机加工装配）",
            difficultyLabel = "进阶难度 · 机械工程实践",
            beginnerFriendly = true,
            summary = "机械工程领域的全国性学科竞赛，围绕农业机械、仿生机械与智能装备等主题，考察机构设计、三维建模仿真与样机制作。",
            skillRoadmap = "掌握机械原理与机械设计基础 → 使用 SolidWorks/UG 进行三维建模、运动仿真与有限元分析 → 联合电控同学完成样机加工调试",
            officialWebsiteUrl = "https://umic.ckcest.cn",
            partnerClubId = "club_partner_hardware_alliance",
            partnerClubName = "硬件技术联盟",
            partnerClubQq = "789860526",
            pinned = false,
            sortOrder = 32
        ),
        CampusCompetitionItem(
            id = "comp_3chuang",
            title = "全国大学生电子商务“创新、创意及创业”挑战赛（三创赛）",
            shortTitle = "电子商务“三创赛”",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.ECON_MGMT_FINANCE,
                CampusMajorTrack.CS_AI_SOFTWARE,
                CampusMajorTrack.FOOD_BIO_CHEM_MAT,
                CampusMajorTrack.DESIGN_MEDIA_FOREIGN,
                CampusMajorTrack.ALL_MAJORS
            ),
            interestTags = listOf("电商策划", "乡村振兴", "新媒体运营", "跨专业组队"),
            organizer = "全国电子商务产教融合创新联盟",
            hautHostCollege = "经济贸易学院 / 管理学院",
            registrationWindow = "每年 10月–12月官网注册报名 · 次年 3月–4月校赛 / 5月省赛 / 7月国赛",
            teamMode = "3–5人组队（常规赛 + 跨境电商/乡村振兴/产学用实战赛）",
            difficultyLabel = "入门友好 · 适合跨专业组队",
            beginnerFriendly = true,
            summary = "面向经管、工科及文科学生的全国性电子商务竞赛，涵盖商业策划、跨境电商、助农电商与数字经济等方向。",
            skillRoadmap = "在官网完成团队报名 → 结合农产品电商、品牌营销或数字化应用选定方向 → 完善市场调研、财务模型与运营材料",
            officialWebsiteUrl = "https://www.3chuang.net",
            pinned = false,
            sortOrder = 34
        ),
        CampusCompetitionItem(
            id = "comp_sun_ada",
            title = "全国大学生广告艺术大赛（大广赛）",
            shortTitle = "全国大广赛",
            tierBadge = "教育部白名单 A 类赛事",
            category = CampusCompetitionCategory.DIGITAL_MEDIA_ART,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.DESIGN_MEDIA_FOREIGN,
                CampusMajorTrack.ECON_MGMT_FINANCE,
                CampusMajorTrack.ALL_MAJORS
            ),
            interestTags = listOf("海报/插画", "二维/三维动画", "短视频拍摄", "品牌广告策划"),
            organizer = "中国高等教育学会广告教育专业委员会",
            hautHostCollege = "设计艺术学院 / 新闻与传播学院",
            registrationWindow = "每年 3月–5月企业命题发布与创作 · 6月省赛 · 8月全国评审",
            teamMode = "1–3人（平面/文案/UI）或 1–5人（视频/动画/广播/策划案）",
            difficultyLabel = "入门友好 · 视觉与创意设计",
            beginnerFriendly = true,
            summary = "艺术设计、新闻传播与数字媒体领域的全国性竞赛，围绕企业命题开展平面设计、动画、短视频、策划案与文案创作。",
            skillRoadmap = "阅读当季企业命题要求 → 提炼创意方案 → 使用设计或剪辑软件完成平面、动画或视频作品",
            officialWebsiteUrl = "https://www.sun-ada.net",
            partnerClubId = "club_partner_chiyekong_acg",
            partnerClubName = "炽夜空动漫社",
            partnerClubQq = "314623763",
            pinned = false,
            sortOrder = 36
        ),
        CampusCompetitionItem(
            id = "comp_neccs",
            title = "全国大学生英语竞赛 (NECCS)",
            shortTitle = "全国大学生英语竞赛",
            tierBadge = "国家级学科综合赛事",
            category = CampusCompetitionCategory.DIGITAL_MEDIA_ART,
            recommendedMajorTracks = listOf(
                CampusMajorTrack.ALL_MAJORS,
                CampusMajorTrack.DESIGN_MEDIA_FOREIGN,
                CampusMajorTrack.ECON_MGMT_FINANCE
            ),
            interestTags = listOf("英语综合能力", "四六级备考", "保研/综测加分", "个人赛"),
            organizer = "国际英语外语教师协会中国英语外语教师协会 / 高等学校大学外语教学研究会",
            hautHostCollege = "外语学院",
            registrationWindow = "每年 2月–3月报名 · 4月中旬初赛 / 5月中旬全国决赛",
            teamMode = "个人赛（A类研究生 / B类英语专业 / C类非英语本科 / D类艺体类）",
            difficultyLabel = "入门友好 · 按参赛比例评奖",
            beginnerFriendly = true,
            summary = "全国统考的大学生英语综合能力竞赛（C类面向非英语专业本科生），按各校参赛人数比例评定奖项。",
            skillRoadmap = "巩固四六级词汇与阅读理解 → 练习赛题中的逻辑推理、改错、翻译与应用文写作 → 限时练习历年真题",
            officialWebsiteUrl = "https://www.chinaneccs.cn",
            pinned = false,
            sortOrder = 38
        )
    )
}
