package cn.edu.haut.campus.core.model

enum class CampusClubCategory(val label: String) {
    TECH_INNOVATION("科技创新"),
    ACADEMIC_CULTURE("学术文化"),
    ART_PERFORMANCE("文艺展演"),
    SPORTS_FITNESS("体育竞技"),
    VOLUNTEER_ORG("志愿与组织")
}

/**
 * 预置 QQ 频道配置模型：
 * 支持直接点击唤醒手机 QQ 跳转进入目标频道，也支持在 App 内修改覆盖预置频道链接。
 */
data class PresetQqChannelItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val tagLabel: String,
    val defaultInviteUrl: String,
    val channelNumberHint: String
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
    val highlights: List<String> = emptyList()
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
            title = "日在校园 · 工大官方主频道",
            subtitle = "版本更新发布 · 莲花街校区日常资讯 · 功能许愿与反馈",
            tagLabel = "官方主频",
            defaultInviteUrl = "https://pd.qq.com/s/hautcampus",
            channelNumberHint = "官方主频道 · 点击直达"
        ),
        PresetQqChannelItem(
            id = "ch_freshman_guide",
            title = "莲花街新生答疑与迎新频道",
            subtitle = "新生报到攻略 · 选课避坑指南 · 宿舍生活与校园卡答疑",
            tagLabel = "迎新互助",
            defaultInviteUrl = "https://pd.qq.com/s/hautfreshman",
            channelNumberHint = "新生互助区 · 点击直达"
        ),
        PresetQqChannelItem(
            id = "ch_academic_contest",
            title = "工大课程资料与竞赛组队频道",
            subtitle = "历年期末复习题库 · 大创/挑战杯/数学建模/ACM跨院组队",
            tagLabel = "学业竞赛",
            defaultInviteUrl = "https://pd.qq.com/s/hautstudy",
            channelNumberHint = "资料与组队 · 点击直达"
        ),
        PresetQqChannelItem(
            id = "ch_campus_life",
            title = "校园生活 · 失物招领与闲置频道",
            subtitle = "一卡通/耳机失物招领 · 教材闲置流转 · 节假日拼车出行",
            tagLabel = "生活社区",
            defaultInviteUrl = "https://pd.qq.com/s/hautlife",
            channelNumberHint = "失物与闲置 · 点击直达"
        )
    )

    /**
     * 河南工业大学莲花街校区代表性学生社团与校级组织名录：
     * 支持分类检索、一键复制群号、一键调起手机 QQ 群资料卡，以及在 App 内自定义更新群号或补充新社团。
     */
    val defaultCampusClubs: List<CampusClubItem> = listOf(
        // 1. 科技创新类
        CampusClubItem(
            id = "club_acm",
            name = "ACM 程序设计协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "信息科学与工程学院",
            activityVenue = "8号教学楼机房 / 在线OJ平台",
            summary = "专注算法设计、ICPC/CCPC及蓝桥杯竞赛集训，每周定期开展零基础C++/算法公开课与模拟赛。",
            qqGroupNumber = "784512903",
            highlights = listOf("蓝桥杯/ACM集训", "大一零基础培训", "学长一对一答疑")
        ),
        CampusClubItem(
            id = "club_robot",
            name = "机器人与智能车创新协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "电气工程学院 / 机电工程学院",
            activityVenue = "工程训练中心创新工坊",
            summary = "围绕全国大学生智能汽车竞赛、RoboMaster与嵌入式开发，涵盖PCB硬件设计、机械结构与视觉控制。",
            qqGroupNumber = "692348150",
            highlights = listOf("智能车国赛", "嵌入式STM32", "动手实践工坊")
        ),
        CampusClubItem(
            id = "club_math_model",
            name = "数学建模协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "理学院",
            activityVenue = "6号教学楼阶梯教室",
            summary = "面向全校理工与经管专业同学，组织高教社杯国赛、美赛（MCM/ICM）赛前培训与跨学院建模队友匹配。",
            qqGroupNumber = "815620493",
            highlights = listOf("国赛/美赛组队", "MATLAB与Python", "跨专业合作")
        ),
        CampusClubItem(
            id = "club_elec_tech",
            name = "电子科技与创客协会",
            category = CampusClubCategory.TECH_INNOVATION,
            affiliatedUnit = "人工智能与大数据学院",
            activityVenue = "9号教学楼创客空间",
            summary = "开展单片机开发、物联网传感器制作、校园义务维修家电与全国大学生电子设计竞赛备赛交流。",
            qqGroupNumber = "739104826",
            highlights = listOf("电子设计竞赛", "校园义务维修", "开源硬件")
        ),

        // 2. 学术文化类
        CampusClubItem(
            id = "club_debate",
            name = "演讲与辩论协会",
            category = CampusClubCategory.ACADEMIC_CULTURE,
            affiliatedUnit = "法学院 / 新闻与传播学院",
            activityVenue = "5号教学楼模拟法庭 / 钟楼报告厅",
            summary = "主办工大「新生杯」「秋实杯」校园辩论赛，培养逻辑交锋、即兴演讲与公众表达能力。",
            qqGroupNumber = "648291035",
            highlights = listOf("新生杯辩论赛", "逻辑与口才", "华语辩论联赛")
        ),
        CampusClubItem(
            id = "club_english",
            name = "英语沙龙协会 (English Club)",
            category = CampusClubCategory.ACADEMIC_CULTURE,
            affiliatedUnit = "外国语学院",
            activityVenue = "钟楼草坪 / 7号教学楼语音室",
            summary = "每周五晚举办钟楼英语角、四六级高分经验分享会、英文配音大赛与外研社杯演讲选拔交流。",
            qqGroupNumber = "583920174",
            highlights = listOf("四六级冲刺", "周五英语角", "配音与演讲")
        ),
        CampusClubItem(
            id = "club_erp",
            name = "ERP 企业经营沙盘协会",
            category = CampusClubCategory.ACADEMIC_CULTURE,
            affiliatedUnit = "管理学院 / 经济贸易学院",
            activityVenue = "11号教学楼经管实训室",
            summary = "模拟企业供应链、财务预算与市场营销博弈，组织全国大学生企业竞争模拟大赛与商科实战实训。",
            qqGroupNumber = "710492836",
            highlights = listOf("商科沙盘实战", "全国经管大赛", "理工经管皆可")
        ),

        // 3. 文艺展演类
        CampusClubItem(
            id = "club_guitar",
            name = "吉他与现代音乐协会",
            category = CampusClubCategory.ART_PERFORMANCE,
            activityVenue = "大学生活动中心排练室 / 东操场看台",
            affiliatedUnit = "校团委社团管理部",
            summary = "汇聚木吉他指弹、电声乐队与流行主唱爱好者，定期举办莲花街草坪音乐节、迎新摇滚夜与零基础教学。",
            qqGroupNumber = "864201937",
            highlights = listOf("草坪音乐节", "校园乐队招募", "木吉他零基础")
        ),
        CampusClubItem(
            id = "club_hanfu",
            name = "汉服与中华传统文化社",
            category = CampusClubCategory.ART_PERFORMANCE,
            affiliatedUnit = "设计艺术学院",
            activityVenue = "易斯顿美术楼 / 中心湖畔",
            summary = "开展传统汉服妆造体验、非遗手作（簪花/漆扇/香囊）、中秋游园灯会与国风摄影外拍活动。",
            qqGroupNumber = "629401835",
            highlights = listOf("中秋游园会", "非遗手作", "国风摄影约拍")
        ),
        CampusClubItem(
            id = "club_photo",
            name = "光影摄影与航拍协会",
            category = CampusClubCategory.ART_PERFORMANCE,
            affiliatedUnit = "新闻与传播学院",
            activityVenue = "外景采风 / 7号教学楼影棚",
            summary = "分享相机参数入门、手机摄影构图、Lightroom后期调色与校园四季风光/人像互勉约拍。",
            qqGroupNumber = "758291046",
            highlights = listOf("人像风光约拍", "后期调色教学", "手机单反皆宜")
        ),

        // 4. 体育竞技类
        CampusClubItem(
            id = "club_skate",
            name = "Freestyle 轮滑社",
            category = CampusClubCategory.SPORTS_FITNESS,
            affiliatedUnit = "体育学院",
            activityVenue = "钟楼广场东侧轮滑场",
            summary = "莲花街晚间人气社团！提供新生平地花式、刹停与速滑带训，常年组织郑大/工大高校联谊刷街。",
            qqGroupNumber = "592038471",
            highlights = listOf("学长手把手带训", "高校联谊刷街", "晚间钟楼集结")
        ),
        CampusClubItem(
            id = "club_badminton",
            name = "羽毛球协会",
            category = CampusClubCategory.SPORTS_FITNESS,
            affiliatedUnit = "体育学院",
            activityVenue = "莲花街体育馆羽毛球场",
            summary = "组织日常球搭子约球、步伐与挥拍基础指导、「工大杯」羽毛球单项赛及院系团体混合赛。",
            qqGroupNumber = "830192746",
            highlights = listOf("日常找球搭子", "工大杯积分赛", "新手步伐指导")
        ),
        CampusClubItem(
            id = "club_night_run",
            name = "荧光夜跑与马拉松协会",
            category = CampusClubCategory.SPORTS_FITNESS,
            affiliatedUnit = "体育学院",
            activityVenue = "北区/东区田径运动场",
            summary = "陪伴晚间健康打卡、体测800/1000米提速训练、荧光夜跑嘉年华及郑开马拉松高校方阵报名。",
            qqGroupNumber = "671829304",
            highlights = listOf("体测满分特训", "郑开马拉松", "晚间荧光打卡")
        ),

        // 5. 志愿与校级组织类
        CampusClubItem(
            id = "club_volunteer",
            name = "校青年志愿者协会",
            category = CampusClubCategory.VOLUNTEER_ORG,
            affiliatedUnit = "校团委志愿服务指导中心",
            activityVenue = "大学生活动中心 204",
            summary = "统筹全校志愿汇时长认证、迎新接站志愿服务、图书馆整理、社区支教及大型赛会志愿者招募。",
            qqGroupNumber = "901284756",
            highlights = listOf("志愿汇时长认证", "迎新与支教", "第二课堂学分")
        ),
        CampusClubItem(
            id = "club_art_troupe",
            name = "河南工业大学大学生艺术团",
            category = CampusClubCategory.VOLUNTEER_ORG,
            affiliatedUnit = "校团委文化艺术中心",
            activityVenue = "大学生活动中心大礼堂",
            summary = "下设合唱团、舞蹈团、器乐团、话剧团与礼仪主持队，承担校迎新晚会、毕业歌会与省级大学生艺术展演。",
            qqGroupNumber = "746192038",
            highlights = listOf("迎新晚会主创", "声乐器乐舞蹈", "省级艺术展演")
        ),
        CampusClubItem(
            id = "club_broadcast",
            name = "工大之声广播站 & 新媒体中心",
            category = CampusClubCategory.VOLUNTEER_ORG,
            affiliatedUnit = "党委宣传部 / 校团委",
            activityVenue = "行政楼融媒体中心 / 广播室",
            summary = "负责莲花街校园早晚间广播点歌、校园官微推文采编、校园短视频拍摄剪辑与大型活动播音主持。",
            qqGroupNumber = "683920417",
            highlights = listOf("校园广播点歌", "播音与采访", "新媒体摄影剪辑")
        )
    )
}
