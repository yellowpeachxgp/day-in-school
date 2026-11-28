package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusCategory
import java.time.LocalDate

/**
 * 莲花街楼宇的纯 JVM 迁移快照。
 *
 * 原事实源是 Android-only core/database/HautMockSeedData.kt；campus-graph 不能反向依赖
 * Room/Android 模块，所以这里保留一次性迁移快照。它不是第二权威事实源：字段变更必须先改
 * legacy 种子并同步更新本快照与特征测试。
 */
internal data class LegacyBuildingSeed(
    val id: String,
    val name: String,
    val shortName: String,
    val latitude: Double,
    val longitude: Double,
    val category: CampusCategory,
    val entrances: List<String>,
    val description: String,
    val verifiedAt: LocalDate
)

internal data class LegacyBuildingAliasSeed(
    val buildingId: String,
    val value: String,
    val confidence: Float,
    val verified: Boolean
)

internal object LegacyHautLianhuajieBuildingSeed {
    val buildings: List<LegacyBuildingSeed> = listOf(
        LegacyBuildingSeed("b_t1", "31号楼（电气信息楼）", "31号楼", 34.830310, 113.540590, CampusCategory.TEACHING_BUILDING, listOf("南侧正门", "东连廊入口", "西侧楼梯口"), "6层公共教学与电气信息实验楼（311xx-315xx教室）", LocalDate.of(2026, 11, 9)),
        LegacyBuildingSeed("b_t2", "32号楼（土建楼）", "32号楼", 34.830190, 113.539030, CampusCategory.TEACHING_BUILDING, listOf("南门大厅", "东侧连廊"), "6层土木建筑学院与公共教学楼（321xx-325xx教室）", LocalDate.of(2026, 11, 9)),
        LegacyBuildingSeed("b_t3", "3号楼（文科楼）", "3号楼", 34.830130, 113.547980, CampusCategory.TEACHING_BUILDING, listOf("南门主入口", "西侧中庭"), "6层文科组团楼：经贸、管理、法学、马院与智慧教室（3101-3635）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t4", "4号教学楼", "4号楼", 34.830120, 113.546720, CampusCategory.TEACHING_BUILDING, listOf("西门大厅", "南侧通道"), "5层公共基础课与阶梯教室教学楼（4101-4530）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t5", "5号楼（外语与计算中心）", "5号楼", 34.831240, 113.546790, CampusCategory.TEACHING_BUILDING, listOf("南门大厅", "西门通道"), "5层外语学院、新闻与传播学院、计算中心机房与演播厅（5101-5530）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t6", "6号楼（信息与人工智能楼）", "6号楼", 34.830090, 113.544160, CampusCategory.TEACHING_BUILDING, listOf("南门主入口", "北侧连廊"), "5层信息科学与工程学院、人工智能与大数据学院教学楼（6101-6530）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t7", "7号楼（机电教学楼）", "7号楼", 34.831290, 113.543840, CampusCategory.TEACHING_BUILDING, listOf("南门主入口", "东侧台阶"), "5层机电工程学院与公共基础教学楼（7101-7530）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t8", "8号楼（粮油食品楼）", "8号楼", 34.830080, 113.542080, CampusCategory.LAB, listOf("南侧正门", "北侧实验区通道"), "6层粮油食品学院、国家工程实验室与专业教室（8101-8530）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t9", "9号楼（化工与生物楼）", "9号楼", 34.831240, 113.541790, CampusCategory.LAB, listOf("南侧正门", "东侧入口"), "6层化学化工学院、生物工程学院与材料学院教学实验楼（9101-9530）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t18", "18号楼（艺术楼）", "艺术楼", 34.832360, 113.547990, CampusCategory.TEACHING_BUILDING, listOf("西门展厅入口", "南侧入口"), "3层设计艺术学院专业教室与美术展厅", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_bell", "24号楼（钟楼）", "钟楼", 34.833690, 113.546520, CampusCategory.TEACHING_BUILDING, listOf("钟楼广场正门", "西侧事务大厅"), "3层校园标志建筑：校园卡务中心、大学生事务与活动中心、生活商超", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t25", "25号楼（学生事务中心）", "事务中心", 34.833580, 113.545620, CampusCategory.TEACHING_BUILDING, listOf("南侧大厅入口"), "3层学生综合事务服务大厅与就业指导中心", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_lib", "图书馆", "图书馆", 34.832000, 113.545240, CampusCategory.LIBRARY, listOf("南侧二楼刷卡主入口", "西侧一楼报告厅入口"), "11层校区文献信息中心、专业阅览室与自习区", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_admin", "办公楼（行政楼）", "办公楼", 34.828980, 113.546900, CampusCategory.TEACHING_BUILDING, listOf("南广场正门", "北侧大厅"), "8层学校党政机关办公楼、教务处与财务处", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t34", "34号楼（实验实训中心）", "34号楼", 34.829000, 113.538930, CampusCategory.LAB, listOf("北门主入口", "东侧通道"), "实验实训中心大楼与专业实验室（341xx-345xx）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_eng", "35号楼（工程训练中心）", "工训中心", 34.831340, 113.537830, CampusCategory.LAB, listOf("东门主通道", "南门实训车间入口"), "4层金工实习、工业创客与机电综合工程训练中心（351xx-354xx）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t38", "38号楼（综合实验楼）", "38号楼", 34.830290, 113.537990, CampusCategory.LAB, listOf("东侧正门"), "4层基础实验与学科创新实验室（381xx-384xx）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_t40", "40号楼（储运与标准化中心）", "40号楼", 34.829660, 113.537710, CampusCategory.LAB, listOf("东侧正门", "模拟粮仓连廊"), "4层粮食储运中心、标准化中心与专业实验室", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_canteen1", "第一餐厅", "一餐厅", 34.833440, 113.548450, CampusCategory.CANTEEN, listOf("西门主入口", "南门入口"), "莲花街校区东北生活区综合学生餐厅（1-3层）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_canteen4", "第四餐厅", "四餐厅", 34.832260, 113.541300, CampusCategory.CANTEEN, listOf("南广场主入口", "西侧门"), "莲花街校区中西区综合学生餐厅，近工程训练中心与E/F公寓", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_canteen_small", "小食堂（风味餐厅）", "小食堂", 34.830570, 113.549520, CampusCategory.CANTEEN, listOf("西侧入口"), "东侧教学区旁特色风味就餐点，近3/4号教学楼", LocalDate.of(2026, 11, 9)),
        LegacyBuildingSeed("b_gate_south", "莲花街南门", "南门", 34.828700, 113.545310, CampusCategory.GATE, listOf("南门西闸机", "南门东闸机"), "莲花街校区正门，紧邻地铁1号线河南工业大学站", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_gate_west", "长椿路西门", "西门", 34.831910, 113.536650, CampusCategory.GATE, listOf("西门人行通道"), "长椿路校区西出入口，近E/F公寓区与工程训练中心", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_gate_east", "东门", "东门", 34.831010, 113.550960, CampusCategory.GATE, listOf("东门通道"), "校区东侧出入口，毗邻校医院与后勤楼", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_dorm_ab", "学生公寓A/B区", "A/B公寓", 34.834650, 113.545100, CampusCategory.DORMITORY, listOf("南侧园区门禁"), "北苑中部学生公寓A区（1-4座）与B区（1-4座）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_dorm_ju", "学生公寓C/D区", "C/D公寓", 34.834500, 113.548800, CampusCategory.DORMITORY, listOf("南侧门禁", "第一餐厅北步道"), "东北生活区学生公寓C区（1-4座）与D区（1-4座）", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_dorm_ef", "学生公寓E/F区", "E/F公寓", 34.833150, 113.537750, CampusCategory.DORMITORY, listOf("东侧门禁", "西门步道"), "西侧生活区学生公寓E区（1-4座）、F区高层公寓与e区美食源", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_express", "菜鸟驿站（快递中心）", "菜鸟驿站", 34.833390, 113.549860, CampusCategory.EXPRESS, listOf("西侧取件入口"), "第一餐厅东侧校园综合快递收发服务中心", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_hospital", "校医院与后勤楼", "校医院", 34.830610, 113.550700, CampusCategory.EXPRESS, listOf("西门门诊入口"), "5层校区医疗门诊、医保服务与后勤保障大楼", LocalDate.of(2026, 11, 5)),
        LegacyBuildingSeed("b_stadium", "体育训练中心与田径场", "体育中心", 34.832340, 113.539230, CampusCategory.SPORTS, listOf("东侧主入口", "北侧体育场看台"), "4层体育训练中心场馆、北侧标准田径运动场与球类场馆", LocalDate.of(2026, 11, 5))
    )

    val aliases: List<LegacyBuildingAliasSeed> = listOf(
        LegacyBuildingAliasSeed("b_t1", "1号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t1", "莲1号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t1", "莲01号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t1", "31号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t1", "莲31号", 1.0f, true),
        LegacyBuildingAliasSeed("b_t1", "^31[1-6]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t2", "32号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t2", "土建楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t2", "莲32号", 1.0f, true),
        LegacyBuildingAliasSeed("b_t2", "^32[1-6]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t2", "教学楼A", 0.90f, true),
        LegacyBuildingAliasSeed("b_t2", "A座", 0.72f, false),
        LegacyBuildingAliasSeed("b_dorm_ef", "学生宿舍F01", 0.74f, false),
        LegacyBuildingAliasSeed("b_dorm_ef", "宿舍F01", 0.72f, false),
        LegacyBuildingAliasSeed("b_dorm_ef", "F01", 0.70f, false),
        LegacyBuildingAliasSeed("b_t3", "3号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t3", "3号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t3", "莲3号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t3", "莲03号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t3", "文科组团楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t3", "文科楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t3", "^3[1-6]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t3", "教学楼B", 0.88f, true),
        LegacyBuildingAliasSeed("b_t4", "4号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t4", "4号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t4", "莲4号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t4", "莲04号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t4", "^4[1-5]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t5", "计算中心", 0.95f, true),
        LegacyBuildingAliasSeed("b_t5", "5号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t5", "5号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t5", "莲5号", 1.0f, true),
        LegacyBuildingAliasSeed("b_t5", "^5[1-5]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t6", "6号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t6", "6号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t6", "莲6号", 1.0f, true),
        LegacyBuildingAliasSeed("b_t6", "^6[1-5]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t7", "7号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t7", "7号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t7", "莲7号", 1.0f, true),
        LegacyBuildingAliasSeed("b_t7", "^7[1-5]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t8", "8号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t8", "8号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t8", "粮油食品楼", 0.98f, true),
        LegacyBuildingAliasSeed("b_t8", "^8[1-6]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t9", "9号教学楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t9", "9号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t9", "^9[1-6]\\d{2}$", 0.96f, true),
        LegacyBuildingAliasSeed("b_t18", "18号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t18", "艺术楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_bell", "钟楼", 0.98f, true),
        LegacyBuildingAliasSeed("b_bell", "24号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t25", "25号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t25", "学生事务中心", 1.0f, true),
        LegacyBuildingAliasSeed("b_t34", "34号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t34", "实验实训中心", 1.0f, true),
        LegacyBuildingAliasSeed("b_t34", "莲34号", 1.0f, true),
        LegacyBuildingAliasSeed("b_eng", "训练中心", 0.96f, true),
        LegacyBuildingAliasSeed("b_eng", "工程训练中心", 1.0f, true),
        LegacyBuildingAliasSeed("b_eng", "35号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_eng", "金工实习", 0.95f, true),
        LegacyBuildingAliasSeed("b_t38", "38号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t40", "40号楼", 1.0f, true),
        LegacyBuildingAliasSeed("b_t40", "储运中心", 0.96f, true),
        LegacyBuildingAliasSeed("b_stadium", "田径场", 0.95f, true),
        LegacyBuildingAliasSeed("b_stadium", "莲花操场", 1.0f, true),
        LegacyBuildingAliasSeed("b_stadium", "体育训练中心", 1.0f, true),
        LegacyBuildingAliasSeed("b_stadium", "体育馆", 0.95f, true),
        LegacyBuildingAliasSeed("b_canteen1", "第一餐厅", 1.0f, true),
        LegacyBuildingAliasSeed("b_canteen1", "一餐厅", 1.0f, true),
        LegacyBuildingAliasSeed("b_canteen1", "一餐", 0.96f, true),
        LegacyBuildingAliasSeed("b_canteen4", "第四餐厅", 1.0f, true),
        LegacyBuildingAliasSeed("b_canteen4", "四餐厅", 1.0f, true),
        LegacyBuildingAliasSeed("b_canteen4", "四餐", 0.96f, true),
        LegacyBuildingAliasSeed("b_canteen_small", "小食堂", 1.0f, true),
        LegacyBuildingAliasSeed("b_canteen_small", "风味餐厅", 0.96f, true)
    )
}
