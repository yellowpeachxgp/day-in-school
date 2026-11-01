package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.json.Digests
import java.time.Instant

/**
 * 一次选课/退课请求。只包含展示与幂等所需字段，不含学号、密码、Cookie 或任何教务响应正文。
 */
data class SelectionRequest(
    val offeringId: String,
    val courseName: String,
    val teachingClassId: String,
    val action: SelectionAction = SelectionAction.ENROLL
) {

    init {
        require(offeringId.isNotBlank() && courseName.isNotBlank() && teachingClassId.isNotBlank()) {
            "选课请求标识不完整"
        }
    }
}

/**
 * 用户确认过的不可变快照：学校、账号、课程、教学班、动作全部固定在这里。
 *
 * 用户在界面上确认的就是这份快照；提交前用 [digest] 二次校验，
 * 防止界面停留在旧数据时把另一门课提交出去（TOCTOU）。
 */
data class ConfirmationSnapshot(
    val scope: SelectionScope,
    val roundId: String,
    val offeringId: String,
    val courseName: String,
    val teachingClassId: String,
    val action: SelectionAction
) {

    init {
        require(roundId.isNotBlank()) { "确认快照必须绑定轮次" }
        require(offeringId.isNotBlank() && courseName.isNotBlank() && teachingClassId.isNotBlank()) {
            "确认快照标识不完整"
        }
    }

    /** 内容摘要，只用于比对用户确认的内容，不包含任何秘密。 */
    val digest: String
        get() = Digests.sha256Hex(
            DIGEST_VERSION,
            scope.schoolId.value,
            scope.accountId.value,
            scope.termId,
            roundId,
            offeringId,
            teachingClassId,
            action.name
        )

    fun expectedSelection(): ExpectedSelection = ExpectedSelection(offeringId, teachingClassId, action)

    /** 确认页文案；刻意不含学号/账号标识，避免身份信息出现在截图、日志或分享里。 */
    fun summaryText(): String = action.label + "：" + courseName + " / 教学班 " + teachingClassId

    private companion object {
        const val DIGEST_VERSION = "selection-snapshot-v1"
    }
}

/** 幂等键。同一笔写入重复提交必须复用同一个键，服务端据此去重。 */
@JvmInline
value class IdempotencyKey(val value: String) {

    init {
        require(value.length in 16..128) { "幂等键长度非法" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "幂等键格式非法" }
    }
}

/** 校方受理回执。[serverReference] 只允许放可公开的受理号，不得放 Cookie 或响应正文。 */
data class SubmissionReceipt(
    val key: IdempotencyKey,
    val snapshotDigest: String,
    val submittedAt: Instant,
    val serverReference: String? = null
)

/** 写入端口收到的命令；[key] 必须原样带到服务端作为幂等键。 */
data class SubmissionCommand(
    val key: IdempotencyKey,
    val snapshot: ConfirmationSnapshot,
    val submittedAt: Instant
)

/** 一次写入的三种结局。超时/连接中断一律归为 [ResultUnknown]，绝不归为失败。 */
sealed interface WriteOutcome {

    data class Accepted(val receipt: SubmissionReceipt) : WriteOutcome

    /** 校方明确拒绝：可以确定写入没有生效，因此允许重新走确认流程再提交。 */
    data class Rejected(val reason: String, val retryable: Boolean = false) : WriteOutcome

    /** 结果未知：网络超时、连接中断或响应无法判读，只能回读官方结果，禁止自动重发。 */
    data class ResultUnknown(val cause: AcademicError, val detail: String? = null) : WriteOutcome
}

/**
 * 唯一的写边界。实现方必须：
 * 1) 把 [SubmissionCommand.key] 原样作为服务端幂等键；
 * 2) 超时/中断/响应不可判读时返回 [WriteOutcome.ResultUnknown]，绝不自行重试；
 * 3) 异常信息里不得出现 Cookie、密码或完整响应正文。
 */
fun interface SelectionWritePort {
    fun submit(command: SubmissionCommand): WriteOutcome
}

/** 提交状态机。名字与蓝图一致：DRAFT → CONFIRM_REQUIRED → SUBMITTING → SUBMITTED_UNVERIFIED → VERIFIED / UNKNOWN / REJECTED。 */
sealed interface SubmissionState {

    val stateLabel: String

    /** 已经用户确认过的快照；草稿阶段没有。 */
    val snapshot: ConfirmationSnapshot?

    data object Draft : SubmissionState {
        override val stateLabel: String get() = "草稿"
        override val snapshot: ConfirmationSnapshot? get() = null
    }

    data class ConfirmRequired(override val snapshot: ConfirmationSnapshot) : SubmissionState {
        override val stateLabel: String get() = "待用户确认"
    }

    data class Submitting(
        override val snapshot: ConfirmationSnapshot,
        val key: IdempotencyKey,
        val approvedAt: Instant
    ) : SubmissionState {
        override val stateLabel: String get() = "提交中"
    }

