package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.json.Digests
import java.time.Instant

/**
 * 幂等键推导：同一作用域下同一笔选课/退课永远得到同一个键。
 *
 * 这样即使界面被重复点击、进程重启或用户重新走一遍确认流程，服务端也只会受理一次。
 * 轮次 ID 进入摘要：换轮次等于换一次写入，不会与上一轮冲突。
 */
object SelectionIdempotencyKey {

    private const val VERSION = "selection-key-v1"

    fun derive(snapshot: ConfirmationSnapshot): IdempotencyKey = IdempotencyKey(
        "sel-" + Digests.sha256Hex(
            VERSION,
            snapshot.scope.schoolId.value,
            snapshot.scope.accountId.value,
            snapshot.scope.termId,
            snapshot.roundId,
            snapshot.offeringId,
            snapshot.teachingClassId,
            snapshot.action.name
        )
    )
}

/**
 * 幂等键账本：记录所有已经发出过的写入，同一键在进程内只允许使用一次。
 *
 * 只有校方明确拒绝（可以确定写入没有生效）时才允许释放该键；
 * 超时与结果未知永远不释放，否则用户可能把同一笔选课提交两次。
 *
 * 已知限制：账本只在内存中，进程重启后需要由持久化实现恢复，否则重启会丢掉去重证据。
 */
class SelectionIdempotencyLedger {

    data class Record(val key: IdempotencyKey, val snapshotDigest: String, val reservedAt: Instant)

    private val records = linkedMapOf<String, Record>()

    /** 预占幂等键；返回 false 表示该键已经被使用过，本次必须拒绝提交。 */
    fun reserve(key: IdempotencyKey, snapshotDigest: String, now: Instant): Boolean {
        if (records.containsKey(key.value)) return false
        records[key.value] = Record(key, snapshotDigest, now)
        return true
    }

    /** 仅在校方明确拒绝、可以确定写入未生效时释放。 */
    fun releaseAfterDefiniteRejection(key: IdempotencyKey): Boolean = records.remove(key.value) != null

    fun record(key: IdempotencyKey): Record? = records[key.value]

    fun size(): Int = records.size
}

/**
 * 受控提交协调器：把状态机、幂等账本和写端口串起来。
 *
 * 行为约束：
 * 1) 只有 SUBMITTING 状态才会真正调用 [SelectionWritePort]；
 * 2) 同一幂等键第二次提交在触网之前就被拒绝；
 * 3) 结果未知后只能 [verify] 核对官方回读，再次 [submit] 会被直接阻止。
 */
class SelectionSubmissionCoordinator(
    private val port: SelectionWritePort,
    private val ledger: SelectionIdempotencyLedger = SelectionIdempotencyLedger(),
    private val clock: () -> Instant = { Instant.now() }
) {

    fun submit(submission: SelectionSubmission): SelectionTransition {
        val current = submission.state
        if (current !is SubmissionState.Submitting) {
            return if (current is SubmissionState.Unknown) {
                SelectionTransition.Blocked(
                    SelectionBlockReason.RESULT_UNKNOWN_REQUIRES_READBACK,
                    "上次写入结果未知，只能核对官方结果，禁止重发"
                )
            } else {
                SelectionTransition.Blocked(
                    SelectionBlockReason.INVALID_STATE,
                    "只有提交中状态才能发出写请求；当前状态：" + current.stateLabel
                )
            }
        }
        val now = clock()
        if (!ledger.reserve(current.key, current.snapshot.digest, now)) {
            return SelectionTransition.Blocked(
                SelectionBlockReason.DUPLICATE_IDEMPOTENCY_KEY,
                "该幂等键已经使用过，拒绝重复提交同一笔写入"
            )
        }
        val outcome = try {
            port.submit(SubmissionCommand(current.key, current.snapshot, now))
        } catch (error: Exception) {
            // 写端口抛异常等价于无法判断结果：按未知处理，且不释放幂等键。
            WriteOutcome.ResultUnknown(AcademicError.WRITE_RESULT_UNKNOWN, "写请求异常中断（" + error::class.java.simpleName + "）")
        }
        val transition = submission.recordOutcome(outcome, now)
        if (outcome is WriteOutcome.Rejected) {
            ledger.releaseAfterDefiniteRejection(current.key)
        }
        return transition
    }

    /** 核对官方回读；这条路径不会触达写端口。 */
    fun verify(submission: SelectionSubmission, readback: AcceptedSelectionSnapshot): SelectionTransition =
        submission.verify(readback, clock())
}
