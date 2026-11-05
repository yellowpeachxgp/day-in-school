package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 河南工业大学（莲花街校区）离线种子与演示数据集。
 * 包含：
 * 1. 莲花街校区标准 10 节作息时间表（可由用户在设置中自由编辑）
 * 2. 莲花街校区已核验建筑 POI 与教务教室字符串别名映射
 * 3. 覆盖单双周、跨时段、周六课程（适配 2026-10-31 第 8 教学周周六）、冲突课程与单日调课的模拟课表
 * 4. 覆盖多学期、重修去重、0 学分实践课、通过制课程的模拟学业成绩
 */
object HautMockSeedData {

    const val DEFAULT_TIMETABLE_ID = "haut_lianhuajie_default"
    const val CURRENT_TERM_ID = "2026-2027-1"

    val defaultStudent = StudentProfile(
        id = "stu_demo",
        schoolId = "2024160101XX",
        displayName = "未设置姓名",
        major = "计算机科学与技术（莲花街校区）",
        enrollmentYear = 2024
    )

    val defaultTerms = listOf(
        Term(
            id = "2023-2024-1",
            academicYear = "2023-2024",
            termNumber = 1,
            startDate = LocalDate.of(2023, 9, 4),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = false
        ),
        Term(
            id = "2023-2024-2",
            academicYear = "2023-2024",
            termNumber = 2,
            startDate = LocalDate.of(2024, 2, 26),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = false
        ),
        Term(
            id = "2024-2025-1",
            academicYear = "2024-2025",
            termNumber = 1,
            startDate = LocalDate.of(2024, 9, 2),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = false
        ),
        Term(
            id = "2024-2025-2",
            academicYear = "2024-2025",
            termNumber = 2,
            startDate = LocalDate.of(2025, 2, 24),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = false
        ),
        Term(
            id = "2025-2026-1",
            academicYear = "2025-2026",
            termNumber = 1,
            startDate = LocalDate.of(2025, 9, 1),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = false
        ),
        Term(
            id = "2025-2026-2",
            academicYear = "2025-2026",
            termNumber = 2,
            startDate = LocalDate.of(2026, 3, 2),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 19,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = false
        ),
        Term(
            id = CURRENT_TERM_ID,
            academicYear = "2026-2027",
            termNumber = 1,
            startDate = LocalDate.of(2026, 8, 31),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = DEFAULT_TIMETABLE_ID,
            current = true
        )
    )

    val defaultPeriodTimes = listOf(
        PeriodTime("pt_1", DEFAULT_TIMETABLE_ID, 1, LocalTime.of(8, 30), LocalTime.of(9, 15), "上午"),
        PeriodTime("pt_2", DEFAULT_TIMETABLE_ID, 2, LocalTime.of(9, 20), LocalTime.of(10, 5), "上午"),
        PeriodTime("pt_3", DEFAULT_TIMETABLE_ID, 3, LocalTime.of(10, 25), LocalTime.of(11, 10), "上午"),
        PeriodTime("pt_4", DEFAULT_TIMETABLE_ID, 4, LocalTime.of(11, 15), LocalTime.of(12, 0), "上午"),
        PeriodTime("pt_5", DEFAULT_TIMETABLE_ID, 5, LocalTime.of(14, 30), LocalTime.of(15, 15), "下午"),
        PeriodTime("pt_6", DEFAULT_TIMETABLE_ID, 6, LocalTime.of(15, 20), LocalTime.of(16, 5), "下午"),
        PeriodTime("pt_7", DEFAULT_TIMETABLE_ID, 7, LocalTime.of(16, 25), LocalTime.of(17, 10), "下午"),
        PeriodTime("pt_8", DEFAULT_TIMETABLE_ID, 8, LocalTime.of(17, 15), LocalTime.of(18, 0), "下午"),
        PeriodTime("pt_9", DEFAULT_TIMETABLE_ID, 9, LocalTime.of(19, 30), LocalTime.of(20, 15), "晚间"),
        PeriodTime("pt_10", DEFAULT_TIMETABLE_ID, 10, LocalTime.of(20, 25), LocalTime.of(21, 10), "晚间")
    )