    data class SubmittedUnverified(
        override val snapshot: ConfirmationSnapshot,
        val receipt: SubmissionReceipt,
        val receivedAt: Instant,
        val readback: ReadbackComparison? = null
    ) : SubmissionState {
        override val stateLabel: String get() = "已提交待核对"
    }

    data class Verified(
        override val snapshot: ConfirmationSnapshot,
        val comparison: ReadbackComparison,
        val receipt: SubmissionReceipt?,
        val verifiedAt: Instant
    ) : SubmissionState {
        override val stateLabel: String get() = "官方回读一致"
    }

    data class Unknown(
        override val snapshot: ConfirmationSnapshot,
        val key: IdempotencyKey,
        val cause: AcademicError,
        val detail: String? = null,
        val readback: ReadbackComparison? = null
    ) : SubmissionState {
        override val stateLabel: String get() = "结果未知"
    }

    data class Rejected(
        override val snapshot: ConfirmationSnapshot,
        val reason: String,
        val retryable: Boolean = false
    ) : SubmissionState {
        override val stateLabel: String get() = "校方拒绝"
    }
}

/** 状态转移被阻止的原因，便于界面给出不同处理方式而不是笼统报错。 */
enum class SelectionBlockReason {
    /** 当前状态不允许该操作。 */
    INVALID_STATE,

    /** 轮次未声明可写或已不在开放窗口内。 */
    ROUND_NOT_WRITABLE,

    /** 用户确认的快照与当前快照不一致（界面停留旧数据）。 */
    CONFIRMATION_MISMATCH,

    /** 同一幂等键已经提交过。 */
    DUPLICATE_IDEMPOTENCY_KEY,

    /** 上次写入结果未知，只能核对官方结果。 */
    RESULT_UNKNOWN_REQUIRES_READBACK,

    /** 官方回读属于另一个学校/账号/学期。 */
    SCOPE_MISMATCH
}

/** 状态转移结果：要么产生新状态，要么明确说明被阻止的原因。 */
sealed interface SelectionTransition {

    data class Applied(val submission: SelectionSubmission) : SelectionTransition

    data class Blocked(val reason: SelectionBlockReason, val detail: String) : SelectionTransition

    val submissionOrNull: SelectionSubmission? get() = (this as? Applied)?.submission
}

/**
 * 受控提交流程。所有写操作必须经过：
 * 生成确认快照 → 用户确认（校验摘要）→ 生成幂等键 → 写入 → 回读核对。
 *
 * 该类型本身不触网，只描述状态与守卫；真正发请求的是 [SelectionSubmissionCoordinator] + [SelectionWritePort]。
 */
