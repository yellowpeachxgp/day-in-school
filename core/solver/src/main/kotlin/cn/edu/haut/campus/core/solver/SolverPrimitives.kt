package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.selection.ClassCapacity
import cn.edu.haut.campus.core.selection.ClassSlot
import cn.edu.haut.campus.core.selection.TeachingWeeks
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 求解器使用的最小组址信息。缺少校区或楼栋时保持未知，不得猜测可达。 */
data class SolverLocation(
    val campusId: CampusId? = null,
    val buildingId: String? = null,
    val room: String? = null
) {
    init {
        buildingId?.let { requireStableText(it, "楼栋 ID", 128) }
        room?.let { requireStableText(it, "房间", 128) }
    }

    val hasCampusAndBuilding: Boolean
        get() = campusId != null && buildingId != null
}

/** 一个教学时段及其结构化地点；时间语义复用 :core:selection 的半开节次与周次规则。 */
data class SolverClassSlot(
    val time: ClassSlot,
    val location: SolverLocation? = null
) {
    val weekday: Int get() = time.weekday
    val startSection: Int get() = time.startSection
    val endSection: Int get() = time.endSection
    val weeks: TeachingWeeks get() = time.weeks

    fun sharesSectionWith(other: SolverClassSlot): Boolean = time.sharesSectionWith(other.time)
}

/** 候选教学班变量值。学分为课程学分；同一 offering 的全部候选学分必须一致。 */
data class TeachingClassOption(
    val id: String,
    val offeringId: String,
    val courseName: String,
    val credits: BigDecimal,
    val slots: List<SolverClassSlot>,
    val capacity: ClassCapacity = ClassCapacity.Unknown,
    val provenance: DataProvenance? = null
) {
    init {
        requireStableText(id, "教学班 ID", 256)
        requireStableText(offeringId, "课程 offering ID", 256)
        require(courseName.isNotBlank() && courseName.none { it.isISOControl() }) { "课程名称不能为空或含控制字符" }
        require(credits.signum() >= 0) { "学分不能为负" }
        creditsToMilli(credits)
        require(slots.isNotEmpty()) { "教学班至少需要一个明确时段；时间未知不得当作可排班" }
        require(slots.map { it.time }.distinct().size == slots.size) { "教学班包含重复时段" }
    }
}

/** 已有课表、考试或个人日程投影到教学周网格后的占用。 */
data class FixedOccupancy(
    val id: String,
    val label: String,
    val slots: List<SolverClassSlot>,
    val provenance: DataProvenance? = null
) {
    init {
        requireStableText(id, "固定占用 ID", 256)
        require(label.isNotBlank() && label.none { it.isISOControl() }) { "固定占用名称不能为空或含控制字符" }
        require(slots.isNotEmpty()) { "固定占用至少需要一个时段" }
    }
}

/** 一节次的校内当地时间；[end] 为开区间，必须晚于 [start]。 */
data class PeriodTime(
    val section: Int,
    val start: LocalTime,
    val end: LocalTime
) {
    init {
        require(section in 1..ClassSlot.MAX_SECTION) { "节次超出范围: " + section }
        require(end.isAfter(start)) { "节次结束时间必须晚于开始时间" }
    }
}

/**
 * 教学周 × 星期 × 节次的显式时间网格。
 *
 * 网格不内置任何学校作息；调用方必须提供学期第一周周一、校区时区和每一节次时间。
 * 周次与节次使用从零开始的原子位索引，便于求解器用位集做时间重叠传播。
 */
data class TeachingTimeGrid(
    val weekCount: Int,
    val sectionsPerDay: Int,
    val firstWeekMonday: LocalDate,
    val zone: ZoneId,
    val periodTimes: Map<Int, PeriodTime>
) {
    init {
        require(weekCount in 1..TeachingWeeks.MAX_WEEK) { "教学周数量超出范围" }
        require(sectionsPerDay in 1..ClassSlot.MAX_SECTION) { "每日节次数量超出范围" }
        require(firstWeekMonday.dayOfWeek == DayOfWeek.MONDAY) { "firstWeekMonday 必须是周一" }
        require(periodTimes.keys == (1..sectionsPerDay).toSet()) { "节次时间必须连续覆盖 1..sectionsPerDay" }
        require(periodTimes.all { (section, period) -> period.section == section }) { "节次时间键与 section 不一致" }
        val ordered = periodTimes.values.sortedBy { it.section }
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            require(!current.start.isBefore(previous.end)) { "节次时间必须单调且不重叠" }
        }
    }

    val atomCount: Int get() = weekCount * 7 * sectionsPerDay

    fun atomIndex(week: Int, weekday: Int, section: Int): Int {
        require(week in 1..weekCount) { "教学周超出网格: " + week }
        require(weekday in 1..7) { "星期超出范围: " + weekday }
        require(section in 1..sectionsPerDay) { "节次超出网格: " + section }
        return ((week - 1) * 7 + (weekday - 1)) * sectionsPerDay + (section - 1)
    }

    fun windowFor(week: Int, weekday: Int, section: Int): cn.edu.haut.campus.core.timeline.TimeWindow {
        val period = periodTimes.getValue(section)
        val date = firstWeekMonday.plusDays(((week - 1) * 7 + (weekday - 1)).toLong())
        return cn.edu.haut.campus.core.timeline.TimeWindow(
            date.atTime(period.start).atZone(zone).toInstant(),
            date.atTime(period.end).atZone(zone).toInstant()
        )
    }

    fun period(section: Int): PeriodTime = periodTimes.getValue(section)
}