    val lianhuajieBuildings = listOf(
        Building(
            id = "b_t1",
            name = "31号楼（1号教学楼）",
            shortName = "31号楼",
            latitude = 34.830310,
            longitude = 113.540590,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南侧正门", "东连廊入口", "西侧楼梯口"),
            description = "6层公共教学与电气信息实验楼（311xx-315xx / 11xx-15xx教室）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t2",
            name = "32号楼（土建楼/2教）",
            shortName = "32号楼",
            latitude = 34.830190,
            longitude = 113.539030,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门大厅", "东侧连廊"),
            description = "6层土木建筑学院与公共教学楼（321xx-325xx / 21xx-25xx教室）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t3",
            name = "3号楼（文科楼）",
            shortName = "3号楼",
            latitude = 34.830130,
            longitude = 113.547980,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门主入口", "西侧中庭"),
            description = "6层文科组团楼：经贸、管理、法学、马院与智慧教室（3101-3635）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t4",
            name = "4号教学楼",
            shortName = "4号楼",
            latitude = 34.830120,
            longitude = 113.546720,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("西门大厅", "南侧通道"),
            description = "5层公共基础课与阶梯教室教学楼（4101-4530）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t5",
            name = "5号楼（外语与计算中心）",
            shortName = "5号楼",
            latitude = 34.831240,
            longitude = 113.546790,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门大厅", "西门通道"),
            description = "5层外语学院、新闻与传播学院、计算中心机房与演播厅（5101-5530）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t6",
            name = "6号楼（信息与人工智能楼）",
            shortName = "6号楼",
            latitude = 34.830090,
            longitude = 113.544160,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门主入口", "北侧连廊"),
            description = "5层信息科学与工程学院、人工智能与大数据学院教学楼（6101-6530）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t7",
            name = "7号楼（机电教学楼）",
            shortName = "7号楼",
            latitude = 34.831290,
            longitude = 113.543840,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门主入口", "东侧台阶"),
            description = "5层机电工程学院与公共基础教学楼（7101-7530）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t8",
            name = "8号楼（粮油食品楼）",
            shortName = "8号楼",
            latitude = 34.830080,
            longitude = 113.542080,
            category = CampusCategory.LAB,
            entrances = listOf("南侧正门", "北侧实验区通道"),
            description = "6层粮油食品学院、国家工程实验室与专业教室（8101-8530）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t9",
            name = "9号楼（化工与生物楼）",
            shortName = "9号楼",
            latitude = 34.831240,
            longitude = 113.541790,
            category = CampusCategory.LAB,
            entrances = listOf("南侧正门", "东侧入口"),
            description = "6层化学化工学院、生物工程学院与材料学院教学实验楼（9101-9530）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t18",
            name = "18号楼（艺术楼）",
            shortName = "艺术楼",
            latitude = 34.832360,
            longitude = 113.547990,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("西门展厅入口", "南侧入口"),
            description = "3层设计艺术学院专业教室与美术展厅",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_bell",
            name = "24号楼（钟楼）",
            shortName = "钟楼",
            latitude = 34.833690,
            longitude = 113.546520,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("钟楼广场正门", "西侧事务大厅"),
            description = "3层校园标志建筑：校园卡务中心、大学生事务与活动中心、生活商超",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t25",
            name = "25号楼（学生事务中心）",
            shortName = "事务中心",
            latitude = 34.833580,
            longitude = 113.545620,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南侧大厅入口"),
            description = "3层学生综合事务服务大厅与就业指导中心",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_lib",
            name = "图书馆",
            shortName = "图书馆",
            latitude = 34.832000,
            longitude = 113.545240,
            category = CampusCategory.LIBRARY,
            entrances = listOf("南侧二楼刷卡主入口", "西侧一楼报告厅入口"),
            description = "11层校区文献信息中心、专业阅览室与自习区",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_admin",
            name = "办公楼（行政楼）",
            shortName = "办公楼",
            latitude = 34.828980,
            longitude = 113.546900,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南广场正门", "北侧大厅"),
            description = "8层学校党政机关办公楼、教务处与财务处",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t34",
            name = "34号楼（实验实训中心）",
            shortName = "34号楼",
            latitude = 34.829000,
            longitude = 113.538930,
            category = CampusCategory.LAB,
            entrances = listOf("北门主入口", "东侧通道"),
            description = "实验实训中心大楼与专业实验室（341xx-345xx）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_eng",
            name = "35号楼（工程训练中心）",
            shortName = "工训中心",
            latitude = 34.831340,
            longitude = 113.537830,
            category = CampusCategory.LAB,
            entrances = listOf("东门主通道", "南门实训车间入口"),
            description = "4层金工实习、工业创客与机电综合工程训练中心（351xx-354xx）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t38",
            name = "38号楼（综合实验楼）",
            shortName = "38号楼",
            latitude = 34.830290,
            longitude = 113.537990,
            category = CampusCategory.LAB,
            entrances = listOf("东侧正门"),
            description = "4层基础实验与学科创新实验室（381xx-384xx）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_t40",
            name = "40号楼（储运与标准化中心）",
            shortName = "40号楼",
            latitude = 34.829660,
            longitude = 113.537710,
            category = CampusCategory.LAB,
            entrances = listOf("东侧正门", "模拟粮仓连廊"),
            description = "4层粮食储运中心、标准化中心与专业实验室",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_canteen1",
            name = "第一餐厅",
            shortName = "一餐厅",
            latitude = 34.833440,
            longitude = 113.548450,
            category = CampusCategory.CANTEEN,
            entrances = listOf("西门主入口", "南门入口"),
            description = "莲花街校区东北生活区综合学生餐厅",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_canteen_small",
            name = "小食堂（风味餐厅）",
            shortName = "小食堂",
            latitude = 34.830570,
            longitude = 113.549520,
            category = CampusCategory.CANTEEN,
            entrances = listOf("西侧入口"),
            description = "东侧教学区旁特色风味就餐点",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_gate_south",
            name = "莲花街南门",
            shortName = "南门",
            latitude = 34.828700,
            longitude = 113.545310,
            category = CampusCategory.GATE,
            entrances = listOf("南门西闸机", "南门东闸机"),
            description = "莲花街校区正门，紧邻地铁1号线河南工业大学站",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_gate_west",
            name = "长椿路西门",
            shortName = "西门",
            latitude = 34.831910,
            longitude = 113.536650,
            category = CampusCategory.GATE,
            entrances = listOf("西门人行通道"),
            description = "长椿路校区西出入口，近E/F公寓区与工程训练中心",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_gate_east",
            name = "东门",
            shortName = "东门",
            latitude = 34.831010,
            longitude = 113.550960,
            category = CampusCategory.GATE,
            entrances = listOf("东门通道"),
            description = "校区东侧出入口，毗邻校医院与后勤楼",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_dorm_ab",
            name = "学生公寓A/B区",
            shortName = "A/B公寓",
            latitude = 34.834650,
            longitude = 113.545100,
            category = CampusCategory.DORMITORY,
            entrances = listOf("南侧园区门禁"),
            description = "北苑中部学生公寓A区（1-4座）与B区（1-4座）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_dorm_ju",
            name = "学生公寓C/D区",
            shortName = "C/D公寓",
            latitude = 34.834500,
            longitude = 113.548800,
            category = CampusCategory.DORMITORY,
            entrances = listOf("南侧门禁", "第一餐厅北步道"),
            description = "东北生活区学生公寓C区（1-4座）与D区（1-4座）",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_dorm_ef",
            name = "学生公寓E/F区",
            shortName = "E/F公寓",
            latitude = 34.833150,
            longitude = 113.537750,
            category = CampusCategory.DORMITORY,
            entrances = listOf("东侧门禁", "西门步道"),
            description = "西侧生活区学生公寓E区（1-4座）、F区高层公寓与e区美食源",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_express",
            name = "菜鸟驿站（快递中心）",
            shortName = "菜鸟驿站",
            latitude = 34.833390,
            longitude = 113.549860,
            category = CampusCategory.EXPRESS,
            entrances = listOf("西侧取件入口"),
            description = "第一餐厅东侧校园综合快递收发服务中心",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_hospital",
            name = "校医院与后勤楼",
            shortName = "校医院",
            latitude = 34.830610,
            longitude = 113.550700,
            category = CampusCategory.EXPRESS,
            entrances = listOf("西门门诊入口"),
            description = "5层校区医疗门诊、医保服务与后勤保障大楼",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_stadium",
            name = "体育训练中心与田径场",
            shortName = "体育中心",
            latitude = 34.832340,
            longitude = 113.539230,
            category = CampusCategory.SPORTS,
            entrances = listOf("东侧主入口", "北侧体育场看台"),
            description = "4层体育训练中心场馆、北侧标准田径运动场与球类场馆",
            verifiedAt = LocalDate.of(2026, 11, 5)
        )
    )

