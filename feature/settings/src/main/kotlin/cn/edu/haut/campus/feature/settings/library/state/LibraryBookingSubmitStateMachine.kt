package cn.edu.haut.campus.feature.settings.library.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CloudReservationQuote
import cn.edu.haut.campus.core.database.CloudPlanPriceDriftException
import java.util.UUID

/**
 * 不可变预约草稿快照（R5-D3）：创建提交瞬间冻结的身份 + 计划参数。
 *
 * 任何 profileId/cloudUserId/identityGeneration/normalizedBase/studentNo/
 * engine/targetDate/targetTime/area/seat/repeatDays 变化都会令草稿修订号
 * [draftRevision] 单调递增，此前捕获的快照一律作废（晚到结果、待确认弹窗、
 * 旧确认按钮三处复核同一快照，绝不跨上下文复用）。
 */
data class BookingDraftSnapshot(
    val profileId: String,
    val cloudUserId: String,
    val identityGeneration: Long,
    val normalizedBase: String,
    val studentNo: String,
    val engine: BookingExecutionMode,
    val targetDate: String,
    val targetTime: String,
    val areaName: String,
    val seatLabel: String,
    val repeatDays: Int,
    val draftRevision: Long,
    val attemptId: String,
    /** 本次尝试钉入/用户确认的价格（微桃）；null 表示按引擎展示常量价 */
    val acceptedPriceMicropeach: Long?,
    /** 报价单钉价语义附带的 quote_id（仅服务端报价成功预取时存在） */
    val quoteId: String? = null,
    val quoteExpiresAtEpochMillis: Long? = null,
    val dateRevision: Long = 0L
) {
    /** 身份五元组：切号/重登世代推进/base 换址/换学号 任一变化即不同身份 */
    fun sameIdentityAs(other: BookingIdentityContext): Boolean =
        profileId == other.profileId &&
            cloudUserId == other.cloudUserId &&
            identityGeneration == other.identityGeneration &&
            normalizedBase == other.normalizedBase &&
            studentNo == other.studentNo

    /** 草稿参数：日期/时间/区域/座位/连续天数/引擎 任一变化即不同草稿 */
    fun sameParamsAs(other: BookingDraftParams): Boolean =
        engine == other.engine &&
            targetDate == other.targetDate &&
            targetTime == other.targetTime &&
            areaName == other.areaName &&
            seatLabel == other.seatLabel &&
            repeatDays == other.repeatDays && dateRevision == other.dateRevision
}

/** 当前会话身份上下文（云身份 + base + 学号） */
data class BookingIdentityContext(
    val profileId: String,
    val cloudUserId: String,
    val identityGeneration: Long,
    val normalizedBase: String,
    val studentNo: String
)

/** 当前草稿参数（屏幕可编辑输入的聚合） */
data class BookingDraftParams(
    val engine: BookingExecutionMode,
    val targetDate: String,
    val targetTime: String,
    val areaName: String,
    val seatLabel: String,
    val repeatDays: Int,
    /** 当前向用户展示的单价：首次创建钉入价即此值 */
    val displayedPriceMicropeach: Long?,
    val dateRevision: Long = 0L
)

/**
 * 云端调价 409 → PRICE_CHANGED 待确认记录（绝不只存 exception）：
 * 绑定完整不可变草稿快照 + 旧钉入价 + 云端新报价（含 quote_id/截止时间，可为 null）。
 */
data class PendingPriceReconfirmation(
    val snapshot: BookingDraftSnapshot,
    val oldPriceMicropeach: Long?,
    val proposedPriceMicropeach: Long?,
    val proposedQuote: CloudReservationQuote? = null
) {
    /** 需要用户确认的最终新价：报价 DTO 优先，其次服务端 detail 现价 */
    val effectiveProposedPriceMicropeach: Long?
        get() = proposedQuote?.amountMicropeach ?: proposedPriceMicropeach
}

/**
 * 预约提交流程状态机：
 * EDITING → SUBMITTING(snapshot) → PRICE_CHANGED(snapshot,quote)
 *         → USER_CONFIRMED(newAttempt) → SUBMITTING → ACCEPTED
 */
enum class BookingSubmitPhase {
    EDITING,
    SUBMITTING,
    PRICE_CHANGED,
    USER_CONFIRMED,
    ACCEPTED
}

