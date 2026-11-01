package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.SchoolId
import java.math.BigDecimal
import java.time.Instant

/** 选课动作。 */
enum class SelectionAction(val label: String) {
    ENROLL("选课"),
    DROP("退课")
}

/**
 * 本地作用域：学校 + 账号 + 学期。
 *
 * 草稿、幂等键与官方回读都必须绑定同一个作用域，否则不同账号或学期会互相污染。
 * 这里只保存本地不透明账号 ID，不保存学号。
 */
data class SelectionScope(
    val schoolId: SchoolId,
    val accountId: AccountId,
    val termId: String
) {
    init {
        require(termId.isNotBlank()) { "学期 ID 不能为空" }
    }
}

/** 轮次阶段；时间窗口不完整时返回 [WINDOW_UNKNOWN]，不猜测用户此刻能否写入。 */
enum class RoundPhase(val label: String) {
    NOT_STARTED("尚未开始"),
    OPEN("开放中"),
    CLOSED("已结束"),
    WINDOW_UNKNOWN("窗口未知")
}

/**
 * 选课轮次。
 *
 * UNVERIFIED：轮次时间来自学校配置或合成夹具，本模块不内置任何真实学校的轮次规则。
 * [declaredWritable] 默认 false —— 只有学校配置明确声明可写时才允许发起写入，
 * 缺少写接口证据时功能保持禁用。
 */
data class SelectionRound(
    val id: String,
    val termId: String,
    val label: String,
    val opensAt: Instant? = null,
    val closesAt: Instant? = null,
    val declaredWritable: Boolean = false
) {

    init {
        require(id.isNotBlank() && label.isNotBlank()) { "轮次 ID 与名称不能为空" }
        require(termId.isNotBlank()) { "轮次必须绑定学期" }
        require(opensAt == null || closesAt == null || !closesAt.isBefore(opensAt)) { "轮次结束时间早于开始时间" }
    }

    val hasKnownWindow: Boolean get() = opensAt != null && closesAt != null

    /** 半开窗口 [opensAt, closesAt)：到达结束时刻即视为已关闭。 */
    fun phaseAt(now: Instant): RoundPhase {
        val open = opensAt
        val close = closesAt
        if (open == null || close == null) return RoundPhase.WINDOW_UNKNOWN
        return when {
            now.isBefore(open) -> RoundPhase.NOT_STARTED
            !now.isBefore(close) -> RoundPhase.CLOSED
            else -> RoundPhase.OPEN
        }
    }

    /** 是否允许发起写入：必须由学校配置声明可写，且当前处于开放窗口内。 */
    fun acceptsWritesAt(now: Instant): Boolean = declaredWritable && phaseAt(now) == RoundPhase.OPEN
}

/** 容量判定；未知就是未知，不允许用“约满/有位”掩盖缺失数据。 */
enum class CapacityVerdict { FULL, AVAILABLE, UNKNOWN }

/** 教学班容量。真实教务可能只给 total 不给 selected，也可能两者缺一，因此用三态而不是可空数字。 */
sealed interface ClassCapacity {

    data class Known(val total: Int, val selected: Int) : ClassCapacity {
        init {
            require(total >= 0 && selected >= 0) { "容量与已选人数不能为负数" }
        }

        val remaining: Int get() = (total - selected).coerceAtLeast(0)

        val isFull: Boolean get() = selected >= total

        /** 实际已选超过计划容量：真实系统存在，界面必须如实显示而不是回退成剩余 0。 */
        val isOverbooked: Boolean get() = selected > total
    }

    data object Unknown : ClassCapacity
}

/**
 * 教学班（某个课程门下的一个可选班级）。
 *
 * UNVERIFIED：教师、时间地点与容量字段按公开协议格式推导，不代表任何真实学校的实际教学班。
 */
data class TeachingClass(
    val id: String,
    val offeringId: String,
    val name: String,
    val capacity: ClassCapacity = ClassCapacity.Unknown,
    val teachers: List<String> = emptyList(),
    val slots: List<ClassSlot> = emptyList()
) {

    init {
        require(id.isNotBlank() && name.isNotBlank()) { "教学班 ID 与名称不能为空" }
        require(offeringId.isNotBlank()) { "教学班必须归属某个课程" }
    }

    val capacityVerdict: CapacityVerdict
        get() = when (val value = capacity) {
            ClassCapacity.Unknown -> CapacityVerdict.UNKNOWN
            is ClassCapacity.Known -> if (value.isFull) CapacityVerdict.FULL else CapacityVerdict.AVAILABLE
        }

    /**
     * 容量文案。未知时只说“容量未知”，绝不显示“约满/有位/剩余”，
     * 否则用户会据此做出错误的选课决策。
     */
    val capacityText: String
        get() = when (val value = capacity) {
            ClassCapacity.Unknown -> "容量未知"
            is ClassCapacity.Known -> if (value.isFull) {
                "已满（已选 ${value.selected} / 共 ${value.total}）"
            } else {
                "剩余 ${value.remaining} 个名额（已选 ${value.selected} / 共 ${value.total}）"
            }
        }
}

/** 一门课程及其全部教学班。 */
data class CourseOffering(
    val id: String,
    val name: String,
    val termId: String,
    val roundId: String,
    val courseCode: String? = null,
    val credits: BigDecimal? = null,
    val classes: List<TeachingClass> = emptyList()
) {

    init {
        require(id.isNotBlank() && name.isNotBlank()) { "课程 ID 与名称不能为空" }
        require(termId.isNotBlank() && roundId.isNotBlank()) { "课程必须绑定学期与轮次" }
        require(classes.map { it.id }.distinct().size == classes.size) { "同一课程包含重复教学班 ID" }
        require(classes.all { it.offeringId == id }) { "教学班的课程归属与所在课程不一致" }
    }

    fun classById(classId: String): TeachingClass? = classes.firstOrNull { it.id == classId }

    /** 可用于选择的教学班：已知满员的不再推荐，容量未知的仍保留（不能因为缺数据就当成满员）。 */
    fun selectableClasses(): List<TeachingClass> =
        classes.filter { it.capacityVerdict != CapacityVerdict.FULL }
}