    val defaultBuildingAliases = listOf(
        BuildingAlias("al_1", "b_t1", "1号教学楼", 1.0f, true),
        BuildingAlias("al_1b", "b_t1", "莲1号教学楼", 1.0f, true),
        BuildingAlias("al_1c", "b_t1", "莲01号教学楼", 1.0f, true),
        BuildingAlias("al_1d", "b_t1", "31号楼", 1.0f, true),
        BuildingAlias("al_1e", "b_t1", "莲31号", 1.0f, true),
        BuildingAlias("al_1f", "b_t1", "^31[1-6]\\d{2}$", 0.96f, true),
        BuildingAlias("al_2", "b_t1", "一号教学楼", 0.98f, true),
        BuildingAlias("al_3", "b_t1", "1教", 0.95f, true),
        BuildingAlias("al_4", "b_t2", "2号教学楼", 1.0f, true),
        BuildingAlias("al_4b", "b_t2", "莲2号教学楼", 1.0f, true),
        BuildingAlias("al_4c", "b_t2", "莲02号教学楼", 1.0f, true),
        BuildingAlias("al_4d", "b_t2", "32号楼", 1.0f, true),
        BuildingAlias("al_4e", "b_t2", "土建楼", 1.0f, true),
        BuildingAlias("al_4f", "b_t2", "^32[1-6]\\d{2}$", 0.96f, true),
        BuildingAlias("al_5", "b_t2", "二号教学楼", 0.98f, true),
        BuildingAlias("al_6", "b_t2", "教学楼A", 0.90f, true),
        BuildingAlias("al_7", "b_t2", "A座", 0.72f, false), // 模糊别名：触发用户确认弹窗
        BuildingAlias("al_8", "b_t3", "3号教学楼", 1.0f, true),
        BuildingAlias("al_8a", "b_t3", "3号楼", 1.0f, true),
        BuildingAlias("al_8b", "b_t3", "莲3号教学楼", 1.0f, true),
        BuildingAlias("al_8c", "b_t3", "莲03号教学楼", 1.0f, true),
        BuildingAlias("al_8d", "b_t3", "文科组团楼", 1.0f, true),
        BuildingAlias("al_8e", "b_t3", "文科楼", 1.0f, true),
        BuildingAlias("al_8f", "b_t3", "^3[1-6]\\d{2}$", 0.96f, true),
        BuildingAlias("al_9", "b_t3", "教学楼B", 0.88f, true),
        BuildingAlias("al_10", "b_t4", "4号教学楼", 1.0f, true),
        BuildingAlias("al_10a", "b_t4", "4号楼", 1.0f, true),
        BuildingAlias("al_10b", "b_t4", "莲4号教学楼", 1.0f, true),
        BuildingAlias("al_10c", "b_t4", "莲04号教学楼", 1.0f, true),
        BuildingAlias("al_10d", "b_t4", "^4[1-5]\\d{2}$", 0.96f, true),
        BuildingAlias("al_11", "b_t5", "计算中心", 0.95f, true),
        BuildingAlias("al_5a", "b_t5", "5号教学楼", 1.0f, true),
        BuildingAlias("al_5b", "b_t5", "5号楼", 1.0f, true),
        BuildingAlias("al_5c", "b_t5", "莲5号", 1.0f, true),
        BuildingAlias("al_5d", "b_t5", "^5[1-5]\\d{2}$", 0.96f, true),
        BuildingAlias("al_6a", "b_t6", "6号教学楼", 1.0f, true),
        BuildingAlias("al_6b", "b_t6", "6号楼", 1.0f, true),
        BuildingAlias("al_6c", "b_t6", "莲6号", 1.0f, true),
        BuildingAlias("al_6d", "b_t6", "^6[1-5]\\d{2}$", 0.96f, true),
        BuildingAlias("al_7a", "b_t7", "7号教学楼", 1.0f, true),
        BuildingAlias("al_7b", "b_t7", "7号楼", 1.0f, true),
        BuildingAlias("al_7c", "b_t7", "莲7号", 1.0f, true),
        BuildingAlias("al_7d", "b_t7", "^7[1-5]\\d{2}$", 0.96f, true),
        BuildingAlias("al_8g", "b_t8", "8号教学楼", 1.0f, true),
        BuildingAlias("al_8h", "b_t8", "8号楼", 1.0f, true),
        BuildingAlias("al_8i", "b_t8", "粮油食品楼", 0.98f, true),
        BuildingAlias("al_8j", "b_t8", "^8[1-6]\\d{2}$", 0.96f, true),
        BuildingAlias("al_9a", "b_t9", "9号教学楼", 1.0f, true),
        BuildingAlias("al_9b", "b_t9", "9号楼", 1.0f, true),
        BuildingAlias("al_9c", "b_t9", "^9[1-6]\\d{2}$", 0.96f, true),
        BuildingAlias("al_18a", "b_t18", "18号楼", 1.0f, true),
        BuildingAlias("al_18b", "b_t18", "艺术楼", 1.0f, true),
        BuildingAlias("al_12", "b_bell", "钟楼", 0.98f, true),
        BuildingAlias("al_12b", "b_bell", "24号楼", 1.0f, true),
        BuildingAlias("al_25a", "b_t25", "25号楼", 1.0f, true),
        BuildingAlias("al_25b", "b_t25", "学生事务中心", 1.0f, true),
        BuildingAlias("al_34a", "b_t34", "34号楼", 1.0f, true),
        BuildingAlias("al_34b", "b_t34", "实验实训中心", 1.0f, true),
        BuildingAlias("al_34c", "b_t34", "莲34号", 1.0f, true),
        BuildingAlias("al_13", "b_eng", "训练中心", 0.96f, true),
        BuildingAlias("al_13a", "b_eng", "工程训练中心", 1.0f, true),
        BuildingAlias("al_13b", "b_eng", "35号楼", 1.0f, true),
        BuildingAlias("al_13d", "b_eng", "金工实习", 0.95f, true),
        BuildingAlias("al_38a", "b_t38", "38号楼", 1.0f, true),
        BuildingAlias("al_40a", "b_t40", "40号楼", 1.0f, true),
        BuildingAlias("al_40b", "b_t40", "储运中心", 0.96f, true),
        BuildingAlias("al_14", "b_stadium", "田径场", 0.95f, true),
        BuildingAlias("al_14b", "b_stadium", "莲花操场", 1.0f, true),
        BuildingAlias("al_14c", "b_stadium", "体育训练中心", 1.0f, true),
        BuildingAlias("al_14d", "b_stadium", "体育馆", 0.95f, true)
    )

