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
    INNOVATION_ENTREPRENEURSHIP("创新创业与双创孵化", "创新创业"),
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
        collegesHint = "不限专业背景，零基础或跨学科组队均可参赛",
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
    val title: String = "河工大合作社团与学科竞赛云端直通车",
    val content: String = "官方合作社团招新 QQ 群与教育部白名单学科竞赛指南已接入云端实时下发，支持按专业与兴趣匹配赛事、直达官网并一键加群组队。",
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
}

/**
 * 校园频道、合作社团与学科竞赛云端配置目录快照。
 */
data class CampusCommunityCloudCatalog(
    val schoolId: String = "haut",
    val version: Long = 3L,
    val updatedAtIso: String = "",
    val bulletin: CampusCommunityBulletin = CampusCommunityBulletin(),
    val channels: List<PresetQqChannelItem> = PresetCampusCommunityCatalog.defaultQqChannels,
    val clubs: List<CampusClubItem> = PresetCampusCommunityCatalog.defaultCampusClubs,
    val competitions: List<CampusCompetitionItem> = PresetCampusCommunityCatalog.defaultCompetitions,
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
            interestTags = listOf("算法编程", "单片机/EDA", "大厂笔试直通", "保研/综测加分"),
            organizer = "工业和信息化部人才交流中心",
            hautHostCollege = "信息科学与工程学院 / 人工智能与大数据学院",
            registrationWindow = "每年 10月–12月报名 · 次年 4月省赛 / 6月国赛",
            teamMode = "个人赛（软件类 C/C++/Java/Python · 电子类单片机/EDA）",
            difficultyLabel = "大一友好 · 零基础可冲省奖",
            beginnerFriendly = true,
            summary = "国内参赛规模最大的个人编程与电子设计赛事，分 C/C++、Java、Python、Web 及单片机/嵌入式赛道，是理工科学生大一练手、斩获首个省级/国家级奖项的最佳跳板。",
            skillRoadmap = "掌握一门主力语言 (C++/Python/Java) → 熟悉数组、字符串、搜索 (BFS/DFS) 与贪心 → 攻克动态规划与图论基础 → 刷透近 5 年省赛真题",
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
            registrationWindow = "每年 5月–6月报名 · 8月四天三夜封闭极限竞赛",
            teamMode = "3人组队（硬件电路 + 嵌入式控制 + 信号算法/报告）",
            difficultyLabel = "硬核工程 · 实验室实战集训",
            beginnerFriendly = false,
            summary = "电子信息、电气工程与自动化专业含金量最高的国家级学科竞赛，采用全国统一命题、四天三夜半封闭制作实物的极限赛制，全面检验硬件电路与嵌入式系统功底。",
            skillRoadmap = "大一入门 STM32 单片机与立创 EDA 画板 → 掌握 PID 控制、运放电路与传感器驱动 → 参加硬件技术联盟日常实训与暑期集训",
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
            interestTags = listOf("MATLAB/Python", "数据分析与优化", "LaTeX科研排版", "保研核心加分"),
            organizer = "中国工业与应用数学学会 (CSIAM)",
            hautHostCollege = "理学院",
            registrationWindow = "每年 6月–8月校内选拔培训 · 9月上旬三天三夜正赛",
            teamMode = "3人组队（数学建模手 + MATLAB/Python 编程手 + 论文写作手）",
            difficultyLabel = "大一/大二友好 · 跨专业组队首选",
            beginnerFriendly = true,
            summary = "全国规模最大、覆盖全部专业的基础性学科竞赛，通过建立数学模型与计算机仿真解决工程、经济或社会实际问题，是保研加分与培养科研论文撰写能力的必修课。",
            skillRoadmap = "组建建模+编程+写作铁三角 → 掌握评价类(TOPSIS/熵权法)、预测类(时间序列/机器学习)、优化类(规划模型/启发式算法) → 熟练使用 MATLAB/Python 与 LaTeX 排版",
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
            interestTags = listOf("双创最高奖", "粮油食品特色赛道", "商业计划书", "路演答辩"),
            organizer = "教育部等十二部委联合主办",
            hautHostCollege = "创新创业学院 / 教务处",
            registrationWindow = "每年 4月–6月校赛网评 · 7月–8月省赛 · 10月全国总决赛",
            teamMode = "3–15人跨学科组队（技术研发 + 商业模式 + 财务法务 + 路演PPT）",
            difficultyLabel = "大一友好 · 全专业皆可参与",
            beginnerFriendly = true,
            summary = "教育部排名第一的国家级顶尖双创赛事（含高教主赛道、红旅赛道、产业命题赛道），河工大在智慧粮油、食品工程、智能装备与新材料等交叉领域极具冲金优势。",
            skillRoadmap = "结合导师科研课题或河工大粮油食品/智能制造学科优势 → 撰写真实落地的商业计划书(BP) → 准备专利/软著/企业应用证明与高水准路演 PPT",
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
            interestTags = listOf("ACM集训", "算法巅峰", "团队极限编程", "名企免笔试"),
            organizer = "ICPC 国际大学生程序设计竞赛基金会 / CCPC 组委会",
            hautHostCollege = "信息科学与工程学院",
            registrationWindow = "每年 3月–5月省赛/邀请赛 · 9月–11月网络赛与区域赛",
            teamMode = "3人组队（共用 1 台电脑限时 5 小时攻克 10–13 道算法题）",
            difficultyLabel = "硬核算法 · 冲击名企与保研顶峰",
            beginnerFriendly = false,
            summary = "全球含金量最高的大学生算法与程序设计巅峰赛事，代表高校计算机学科硬核编程实力，获奖选手深受字节、腾讯、华为等核心研发部门青睐。",
            skillRoadmap = "洛谷/Codeforces 入门刷题 → 系统学习高级数据结构（线段树/树状数组/并查集） → 图论网络流、数论与博弈论 → 3 人组队分工模拟赛",
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
            interestTags = listOf("自动驾驶竞速", "PID控制算法", "嵌入式硬件", "机器视觉"),
            organizer = "中国自动化学会",
            hautHostCollege = "电气工程学院 / 机电工程学院",
            registrationWindow = "每年 11月发布赛题 → 次年 7月分赛区赛 / 8月全国总决赛",
            teamMode = "3–5人组队（机械车模调校 + 硬件驱动板 + 视觉/电磁循迹控制）",
            difficultyLabel = "进阶实战 · 软硬件全栈闭环",
            beginnerFriendly = true,
            summary = "以竞速智能车为载体的综合工程探索赛事，涵盖电磁循迹、摄像头视觉识别、平衡控制、越野信标等组别，亲手打造一辆风驰电掣的微型自动驾驶赛车。",
            skillRoadmap = "学习 C 语言与微控制器 PWM/编码器驱动 → 自行设计电机驱动与电源稳压 PCB → 图像二值化赛道提取与串级 PID 转向/速度闭环调参",
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
            interestTags = listOf("机甲大师", "SolidWorks机械", "ROS2/机器视觉", "电控开发"),
            organizer = "共青团中央 / 全国学联",
            hautHostCollege = "机电工程学院 / 电气工程学院",
            registrationWindow = "每年 9月–10月招新与赛季注册 · 次年 5月区域赛 / 8月全国赛",
            teamMode = "战队协作（机械结构 + 嵌入式电控 + 机器视觉算法 + 战队运营）",
            difficultyLabel = "硬核工程 · 多学科团队协同",
            beginnerFriendly = true,
            summary = "专为青年工程师打造的机器人科创盛事，融合机械传动设计、嵌入式电控、OpenCV/深度学习装甲板识别与战术对抗，堪称工科生的热血赛场。",
            skillRoadmap = "按方向选修：机械组掌握 SolidWorks 三维建模；电控组掌握 FreeRTOS 与 CAN 总线电机控制；算法组掌握相机标定、PNP 解算与神经网络推理",
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
            interestTags = listOf("学术奥林匹克", "科技发明制作", "社会调研报告", "团委核心赛事"),
            organizer = "共青团中央 / 中国科协 / 教育部 / 全国学联",
            hautHostCollege = "校团委",
            registrationWindow = "每年 11月–次年3月校赛立项 · 5月省赛 · 10月全国终审决赛",
            teamMode = "3–10人组队（大挑：学术科技发明制作/哲学社科调查；小挑：创业计划）",
            difficultyLabel = "进阶科研 · 学术与社会调研并重",
            beginnerFriendly = true,
            summary = "被誉为当代大学生科技创新的“奥林匹克”盛会，奇数年举办课外学术科技作品竞赛（大挑），偶数年举办中国大学生创业计划竞赛（小挑），文理工商学子均可找到专属赛道。",
            skillRoadmap = "大一加入导师课题组或大创项目 → 理工科聚焦硬核实物样机与核心论文/专利，人文经管聚焦扎实的田野调查问卷与政策建议报告",
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
            interestTags = listOf("CTF夺旗赛", "Web渗透与逆向", "密码学", "安全创新作品"),
            organizer = "教育部高等学校网络空间安全专业教学指导委员会",
            hautHostCollege = "信息科学与工程学院",
            registrationWindow = "每年 3月–4月报名 · 5月分区赛 · 7月全国总决赛",
            teamMode = "4人组队（创新实践能力赛 CTF 解题/AWD 攻防 · 安全作品赛）",
            difficultyLabel = "进阶挑战 · 网络攻防实战",
            beginnerFriendly = false,
            summary = "网络空间安全与计算机领域权威国家级赛事，分为 CTF 夺旗攻防实战赛与信息安全作品赛两大方向，全面锤炼漏洞挖掘、逆向工程、密码学与安全系统研发能力。",
            skillRoadmap = "入门 Linux 与计算机网络协议 → 选定 Web 渗透、Reverse 逆向、Pwn 二进制漏洞或 Crypto 密码学方向 → 在 CTFHub/BUUCTF 靶场持续刷题实战",
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
            interestTags = listOf("App/小程序开发", "AI大模型应用", "数据可视化", "数媒交互设计"),
            organizer = "教育部高等学校计算机类专业教学指导委员会等",
            hautHostCollege = "人工智能与大数据学院 / 信息科学与工程学院",
            registrationWindow = "每年 3月–4月校赛选拔 · 5月省赛 · 7月–8月全国总决赛",
            teamMode = "1–5人组队（软件应用开发 / 人工智能应用 / 信息可视化 / 数媒动漫游戏）",
            difficultyLabel = "大一友好 · 作品导向极易上手",
            beginnerFriendly = true,
            summary = "面向全校各专业学生的作品类计算机大赛，不考限时算法题，而是提交完整软件、小程序、AI 应用、数据可视化或数媒动画作品，非常适合喜欢做项目的同学。",
            skillRoadmap = "确定实用选题（如校园工具、智慧农业/粮仓监测、中华传统文化数媒展示） → 完成前后端/移动端开发或交互设计 → 录制高清演示视频与设计说明书",
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
            difficultyLabel = "进阶工程 · 机械学科王牌赛",
            beginnerFriendly = true,
            summary = "国内机械工程领域最高水平的大学生学科竞赛，聚焦兴农机械、仿生机械、高性能智能装备等主题，要求完成从机构原理构思、三维建模仿真到全尺寸实物样机制作。",
            skillRoadmap = "学透机械原理与机械设计基础 → 熟练掌握 SolidWorks/UG 三维装配、运动仿真与 ANSYS 有限元分析 → 联合电控同学完成样机加工调试",
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
            interestTags = listOf("电商运营与实战", "乡村振兴助农", "新媒体营销", "零门槛双创"),
            organizer = "全国电子商务产教融合创新联盟",
            hautHostCollege = "经济贸易学院 / 管理学院",
            registrationWindow = "每年 10月–12月官网注册报名 · 次年 3月–4月校赛 / 5月省赛 / 7月国赛",
            teamMode = "3–5人组队（常规赛 + 跨境电商/乡村振兴/产学用实战赛）",
            difficultyLabel = "大一友好 · 经管与工科跨界首选",
            beginnerFriendly = true,
            summary = "教育部白名单中对经贸、管理、营销与工科交叉最友好的全国性大赛，强调真实商业策划、跨境电商、直播助农与数字经济创新，大一新生即可快速组队参赛。",
            skillRoadmap = "年底前务必在官网完成团队注册 → 结合河南农产品/粮油品牌或校园数字化需求选定赛道 → 打磨市场调研数据、盈利模型与实战运营佐证材料",
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
            interestTags = listOf("海报/IP插画", "二维/三维动画", "短视频拍摄", "品牌广告策划"),
            organizer = "中国高等教育学会广告教育专业委员会",
            hautHostCollege = "设计艺术学院 / 新闻与传播学院",
            registrationWindow = "每年 3月–5月企业命题发布与创作 · 6月省赛 · 8月全国评审",
            teamMode = "1–3人（平面/文案/UI）或 1–5人（视频/动画/广播/策划案）",
            difficultyLabel = "大一友好 · 创意与ACG爱好者天堂",
            beginnerFriendly = true,
            summary = "文科、艺术设计与数字媒体领域规模最大的教育部白名单赛事，由知名品牌提供真实商业命题，涵盖平面设计、动画、短视频、互动H5、广播与广告语创作。",
            skillRoadmap = "下载当季企业命题策略单 → 针对品牌痛点提炼核心创意概念 → 运用 PS/AI/Blender/AE/PR 完成视觉、动画或视频成片，严守原创规范",
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
            interestTags = listOf("英语综合能力", "四六级/考研神助攻", "保研外语加分", "个人赛"),
            organizer = "国际英语外语教师协会中国英语外语教师协会 / 高等学校大学外语教学研究会",
            hautHostCollege = "外语学院",
            registrationWindow = "每年 2月–3月报名 · 4月中旬初赛 / 5月中旬全国决赛",
            teamMode = "个人赛（A类研究生 / B类英语专业 / C类非英语本科 / D类艺体类）",
            difficultyLabel = "大一友好 · 按校内比例评奖",
            beginnerFriendly = true,
            summary = "全国唯一统考的大学生英语综合能力竞赛（C类面向全校非英语专业本科生），按各校参赛人数比例直接评定全国特等奖及一、二、三等奖，获奖率高且保研综测高度认可。",
            skillRoadmap = "巩固四六级高频词汇与长难句阅读 → 针对性训练赛题特有的智力推理题(IQ)、篇章改错、英汉翻译与图表应用文写作 → 限时刷近 5 年真题卷",
            officialWebsiteUrl = "https://www.chinaneccs.cn",
            pinned = false,
            sortOrder = 38
        )
    )
}