data class SelectionSubmission(
    val scope: SelectionScope,
    val round: SelectionRound,
    val request: SelectionRequest,
    val state: SubmissionState
) {

    init {
        require(scope.termId == round.termId) { "选课轮次与账号学期不一致" }
    }

    val stateLabel: String get() = state.stateLabel

    val snapshot: ConfirmationSnapshot? get() = state.snapshot

    /** 修改选课意图：回到草稿，旧确认快照立即失效。提交发出后不允许再改。 */
    fun revise(request: SelectionRequest): SelectionTransition = when (state) {
        SubmissionState.Draft, is SubmissionState.ConfirmRequired ->
            if (request == this.request) {
                SelectionTransition.Applied(this)
            } else {
                SelectionTransition.Applied(copy(request = request, state = SubmissionState.Draft))
            }
        else -> SelectionTransition.Blocked(
            SelectionBlockReason.INVALID_STATE,
            "写入已经发出，不能再修改选课请求；当前状态：" + state.stateLabel
        )
    }

    /** 生成（或刷新）确认快照；轮次不可写时直接阻止，避免把用户的点击变成一次注定失败的写。 */
    fun confirm(now: Instant): SelectionTransition = when (val current = state) {
        SubmissionState.Draft, is SubmissionState.ConfirmRequired -> {
            if (!round.declaredWritable) {
                SelectionTransition.Blocked(SelectionBlockReason.ROUND_NOT_WRITABLE, "当前轮次未声明支持写入，已禁止提交")
            } else if (!round.acceptsWritesAt(now)) {
                SelectionTransition.Blocked(
                    SelectionBlockReason.ROUND_NOT_WRITABLE,
                    "轮次" + round.phaseAt(now).label + "，暂时不能提交选课"
                )
            } else {
                val snapshot = ConfirmationSnapshot(
                    scope = scope,
                    roundId = round.id,
                    offeringId = request.offeringId,
                    courseName = request.courseName,
                    teachingClassId = request.teachingClassId,
                    action = request.action
                )
                if (current is SubmissionState.ConfirmRequired && current.snapshot == snapshot) {
                    SelectionTransition.Applied(this)
                } else {
                    SelectionTransition.Applied(copy(state = SubmissionState.ConfirmRequired(snapshot)))
                }
            }
        }
        else -> SelectionTransition.Blocked(
            SelectionBlockReason.INVALID_STATE,
            "只有草稿状态才能生成确认快照；当前状态：" + state.stateLabel
        )
    }

    /**
     * 用户确认。[confirmedDigest] 必须等于界面上展示过的快照摘要；
     * 同时再检查一次轮次窗口，防止确认页停留过久后已经闭轮。
     */
    fun approve(confirmedDigest: String, now: Instant): SelectionTransition {
        val current = state as? SubmissionState.ConfirmRequired
            ?: return SelectionTransition.Blocked(
                SelectionBlockReason.INVALID_STATE,
                "只有待确认状态才能批准提交；当前状态：" + state.stateLabel
            )
        if (confirmedDigest != current.snapshot.digest) {
            return SelectionTransition.Blocked(
                SelectionBlockReason.CONFIRMATION_MISMATCH,
                "用户确认的快照与当前快照不一致，请重新确认后再提交"
            )
        }
        if (!round.acceptsWritesAt(now)) {
            return SelectionTransition.Blocked(
                SelectionBlockReason.ROUND_NOT_WRITABLE,
                "轮次在确认后已不可写，已取消本次提交"
            )
        }
        return SelectionTransition.Applied(
            copy(
                state = SubmissionState.Submitting(
                    snapshot = current.snapshot,
                    key = SelectionIdempotencyKey.derive(current.snapshot),
                    approvedAt = now
                )
            )
        )
    }

    /** 记录写入结果。回执必须与本次提交的幂等键、快照摘要一致，否则拒绝采纳。 */
    fun recordOutcome(outcome: WriteOutcome, now: Instant): SelectionTransition {
        val current = state as? SubmissionState.Submitting
            ?: return SelectionTransition.Blocked(
                SelectionBlockReason.INVALID_STATE,
                "只有提交中状态才能记录写入结果；当前状态：" + state.stateLabel
            )
        return when (outcome) {
            is WriteOutcome.Accepted -> {
                if (outcome.receipt.key != current.key) {
                    SelectionTransition.Blocked(SelectionBlockReason.INVALID_STATE, "回执的幂等键与本次提交不一致，拒绝采纳")
                } else if (outcome.receipt.snapshotDigest != current.snapshot.digest) {
                    SelectionTransition.Blocked(SelectionBlockReason.INVALID_STATE, "回执的快照摘要与本次提交不一致，拒绝采纳")
                } else {
                    SelectionTransition.Applied(
                        copy(
                            state = SubmissionState.SubmittedUnverified(
                                snapshot = current.snapshot,
                                receipt = outcome.receipt,
                                receivedAt = now
                            )
                        )
                    )
                }
            }
            is WriteOutcome.Rejected -> SelectionTransition.Applied(
                copy(state = SubmissionState.Rejected(current.snapshot, outcome.reason, outcome.retryable))
            )
            is WriteOutcome.ResultUnknown -> SelectionTransition.Applied(
                copy(
                    state = SubmissionState.Unknown(
                        snapshot = current.snapshot,
                        key = current.key,
                        cause = outcome.cause,
                        detail = outcome.detail
                    )
                )
            )
        }
    }

    /**
     * 用官方回读核对结果。这是 UNKNOWN 之后唯一允许的动作；
     * 回读与预期一致才进入 VERIFIED，不一致则保持原状态并记录差异，绝不重发。
     */
    fun verify(readback: AcceptedSelectionSnapshot, now: Instant): SelectionTransition {
        val current = state
        if (current !is SubmissionState.SubmittedUnverified && current !is SubmissionState.Unknown) {
            return SelectionTransition.Blocked(
                SelectionBlockReason.INVALID_STATE,
                "只有已提交待核对或结果未知的状态才能核对官方回读；当前状态：" + state.stateLabel
            )
        }
        if (readback.scope != scope) {
            return SelectionTransition.Blocked(
                SelectionBlockReason.SCOPE_MISMATCH,
                "官方回读不属于当前学校/账号/学期，拒绝据此判定结果"
            )
        }
        val snapshot = current.snapshot
            ?: return SelectionTransition.Blocked(SelectionBlockReason.INVALID_STATE, "当前状态缺少确认快照，无法核对")
        val comparison = SelectionReadback.compare(snapshot.expectedSelection(), readback)
        return if (comparison.matched) {
            SelectionTransition.Applied(
                copy(
                    state = SubmissionState.Verified(
                        snapshot = snapshot,
                        comparison = comparison,
                        receipt = (current as? SubmissionState.SubmittedUnverified)?.receipt,
                        verifiedAt = now
                    )
                )
            )
        } else {
            val updated = when (current) {
                is SubmissionState.SubmittedUnverified -> current.copy(readback = comparison)
                is SubmissionState.Unknown -> current.copy(readback = comparison)
                else -> current
            }
            SelectionTransition.Applied(copy(state = updated))
        }
    }

    companion object {

        fun begin(scope: SelectionScope, round: SelectionRound, request: SelectionRequest): SelectionSubmission =
            SelectionSubmission(scope, round, request, SubmissionState.Draft)
    }
}