    val defaultCourses = listOf(
        Course("c_ds", "JW_CS201", "数据结构与算法", "张伟 教授", 0xFF2563EB, BigDecimal("4.0"), CourseSource.OFFICIAL_JW),
        Course("c_os", "JW_CS205", "操作系统原理", "李娜 副教授", 0xFFEA580C, BigDecimal("3.5"), CourseSource.OFFICIAL_JW),
        Course("c_net", "JW_CS208", "计算机网络", "赵敏 讲师", 0xFF059669, BigDecimal("3.5"), CourseSource.OFFICIAL_JW),
        Course("c_db", "JW_CS210", "数据库系统概论", "陈志强 副教授", 0xFF7C3AED, BigDecimal("3.0"), CourseSource.OFFICIAL_JW),
        Course("c_grain", "JW_GR102", "智慧粮仓与物联网导论", "王建国 教授", 0xFFD97706, BigDecimal("2.0"), CourseSource.OFFICIAL_JW),
        Course("c_prob", "JW_MA204", "概率论与数理统计", "孙芳 副教授", 0xFF0891B2, BigDecimal("3.0"), CourseSource.OFFICIAL_JW),
        Course("c_pe", "JW_PE103", "大学体育(3)·羽毛球", "周鹏 讲师", 0xFF16A34A, BigDecimal("1.0"), CourseSource.OFFICIAL_JW),
        Course("c_pol", "JW_PL104", "形势与政策", "马晓东 讲师", 0xFFDC2626, BigDecimal("1.0"), CourseSource.OFFICIAL_JW)
    )

