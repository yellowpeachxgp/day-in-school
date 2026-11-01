package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant

/**
 * 合成夹具（UNVERIFIED）。
 *
 * 学校 ID、账号 ID、课程、教学班、周次与轮次时间全部是虚构的，按公开协议格式推导，
 * 不代表任何真实学校的选课数据；这里只用于验证本模块的判定与守卫逻辑。
 */
internal object SelectionFixtures {

    val SCHOOL: SchoolId = SchoolId("demo-school")
    val ACCOUNT: AccountId = AccountId("account-a")
    val OTHER_ACCOUNT: AccountId = AccountId("account-b")
    const val TERM: String = "2026-1"
    const val OTHER_TERM: String = "2026-2"
    val NOW: Instant = Instant.parse("2026-09-03T08:00:00Z")

    val OPEN_ROUND: SelectionRound = SelectionRound(
        id = "round-1",
        termId = TERM,
        label = "第一轮选课（合成）",
        opensAt = Instant.parse("2026-09-01T00:00:00Z"),
        closesAt = Instant.parse("2026-09-07T00:00:00Z"),
        declaredWritable = true
    )

    val CLOSED_ROUND: SelectionRound = OPEN_ROUND.copy(
        label = "已闭轮（合成）",
        closesAt = Instant.parse("2026-09-02T00:00:00Z")
    )

    val READ_ONLY_ROUND: SelectionRound = OPEN_ROUND.copy(id = "round-readonly", declaredWritable = false)

    fun scope(account: AccountId = ACCOUNT, termId: String = TERM): SelectionScope =
        SelectionScope(SCHOOL, account, termId)

    fun request(
        classId: String = "class-1",
        action: SelectionAction = SelectionAction.ENROLL
    ): SelectionRequest = SelectionRequest(
        offeringId = "off-1",
        courseName = "高等数学（合成）",
        teachingClassId = classId,
        action = action
    )

    fun acceptedOutcome(
        submitting: SubmissionState.Submitting,
        at: Instant = NOW
    ): WriteOutcome.Accepted = WriteOutcome.Accepted(
        SubmissionReceipt(
            key = submitting.key,
            snapshotDigest = submitting.snapshot.digest,
            submittedAt = at,
            serverReference = "receipt-demo"
        )
    )
}

/** 记录调用的写端口；[outcome] 可随时替换，用于模拟接受、拒绝与未知三种结局。 */
internal class RecordingWritePort(var outcome: WriteOutcome) : SelectionWritePort {

    val commands: MutableList<SubmissionCommand> = mutableListOf()

    override fun submit(command: SubmissionCommand): WriteOutcome {
        commands += command
        return outcome
    }
}

/** 只保留 JDK 的合成回读快照构造器。 */
internal fun readbackOf(
    scope: SelectionScope,
    vararg classes: AcceptedClass,
    completeness: cn.edu.haut.campus.core.model.SnapshotCompleteness =
        cn.edu.haut.campus.core.model.SnapshotCompleteness.COMPLETE
): AcceptedSelectionSnapshot = AcceptedSelectionSnapshot(scope, classes.toList(), completeness)