/**
 * 预约提交草稿状态机（R5-D3）：Compose 可观察、纯 JVM 可测。
 *
 * - 任何身份/base/generation/学号/参数/引擎变化 → [updateContext] 递增
 *   [draftRevision] 并清空 pending；
 * - [beginSubmission] 捕获不可变 [BookingDraftSnapshot]，在途时返回 null
 *   （双击/旋屏至多一个有效业务尝试）；
 * - 异步结果（[onAttemptPriceDrift]/[onAttemptSettled]）、渲染弹窗
 *   （[visiblePendingReconfirmation]）、确认按钮（[confirmPendingReconfirmation]）
 *   三处复核同一快照；
 * - 取消（[cancelPendingReconfirmation]）只清 pending，绝不重发；
 * - 确认绑定该不可变 draft：[confirmPendingReconfirmation] 返回携带新
 *   attemptId + 确认价的新快照，随后 [beginConfirmedAttempt] 进入 SUBMITTING。
 */
class LibraryBookingSubmitStateMachine(
    private val attemptIdFactory: () -> String = {
        "att_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
) {
    var phase by mutableStateOf(BookingSubmitPhase.EDITING)
        private set

    var pendingReconfirmation by mutableStateOf<PendingPriceReconfirmation?>(null)
        private set

    var draftRevision by mutableLongStateOf(0L)
        private set

    /** 当前在途业务尝试（至多一个） */
    var inFlightAttempt by mutableStateOf<BookingDraftSnapshot?>(null)
        private set

    private var currentIdentity: BookingIdentityContext? = null
    private var currentParams: BookingDraftParams? = null

    private fun BookingDraftSnapshot.isCurrentDraft(): Boolean {
        val i = currentIdentity ?: return false
        val p = currentParams ?: return false
        return sameIdentityAs(i) && sameParamsAs(p) && draftRevision == this@LibraryBookingSubmitStateMachine.draftRevision
    }

    /**
     * 上下文推进：任何身份/参数变化即修订号 +1 并清空 pending——
     * 由接线层在编辑输入变化、ON_RESUME、每次提交/回执/确认前调用。
     */
    fun updateContext(identity: BookingIdentityContext, params: BookingDraftParams) {
        if (identity != currentIdentity || params != currentParams) {
            currentIdentity = identity
            currentParams = params
            draftRevision++
            pendingReconfirmation = null
            if (phase != BookingSubmitPhase.SUBMITTING) {
                phase = BookingSubmitPhase.EDITING
            }
        }
    }

    /**
     * 捕获当前不可变草稿快照并进入 SUBMITTING。
     * 在途尝试存在或存在当前待确认弹窗时返回 null（拒绝叠加第二笔业务尝试）。
     */
    fun beginSubmission(): BookingDraftSnapshot? {
        if (inFlightAttempt != null) return null
        if (pendingReconfirmation?.takeIf { it.snapshot.isCurrentDraft() } != null) return null
        val i = currentIdentity ?: return null
        val p = currentParams ?: return null
        val snap = BookingDraftSnapshot(
            profileId = i.profileId,
            cloudUserId = i.cloudUserId,
            identityGeneration = i.identityGeneration,
            normalizedBase = i.normalizedBase,
            studentNo = i.studentNo,
            engine = p.engine,
            targetDate = p.targetDate,
            targetTime = p.targetTime,
            areaName = p.areaName,
            seatLabel = p.seatLabel,
            repeatDays = p.repeatDays,
            draftRevision = draftRevision,
            attemptId = attemptIdFactory(),
            acceptedPriceMicropeach = p.displayedPriceMicropeach,
            dateRevision = p.dateRevision
        )
        inFlightAttempt = snap
        phase = BookingSubmitPhase.SUBMITTING
        return snap
    }

    /**
     * 异步结果①·报价漂移：仅当回执快照仍是当前草稿才落 pending；
     * 晚到/过期结果被吸收，绝不污染新上下文。
     */
    fun onAttemptPriceDrift(
        attempt: BookingDraftSnapshot,
        oldPriceMicropeach: Long?,
        proposedPriceMicropeach: Long?,
        proposedQuote: CloudReservationQuote?
    ): Boolean {
        if (attempt != inFlightAttempt) return false
        inFlightAttempt = null
        if (!attempt.isCurrentDraft()) {
            phase = if (pendingReconfirmation != null) {
                BookingSubmitPhase.PRICE_CHANGED
            } else {
                BookingSubmitPhase.EDITING
            }
            return false
        }
        phase = BookingSubmitPhase.PRICE_CHANGED
        pendingReconfirmation = PendingPriceReconfirmation(
            snapshot = attempt,
            oldPriceMicropeach = oldPriceMicropeach,
            proposedPriceMicropeach = proposedPriceMicropeach,
            proposedQuote = proposedQuote
        )
        return true
    }

    /**
     * 异步结果②·尝试完结：返回 false 表示该结果针对的是已失效上下文
     * （晚到结果），已被吸收；true 表示结果属于当前草稿。
     */
    fun onAttemptSettled(attempt: BookingDraftSnapshot, succeeded: Boolean): Boolean {
        if (attempt != inFlightAttempt) return false
        inFlightAttempt = null
        val stillCurrent = attempt.isCurrentDraft()
        phase = when {
            succeeded && stillCurrent -> BookingSubmitPhase.ACCEPTED
            pendingReconfirmation != null -> BookingSubmitPhase.PRICE_CHANGED
            else -> BookingSubmitPhase.EDITING
        }
        return stillCurrent
    }

    /** 渲染复核：pending 仍属当前草稿才可见（上下文变化后绝不渲染旧确认框） */
    fun visiblePendingReconfirmation(): PendingPriceReconfirmation? =
        pendingReconfirmation?.takeIf { it.snapshot.isCurrentDraft() }

    /**
     * 确认按钮复核：pending 仍属当前草稿且无在途尝试 → 返回绑定该
     * 不可变 draft 的新尝试快照（全新 attemptId + 用户确认价 + 原报价引用），
     * 状态转 USER_CONFIRMED；否则返回 null（旧确认不可提交）。
     */
    fun confirmPendingReconfirmation(): BookingDraftSnapshot? {
        val pending = pendingReconfirmation
            ?.takeIf { it.snapshot.isCurrentDraft() }
            ?: return null
        if (inFlightAttempt != null) return null
        val proposed = pending.effectiveProposedPriceMicropeach ?: return null
        pendingReconfirmation = null
        val confirmedAttempt = pending.snapshot.copy(
            attemptId = attemptIdFactory(),
            acceptedPriceMicropeach = proposed,
            quoteId = pending.proposedQuote?.quoteId,
            quoteExpiresAtEpochMillis = pending.proposedQuote?.expiresAtEpochMillis
        )
        phase = BookingSubmitPhase.USER_CONFIRMED
        return confirmedAttempt
    }

    /** USER_CONFIRMED → SUBMITTING：确认的新尝试正式进入在途（仍至多一个） */
    fun beginConfirmedAttempt(attempt: BookingDraftSnapshot): Boolean {
        if (phase != BookingSubmitPhase.USER_CONFIRMED || inFlightAttempt != null) return false
        inFlightAttempt = attempt
        phase = BookingSubmitPhase.SUBMITTING
        return true
    }

    /** 取消：仅关闭待确认记录，绝不自动重发 */
    fun cancelPendingReconfirmation() {
        pendingReconfirmation = null
        if (phase == BookingSubmitPhase.PRICE_CHANGED) {
            phase = BookingSubmitPhase.EDITING
        }
    }
}

/** 提交事件：供 UI 接线层映射为 toast/弹窗/忽略 */
sealed interface BookingSubmitEvent {
    /** 已有在途尝试，双击/旋屏重入被吸收 */
    data object Busy : BookingSubmitEvent

    /** 上下文已变：待确认快照不再匹配当前草稿，旧确认被拒绝 */
    data object StaleConfirmationRefused : BookingSubmitEvent

    /** 异步回执对应的是已失效草稿：结果已被吸收，不污染新上下文 */
    data object StaleResultIgnored : BookingSubmitEvent

    data class Accepted(val snapshot: BookingDraftSnapshot) : BookingSubmitEvent

    data class PriceReconfirmationRequired(val pending: PendingPriceReconfirmation) : BookingSubmitEvent

    data class Failed(val message: String, val cause: Throwable?) : BookingSubmitEvent
}

/**
 * 预约提交接线层（R5-D3，纯 JVM 可测）：把屏幕输入与仓库调用缝进状态机。
 *
 * - [identityProvider]：每次复核时取最新身份快照（profileId/cloudUserId/
 *   generation/base/studentNo），绝不复用旧值；
 * - [paramsProvider]：每次复核时取最新草稿参数；
 * - [submitPlan]：把不可变快照映射为仓库创建调用（attemptId 由快照绑定）。
 */
class LibraryBookingPlanSubmitter(
    val machine: LibraryBookingSubmitStateMachine,
    private val identityProvider: suspend () -> BookingIdentityContext,
    private val paramsProvider: () -> BookingDraftParams,
    private val submitPlan: suspend (BookingDraftSnapshot) -> Result<Unit>,
    private val prepareSubmission: () -> Boolean = { true },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    /** 用最新身份 + 参数复核上下文（编辑变化/ON_RESUME/提交前后均调用） */
    suspend fun refreshContext() {
        machine.updateContext(identityProvider(), paramsProvider())
    }

    /** 首次发起业务尝试：复核最新上下文后捕获不可变快照并提交 */
    suspend fun submitNewAttempt(): BookingSubmitEvent {
        if (!prepareSubmission()) {
            refreshContext()
            return BookingSubmitEvent.StaleConfirmationRefused
        }
        val requestedParams = paramsProvider()
        refreshContext()
        if (requestedParams != paramsProvider()) return BookingSubmitEvent.StaleConfirmationRefused
        // 身份读取可能挂起并跨午夜，捕获快照前再校验页面查询日期。
        if (!prepareSubmission()) {
            refreshContext()
            return BookingSubmitEvent.StaleConfirmationRefused
        }
        val snap = machine.beginSubmission() ?: return BookingSubmitEvent.Busy
        return runAttempt(snap)
    }

    private suspend fun runAttempt(snap: BookingDraftSnapshot): BookingSubmitEvent {
        val result = runCatching { submitPlan(snap) }.getOrElse { Result.failure(it) }
        return result.fold(
            onSuccess = {
                // 创建成功是既成事实（计划确已建立），无论上下文是否已变化都如实反馈；
                // onAttemptSettled 的返回值仅用于状态机内部相位，绝不吞掉成功回执
                machine.onAttemptSettled(snap, succeeded = true)
                BookingSubmitEvent.Accepted(snap)
            },
            onFailure = { err ->
                val drift = err as? CloudPlanPriceDriftException
                if (drift != null) {
                    // 接收异步结果复核：先刷新上下文再尝试落 pending——
                    // 切号/改参期间晚到的漂移绝不安装到新上下文
                    refreshContext()
                    val applied = machine.onAttemptPriceDrift(
                        attempt = snap,
                        oldPriceMicropeach = drift.expectedPriceMicropeach,
                        proposedPriceMicropeach = drift.currentPriceMicropeach,
                        proposedQuote = drift.proposedQuote
                    )
                    if (applied) {
                        BookingSubmitEvent.PriceReconfirmationRequired(
                            machine.pendingReconfirmation!!
                        )
                    } else {
                        BookingSubmitEvent.StaleResultIgnored
                    }
                } else {
                    if (machine.onAttemptSettled(snap, succeeded = false)) {
                        BookingSubmitEvent.Failed(err.message ?: "创建失败，请重试", err)
                    } else {
                        BookingSubmitEvent.StaleResultIgnored
                    }
                }
            }
        )
    }

    /**
     * 用户确认新价：确认按钮复核（最新上下文必须仍匹配 pending 的不可变快照），
     * 随后 USER_CONFIRMED → SUBMITTING 发起绑定该 draft 的新尝试。
     */
    suspend fun confirmPendingReconfirmation(): BookingSubmitEvent {
        if (!prepareSubmission()) {
            refreshContext()
            machine.cancelPendingReconfirmation()
            return BookingSubmitEvent.StaleConfirmationRefused
        }
        refreshContext()
        if (!prepareSubmission()) {
            refreshContext()
            machine.cancelPendingReconfirmation()
            return BookingSubmitEvent.StaleConfirmationRefused
        }
        val expiresAt = machine.visiblePendingReconfirmation()?.proposedQuote?.expiresAtEpochMillis
        if (expiresAt != null && expiresAt <= nowEpochMillis()) {
            machine.cancelPendingReconfirmation()
            return BookingSubmitEvent.StaleConfirmationRefused
        }
        val confirmedAttempt = machine.confirmPendingReconfirmation()
            ?: return BookingSubmitEvent.StaleConfirmationRefused
        if (!machine.beginConfirmedAttempt(confirmedAttempt)) {
            return BookingSubmitEvent.Busy
        }
        return runAttempt(confirmedAttempt)
    }

    /** 取消弹窗：关闭待确认记录，绝不自动重发 */
    fun cancelPendingReconfirmation() {
        machine.cancelPendingReconfirmation()
    }
}
