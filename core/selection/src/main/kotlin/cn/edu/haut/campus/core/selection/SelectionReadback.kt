package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.SnapshotCompleteness

/** 官方回读里的一个已选教学班。 */
data class AcceptedClass(
    val offeringId: String,
    val teachingClassId: String,
    val courseName: String
) {

    init {
        require(offeringId.isNotBlank() && teachingClassId.isNotBlank() && courseName.isNotBlank()) {
            "回读记录标识不完整"
        }
    }
}

/**
 * 官方“已选课程”回读快照。
 *
 * UNVERIFIED：字段按公开协议格式推导，不代表任何真实学校的回读结果；
 * 只有 [isConclusive] 为 true 时，"没看到某门课" 才能当作写入未生效的证据。
 */
data class AcceptedSelectionSnapshot(
    val scope: SelectionScope,
    val classes: List<AcceptedClass>,
    val completeness: SnapshotCompleteness = SnapshotCompleteness.UNKNOWN
) {

    init {
        require(classes.map { it.offeringId }.distinct().size == classes.size) {
            "同一门课程不能同时存在多个已选教学班"
        }
    }

    /** 只有完整快照或权威空快照才能支撑“一致/不一致”的结论。 */
    val isConclusive: Boolean
        get() = completeness == SnapshotCompleteness.COMPLETE || completeness == SnapshotCompleteness.EMPTY_CONFIRMED

    fun findClass(offeringId: String): AcceptedClass? = classes.firstOrNull { it.offeringId == offeringId }
}

/** 本地预期：一次选课/退课动作希望看到的结果。 */
data class ExpectedSelection(
    val offeringId: String,
    val teachingClassId: String,
    val action: SelectionAction
) {

    init {
        require(offeringId.isNotBlank() && teachingClassId.isNotBlank()) { "本地预期标识不完整" }
    }
}

enum class ReadbackDifferenceKind(val label: String) {
    /** 选课后期望出现，但官方回读没有该课程。 */
    MISSING_IN_OFFICIAL("官方未出现该课程"),

    /** 退课后官方回读仍然存在该课程。 */
    STILL_ENROLLED("官方仍显示已选"),

    /** 官方出现的是同一门课程的另一教学班。 */
    CLASS_MISMATCH("教学班与预期不一致")
}

data class ReadbackDifference(
    val kind: ReadbackDifferenceKind,
    val offeringId: String,
    val expectedTeachingClassId: String?,
    val actualTeachingClassId: String?,
    val message: String
)

/**
 * 单条预期的回读比对结果。
 *
 * [conclusive] 为 false 表示官方回读不完整：既不能判定成功，也不能判定失败，
 * 只能提示用户稍后重新核对。
 */
data class ReadbackComparison(
    val expected: ExpectedSelection,
    val actual: AcceptedClass?,
    val differences: List<ReadbackDifference>,
    val conclusive: Boolean,
    val note: String?
) {

    /** 只有“结论可靠且没有任何差异”才算核对一致。 */
    val matched: Boolean get() = conclusive && differences.isEmpty()
}

/**
 * 官方回读比对。
 *
 * 规则：
 * 1) 只有完整/权威空回读才给出确定结论；回读不完整时，缺失不能当作失败证据。
 * 2) 退课只要官方仍显示该课程就是差异，与教学班无关。
 * 3) 不把账号里的其它课程算作差异——它们与本次写入无关，误报会让用户反复核对。
 */
object SelectionReadback {

    fun compare(expected: ExpectedSelection, snapshot: AcceptedSelectionSnapshot): ReadbackComparison {
        val actual = snapshot.findClass(expected.offeringId)
        if (!snapshot.isConclusive) {
            val hardDifference = when {
                expected.action == SelectionAction.ENROLL &&
                    actual != null &&
                    actual.teachingClassId != expected.teachingClassId ->
                    difference(ReadbackDifferenceKind.CLASS_MISMATCH, expected, actual)
                else -> null
            }
            return ReadbackComparison(
                expected = expected,
                actual = actual,
                differences = listOfNotNull(hardDifference),
                conclusive = false,
                note = "官方回读不完整（" + snapshot.completeness.name + "），无法据此确认写入结果"
            )
        }
        return when (expected.action) {
            SelectionAction.ENROLL -> when {
                actual == null -> ReadbackComparison(
                    expected = expected,
                    actual = null,
                    differences = listOf(
                        ReadbackDifference(
                            kind = ReadbackDifferenceKind.MISSING_IN_OFFICIAL,
                            offeringId = expected.offeringId,
                            expectedTeachingClassId = expected.teachingClassId,
                            actualTeachingClassId = null,
                            message = "官方已选列表未出现该课程，本次选课没有生效"
                        )
                    ),
                    conclusive = true,
                    note = null
                )
                actual.teachingClassId == expected.teachingClassId -> ReadbackComparison(
                    expected = expected,
                    actual = actual,
                    differences = emptyList(),
                    conclusive = true,
                    note = null
                )
                else -> ReadbackComparison(
                    expected = expected,
                    actual = actual,
                    differences = listOf(difference(ReadbackDifferenceKind.CLASS_MISMATCH, expected, actual)),
                    conclusive = true,
                    note = null
                )
            }
            SelectionAction.DROP -> if (actual == null) {
                ReadbackComparison(
                    expected = expected,
                    actual = null,
                    differences = emptyList(),
                    conclusive = true,
                    note = null
                )
            } else {
                ReadbackComparison(
                    expected = expected,
                    actual = actual,
                    differences = listOf(
                        ReadbackDifference(
                            kind = ReadbackDifferenceKind.STILL_ENROLLED,
                            offeringId = expected.offeringId,
                            expectedTeachingClassId = expected.teachingClassId,
                            actualTeachingClassId = actual.teachingClassId,
                            message = "官方已选列表仍包含该课程，本次退课没有生效"
                        )
                    ),
                    conclusive = true,
                    note = null
                )
            }
        }
    }

    /** 批量比对，按输入顺序返回，便于界面一次性展示多条预期。 */
    fun compare(expected: Collection<ExpectedSelection>, snapshot: AcceptedSelectionSnapshot): List<ReadbackComparison> =
        expected.map { compare(it, snapshot) }

    private fun difference(
        kind: ReadbackDifferenceKind,
        expected: ExpectedSelection,
        actual: AcceptedClass
    ): ReadbackDifference = ReadbackDifference(
        kind = kind,
        offeringId = expected.offeringId,
        expectedTeachingClassId = expected.teachingClassId,
        actualTeachingClassId = actual.teachingClassId,
        message = "官方回读的教学班为 " + actual.teachingClassId + "，与预期 " + expected.teachingClassId + " 不一致"
    )
}