    val defaultRules = listOf(
        // 周一
        CourseScheduleRule("r_mon_1", "c_ds", CURRENT_TERM_ID, DayOfWeek.MONDAY, 1, 2, (1..16).toSet(), "1号教学楼1204", "b_t1"),
        CourseScheduleRule("r_mon_2", "c_prob", CURRENT_TERM_ID, DayOfWeek.MONDAY, 3, 4, (1..16).toSet(), "2号教学楼2106", "b_t2"),
        CourseScheduleRule("r_mon_3", "c_net", CURRENT_TERM_ID, DayOfWeek.MONDAY, 5, 6, (1..15).filter { it % 2 == 1 }.toSet(), "3号教学楼3302", "b_t3"),
        // 周二
        CourseScheduleRule("r_tue_1", "c_os", CURRENT_TERM_ID, DayOfWeek.TUESDAY, 1, 2, (1..16).toSet(), "2号教学楼2306", "b_t2"),
        CourseScheduleRule("r_tue_2", "c_db", CURRENT_TERM_ID, DayOfWeek.TUESDAY, 5, 6, (1..16).toSet(), "4号教学楼4208", "b_t4"),
        CourseScheduleRule("r_tue_3", "c_grain", CURRENT_TERM_ID, DayOfWeek.TUESDAY, 9, 10, (2..16).filter { it % 2 == 0 }.toSet(), "钟楼报告厅201", "b_bell"),
        // 周三（含下午 5-6 节选修冲突演示）
        CourseScheduleRule("r_wed_1", "c_ds", CURRENT_TERM_ID, DayOfWeek.WEDNESDAY, 3, 4, (1..16).toSet(), "1号教学楼1204", "b_t1"),
        CourseScheduleRule("r_wed_2", "c_net", CURRENT_TERM_ID, DayOfWeek.WEDNESDAY, 5, 6, (1..16).toSet(), "3号教学楼3302", "b_t3"),
        CourseScheduleRule("r_wed_conflict", "c_grain", CURRENT_TERM_ID, DayOfWeek.WEDNESDAY, 6, 7, setOf(4, 8, 12), "A座204", null),
        // 周四
        CourseScheduleRule("r_thu_1", "c_prob", CURRENT_TERM_ID, DayOfWeek.THURSDAY, 1, 2, (1..14).toSet(), "2号教学楼2106", "b_t2"),
        CourseScheduleRule("r_thu_2", "c_os", CURRENT_TERM_ID, DayOfWeek.THURSDAY, 3, 4, (1..16).toSet(), "2号教学楼2306", "b_t2"),
        CourseScheduleRule("r_thu_3", "c_pe", CURRENT_TERM_ID, DayOfWeek.THURSDAY, 7, 8, (1..16).toSet(), "中心体育场与体育馆", "b_stadium"),
        // 周五
        CourseScheduleRule("r_fri_1", "c_db", CURRENT_TERM_ID, DayOfWeek.FRIDAY, 1, 2, (1..16).toSet(), "4号教学楼4208", "b_t4"),
        CourseScheduleRule("r_fri_2", "c_ds", CURRENT_TERM_ID, DayOfWeek.FRIDAY, 5, 6, (2..16).filter { it % 2 == 0 }.toSet(), "4号教学楼4310机房", "b_t4"),
        // 周六（使 2026-10-31 第 8 教学周周六打开即呈现完整已结束/正在上/未开始时间轴！）
        CourseScheduleRule("r_sat_1", "c_grain", CURRENT_TERM_ID, DayOfWeek.SATURDAY, 1, 2, (1..16).toSet(), "1号教学楼1104", "b_t1"),
        CourseScheduleRule("r_sat_2", "c_ds", CURRENT_TERM_ID, DayOfWeek.SATURDAY, 3, 4, (1..16).toSet(), "2号教学楼2204", "b_t2"),
        CourseScheduleRule("r_sat_3", "c_os", CURRENT_TERM_ID, DayOfWeek.SATURDAY, 5, 6, (2..16).filter { it % 2 == 0 }.toSet(), "4号教学楼4102机房", "b_t4"),
        CourseScheduleRule("r_sat_4", "c_pol", CURRENT_TERM_ID, DayOfWeek.SATURDAY, 9, 10, setOf(4, 8, 12, 16), "钟楼报告厅101", "b_bell")
    )