/** 用户级硬约束引用。解释与放松建议只暴露这一层，不暴露候选两两冲突内部 ID。 */
enum class ConstraintKind {
    REQUIRED_OFFERING,
    CREDIT_LOWER_BOUND,
    CREDIT_UPPER_BOUND,
    CAPACITY_POLICY,
    TIME_EXCLUSION,
    CAMPUS_RESTRICTION,
    MUTUAL_EXCLUSION,
    COMMUTE_POLICY
}

data class ConstraintRef(
    val id: String,
    val kind: ConstraintKind,
    val label: String
) {
    init {
        requireStableText(id, "约束 ID", 256)
        require(label.isNotBlank() && label.none { it.isISOControl() }) { "约束名称不能为空或含控制字符" }
    }

    companion object {
        fun requiredOffering(offeringId: String, courseName: String? = null): ConstraintRef = ConstraintRef(
            id = "required-offering:" + offeringId,
            kind = ConstraintKind.REQUIRED_OFFERING,
            label = courseName?.let { "必选课程《"+it+"》" } ?: "必选课程 " + offeringId
        )

        fun creditLowerBound(): ConstraintRef = ConstraintRef("credit-lower-bound", ConstraintKind.CREDIT_LOWER_BOUND, "总学分下限")

        fun creditUpperBound(): ConstraintRef = ConstraintRef("credit-upper-bound", ConstraintKind.CREDIT_UPPER_BOUND, "总学分上限")
    }
}

sealed interface HardConstraint {
    val ref: ConstraintRef

    /** 某 offering 必须选择一个教学班。 */
    data class RequireOffering(
        val offeringId: String,
        val courseName: String? = null,
        override val ref: ConstraintRef = ConstraintRef.requiredOffering(offeringId, courseName)
    ) : HardConstraint {
        init {
            requireStableText(offeringId, "课程 offering ID", 256)
        }
    }

    /** 容量过滤是用户可放松约束；未知容量不得在输出中被改写成有余量。 */
    data class CapacityPolicy(
        val excludeFull: Boolean = true,
        val allowUnknown: Boolean = false,
        override val ref: ConstraintRef = ConstraintRef("capacity-policy", ConstraintKind.CAPACITY_POLICY, "容量过滤")
    ) : HardConstraint

    /** 与任一给定时段重叠的候选被排除；调用方负责把“不选早八”等规则展开为明确时段。 */
    data class TimeExclusion(
        val slots: List<SolverClassSlot>,
        override val ref: ConstraintRef = ConstraintRef("time-exclusion", ConstraintKind.TIME_EXCLUSION, "时间排除")
    ) : HardConstraint {
        init {
            require(slots.isNotEmpty()) { "时间排除至少需要一个时段" }
        }
    }

    /** 只允许指定校区；地点未知时保守排除，并在可行方案或解释中保留不确定证据。 */
    data class CampusRestriction(
        val allowedCampusIds: Set<CampusId>,
        override val ref: ConstraintRef = ConstraintRef("campus-restriction", ConstraintKind.CAMPUS_RESTRICTION, "校区限制")
    ) : HardConstraint {
        init {
            require(allowedCampusIds.isNotEmpty()) { "校区限制不能为空" }
        }
    }

    /** 一组 offering 至多选择一个。 */
    data class MutualExclusion(
        val offeringIds: Set<String>,
        override val ref: ConstraintRef = ConstraintRef("mutual-exclusion", ConstraintKind.MUTUAL_EXCLUSION, "课程互斥")
    ) : HardConstraint {
        init {
            require(offeringIds.size >= 2) { "互斥至少包含两个 offering" }
            offeringIds.forEach { requireStableText(it, "课程 offering ID", 256) }
        }
    }