    val defaultCalendarOverrides = listOf(
        CalendarOverride(
            id = "cal_national_day",
            termId = CURRENT_TERM_ID,
            date = LocalDate.of(2026, 10, 1),
            overrideType = CalendarOverrideType.HOLIDAY_NO_CLASS,
            title = "国庆节法定节假日停课"
        ),
        CalendarOverride(
            id = "cal_national_makeup",
            termId = CURRENT_TERM_ID,
            date = LocalDate.of(2026, 10, 10),
            overrideType = CalendarOverrideType.MAKEUP_FOLLOW_DATE,
            followDate = LocalDate.of(2026, 10, 2),
            title = "国庆调休补课（按第4周周五课表执行）"
        )
    )

    val defaultOccurrenceOverrides = listOf(
        OccurrenceOverride(
            id = "occ_demo_move",
            termId = CURRENT_TERM_ID,
            courseRuleId = "r_fri_2",
            courseId = "c_ds",
            date = LocalDate.of(2026, 10, 30),
            room = "4号教学楼4502创新实验室",
            buildingId = "b_t4",
            canceled = false,
            reason = "上机实验换至4502室"
        )
    )

    val defaultGradeAttempts: List<GradeAttempt> = run {
        val sync2026Jan = Instant.parse("2026-01-20T08:00:00Z")
        val sync2026Jul = Instant.parse("2026-07-10T08:00:00Z")
        listOf(
            // 2025-2026 第 1 学期
            GradeAttempt("ga_1", "2025-2026-1", "2025-2026", 1, "MATH101", "高等数学A(1)", CourseCategory.COMPULSORY, BigDecimal("5.0"), "56", BigDecimal("56"), BigDecimal("0.0"), AttemptType.NORMAL, false, false, CourseSource.OFFICIAL_JW, sync2026Jan),
            GradeAttempt("ga_2", "2025-2026-1", "2025-2026", 1, "CS101", "C语言程序设计", CourseCategory.COMPULSORY, BigDecimal("4.0"), "91", BigDecimal("91"), BigDecimal("4.1"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jan),
            GradeAttempt("ga_3", "2025-2026-1", "2025-2026", 1, "ENG101", "大学英语(1)", CourseCategory.COMPULSORY, BigDecimal("3.0"), "84", BigDecimal("84"), BigDecimal("3.4"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jan),
            GradeAttempt("ga_4", "2025-2026-1", "2025-2026", 1, "MATH103", "线性代数", CourseCategory.COMPULSORY, BigDecimal("3.0"), "88", BigDecimal("88"), BigDecimal("3.8"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jan),
            GradeAttempt("ga_5", "2025-2026-1", "2025-2026", 1, "MIL101", "军事技能训练", CourseCategory.PRACTICE, BigDecimal("2.0"), "优秀", null, BigDecimal("4.5"), AttemptType.FIVE_LEVEL, true, false, CourseSource.OFFICIAL_JW, sync2026Jan),

            // 2025-2026 第 2 学期（含高等数学重修通过、0 学分实践、通过制课程）
            GradeAttempt("ga_6", "2025-2026-2", "2025-2026", 2, "MATH101", "高等数学A(1)", CourseCategory.COMPULSORY, BigDecimal("5.0"), "86", BigDecimal("86"), BigDecimal("3.6"), AttemptType.RETAKE, true, false, CourseSource.OFFICIAL_JW, sync2026Jul),
            GradeAttempt("ga_7", "2025-2026-2", "2025-2026", 2, "MATH102", "高等数学A(2)", CourseCategory.COMPULSORY, BigDecimal("5.0"), "82", BigDecimal("82"), BigDecimal("3.2"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jul),
            GradeAttempt("ga_8", "2025-2026-2", "2025-2026", 2, "CS104", "离散数学", CourseCategory.COMPULSORY, BigDecimal("3.5"), "94", BigDecimal("94"), BigDecimal("4.4"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jul),
            GradeAttempt("ga_9", "2025-2026-2", "2025-2026", 2, "CS106", "面向对象程序设计(Java)", CourseCategory.COMPULSORY, BigDecimal("3.5"), "89", BigDecimal("89"), BigDecimal("3.9"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jul),
            GradeAttempt("ga_10", "2025-2026-2", "2025-2026", 2, "PHY101", "大学物理B", CourseCategory.COMPULSORY, BigDecimal("3.5"), "72", BigDecimal("72"), BigDecimal("2.2"), AttemptType.NORMAL, true, false, CourseSource.OFFICIAL_JW, sync2026Jul),
            GradeAttempt("ga_11", "2025-2026-2", "2025-2026", 2, "GEN201", "中原粮食文化导论", CourseCategory.PUBLIC_ELECTIVE, BigDecimal("2.0"), "通过", null, null, AttemptType.PASS_FAIL, true, false, CourseSource.OFFICIAL_JW, sync2026Jul),
            GradeAttempt("ga_12", "2025-2026-2", "2025-2026", 2, "LAB001", "劳动教育实践", CourseCategory.PRACTICE, BigDecimal("0.0"), "合格", null, null, AttemptType.PASS_FAIL, true, false, CourseSource.OFFICIAL_JW, sync2026Jul)
        )
    }
}