    /** 同一天相邻课程的通勤约束。未知通勤默认保守拒绝，可显式降级为警告。 */
    data class CommutePolicy(
        val bufferMinutes: Int = 0,
        val unknownTravel: UnknownTravelPolicy = UnknownTravelPolicy.REJECT,
        override val ref: ConstraintRef = ConstraintRef("commute-policy", ConstraintKind.COMMUTE_POLICY, "课间通勤约束")
    ) : HardConstraint {
        init {
            require(bufferMinutes in 0..240) { "通勤缓冲分钟数超出范围" }
        }
    }
}

enum class UnknownTravelPolicy {
    REJECT,
    ALLOW_WITH_WARNING
}

/** 非负整数毫分软成本。禁止负成本，避免用负权重诱导选择可选课程。 */
sealed interface WeightedPreference {
    val id: String
    val label: String

    data class EarlySection(
        val latestEarlySection: Int,
        val costPerOccurrenceMilli: Long,
        override val id: String = "early-section",
        override val label: String = "早课成本"
    ) : WeightedPreference {
        init {
            require(latestEarlySection in 1..ClassSlot.MAX_SECTION) { "早课节次阈值无效" }
            require(costPerOccurrenceMilli >= 0) { "软成本不能为负" }
        }
    }

    data class LateSection(
        val firstLateSection: Int,
        val costPerOccurrenceMilli: Long,
        override val id: String = "late-section",
        override val label: String = "晚课成本"
    ) : WeightedPreference {
        init {
            require(firstLateSection in 1..ClassSlot.MAX_SECTION) { "晚课节次阈值无效" }
            require(costPerOccurrenceMilli >= 0) { "软成本不能为负" }
        }
    }

    data class GapTime(
        val costPerHourMilli: Long,
        override val id: String = "gap-time",
        override val label: String = "空档时间成本"
    ) : WeightedPreference {
        init { require(costPerHourMilli >= 0) { "软成本不能为负" } }
    }

    data class TravelTime(
        val costPerMinuteMilli: Long,
        override val id: String = "travel-time",
        override val label: String = "通勤时间成本"
    ) : WeightedPreference {
        init { require(costPerMinuteMilli >= 0) { "软成本不能为负" } }
    }

    data class DailyLoad(
        val maxSectionsPerDay: Int,
        val costPerExtraSectionMilli: Long,
        override val id: String = "daily-load",
        override val label: String = "每日负担成本"
    ) : WeightedPreference {
        init {
            require(maxSectionsPerDay in 1..ClassSlot.MAX_SECTION) { "每日节次阈值无效" }
            require(costPerExtraSectionMilli >= 0) { "软成本不能为负" }
        }
    }

    data class UnknownCapacityRisk(
        val costPerClassMilli: Long,
        override val id: String = "unknown-capacity-risk",
        override val label: String = "容量未知风险成本"
    ) : WeightedPreference {
        init { require(costPerClassMilli >= 0) { "软成本不能为负" } }
    }

    data class UnknownWeeksRisk(
        val costPerClassMilli: Long,
        override val id: String = "unknown-weeks-risk",
        override val label: String = "周次未知风险成本"
    ) : WeightedPreference {
        init { require(costPerClassMilli >= 0) { "软成本不能为负" } }
    }

    data class CandidatePenalty(
        val candidateIds: Set<String>,
        val costPerOccurrenceMilli: Long,
        override val id: String = "candidate-penalty",
        override val label: String = "候选惩罚"
    ) : WeightedPreference {
        init {
            require(candidateIds.isNotEmpty()) { "候选惩罚不能为空" }
            candidateIds.forEach { requireStableText(it, "教学班 ID", 256) }
            require(costPerOccurrenceMilli >= 0) { "软成本不能为负" }
        }
    }
}

internal fun creditsToMilli(value: BigDecimal): Long {
    require(value.signum() >= 0) { "学分不能为负" }
    val scaled = value.movePointRight(3).stripTrailingZeros()
    require(scaled.scale() <= 0) { "学分最多支持三位小数" }
    return scaled.toBigIntegerExact().longValueExact()
}

internal fun milliToCredits(value: Long): BigDecimal = BigDecimal.valueOf(value, 3)

internal fun requireStableText(value: String, label: String, maxLength: Int) {
    require(value.length in 1..maxLength) { label + "为空或过长" }
    require(value.none { it.isISOControl() }) { label + "不能包含控制字符" }
}
