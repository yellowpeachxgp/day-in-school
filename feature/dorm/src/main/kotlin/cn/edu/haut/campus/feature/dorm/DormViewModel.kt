package cn.edu.haut.campus.feature.dorm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import cn.edu.haut.campus.feature.dorm.state.DormTab
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.DormUiState
import cn.edu.haut.campus.feature.dorm.state.DutyRotaDraft
import cn.edu.haut.campus.feature.dorm.state.LedgerEntryDraft
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 宿舍协作状态 holder；只通过 DormCollaborationPort/DormUtilityStatusPort 读取当前 Profile 授权数据。 */
class DormViewModel(
    private val port: DormCollaborationPort,
    private val utilityStatusPort: DormUtilityStatusPort,
    private val schoolId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val currentCloudUserId: suspend () -> String? = { null },
) : ViewModel() {
    private val mutableState = MutableStateFlow(DormUiState())
    val uiState: StateFlow<DormUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            utilityStatusPort.reminders().collect { reminders ->
                mutableState.update { it.copy(utilityReminders = reminders) }
            }
        }
        refresh()
    }

    fun dispatch(action: DormUiAction) {
        when (action) {
            DormUiAction.Refresh -> refresh()
            DormUiAction.Back -> Unit // Route 消费返回；ViewModel 不持有导航。
            is DormUiAction.SelectTab -> mutableState.update { it.copy(selectedTab = action.tab) }
            is DormUiAction.SelectGroup -> refresh(preferredGroupId = action.groupId)
            is DormUiAction.UpdateNewGroupName -> mutableState.update { it.copy(newGroupName = action.value.take(80)) }
            DormUiAction.CreateDormGroup -> createDormGroup()
            is DormUiAction.UpdateLedgerDraft -> mutableState.update { it.copy(ledgerDraft = action.draft) }
            DormUiAction.SubmitLedgerEntry -> submitLedgerEntry()
            is DormUiAction.ReverseLedgerEntry -> reverseLedgerEntry(action.entryId)
            DormUiAction.ProposeSettlement -> proposeSettlement()
            is DormUiAction.ConfirmSettlement -> confirmSettlement(action.settlementId)
            is DormUiAction.CancelSettlement -> cancelSettlement(action.settlementId)
            is DormUiAction.UpdateDutyRotaDraft -> mutableState.update { it.copy(dutyRotaDraft = action.draft) }
            DormUiAction.CreateDutyRota -> createDutyRota()
            is DormUiAction.CompleteDutyTask -> completeDutyTask(action.taskId)
            is DormUiAction.ConfirmDutyTask -> confirmDutyTask(action.taskId)
            is DormUiAction.SkipDutyTask -> skipDutyTask(action.taskId)
            is DormUiAction.RequestDutySwap -> requestDutySwap(action.taskId)
            is DormUiAction.ResolveDutySwap -> resolveDutySwap(action.swapId, action.action)
            is DormUiAction.UpdateRepairDraft -> mutableState.update { it.copy(repairDraft = action.draft) }
            DormUiAction.SubmitRepairTicket -> submitRepairTicket()
            is DormUiAction.TransitionRepair -> transitionRepair(action.repairId, action.toStatus)
            is DormUiAction.ReviewRepair -> reviewRepair(action.repairId)
            DormUiAction.OpenElectricity -> Unit // Route 消费导航。
            DormUiAction.ConsumeNotice -> mutableState.update { it.copy(notice = null) }
        }
    }

    private fun refresh(preferredGroupId: String? = null, notice: String? = null) {
        mutableState.update {
            it.copy(
                loading = true,
                status = DormScreenStatus.LOADING,
                statusDetail = null,
                statusRetryable = false,
                notice = notice ?: it.notice,
            )
        }
        viewModelScope.launch {
            try {
                val snapshot = port.loadSnapshot(
                    schoolId = schoolId,
                    preferredGroupId = preferredGroupId ?: mutableState.value.snapshot?.selectedGroupId,
                )
                val status = when {
                    snapshot.groups.isEmpty() -> DormScreenStatus.EMPTY
                    snapshot.partialWarnings.isNotEmpty() -> DormScreenStatus.PARTIAL
                    snapshot.staleness == Staleness.STALE -> DormScreenStatus.STALE
                    else -> DormScreenStatus.CONTENT
                }
                mutableState.update {
                    it.copy(
                        loading = false,
                        status = status,
                        statusDetail = when (status) {
                            DormScreenStatus.EMPTY -> "当前账号还没有宿舍群组。创建后邀请室友加入即可开始协作。"
                            DormScreenStatus.PARTIAL -> snapshot.partialWarnings.joinToString("；")
                            DormScreenStatus.STALE -> "群组元数据来自本地缓存且可能陈旧；账本、值日和报修不会在离线时伪装成新数据。"
                            else -> null
                        },
                        snapshot = snapshot,
                        currentCloudUserId = currentCloudUserId(),
                        notice = notice,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                applyError(error)
            }
        }
    }

    private fun applyError(error: Throwable) {
        val dormError = error as? DormCollaborationException ?: DormCollaborationException(
            DormErrorKind.SERVER,
            error.message ?: "宿舍协作加载失败",
            retryable = true,
            cause = error,
        )
        val status = when (dormError.kind) {
            DormErrorKind.AUTHENTICATION_REQUIRED -> DormScreenStatus.NEEDS_LOGIN
            DormErrorKind.FEATURE_DISABLED, DormErrorKind.UNSUPPORTED -> DormScreenStatus.UNSUPPORTED
            DormErrorKind.NETWORK -> DormScreenStatus.OFFLINE
            DormErrorKind.SERVER -> if (dormError.retryable) DormScreenStatus.ERROR_RETRYABLE else DormScreenStatus.ERROR_NON_RETRYABLE
            DormErrorKind.CONTRACT, DormErrorKind.VALIDATION -> DormScreenStatus.ERROR_NON_RETRYABLE
        }
        mutableState.update {
            it.copy(
                loading = false,
                status = status,
                statusDetail = dormError.message + (dormError.userAction?.let { action -> "；" + action } ?: ""),
                statusRetryable = dormError.retryable,
            )
        }
    }

    private fun createDormGroup() {
        val name = mutableState.value.newGroupName.trim()
        if (name.isBlank()) {
            mutableState.update { it.copy(notice = "请先填写宿舍群组名称") }
            return
        }
        runMutation("已创建宿舍群组") {
            port.createDormGroup(
                schoolId = schoolId,
                name = name,
                description = null,
                idempotencyKey = newKey("dorm-group"),
            )
        }
    }

    private fun submitLedgerEntry() {
        val state = mutableState.value
        val snapshot = state.snapshot ?: return
        val groupId = snapshot.selectedGroupId ?: return
        val input = try {
            buildLedgerInput(state.ledgerDraft, snapshot.members)
        } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(notice = error.message) }
            return
        }
        runMutation("已追加账本记录；历史不会被改写") {
            port.createLedgerEntry(groupId, input, newKey("ledger-entry"))
        }
    }

    private fun reverseLedgerEntry(entryId: String) {
        val snapshot = mutableState.value.snapshot ?: return
        val groupId = snapshot.selectedGroupId ?: return
        val original = snapshot.ledgerEntries.firstOrNull { it.entryId == entryId } ?: return
        val input = DormLedgerEntryInput(
            title = original.title,
            amountFen = -original.amountFen,
            paidByMemberId = original.paidByMemberId,
            occurredAt = Instant.now(clock).toString(),
            splitRule = original.splitRule,
            entryKind = DormLedgerEntryKind.EXPENSE,
            note = "全额冲正 " + original.entryId,
        )
        runMutation("已追加全额冲正；原记录仍可追溯") {
            port.amendLedgerEntry(
                groupId = groupId,
                entryId = original.entryId,
                reason = "用户确认全额冲正：" + original.title,
                input = input,
                idempotencyKey = newKey("ledger-amend"),
            )
        }
    }

    private fun proposeSettlement() {
        val snapshot = mutableState.value.snapshot ?: return
        val groupId = snapshot.selectedGroupId ?: return
        val balances = snapshot.balances ?: return
        val debtor = balances.members.filter { it.balanceFen < 0L }.minByOrNull { it.balanceFen }
        val creditor = balances.members.filter { it.balanceFen > 0L }.maxByOrNull { it.balanceFen }
        if (debtor == null || creditor == null) {
            mutableState.update { it.copy(notice = "当前余额无需结算") }
            return
        }
        val amount = minOf(-debtor.balanceFen, creditor.balanceFen)
        runMutation("已创建两方确认结算记录；这不执行任何支付") {
            port.proposeSettlement(
                groupId = groupId,
                input = DormSettlementInput(
                    debtorMemberId = debtor.memberId,
                    creditorMemberId = creditor.memberId,
                    amountFen = amount,
                    basisBalanceRevision = balances.balanceRevision,
                    note = "由宿舍协作骨架按当前余额生成；确认只记账，不付款",
                ),
                idempotencyKey = newKey("settlement"),
            )
        }
    }

    private fun confirmSettlement(settlementId: String) {
        val settlement = mutableState.value.snapshot?.settlements?.firstOrNull { it.settlementId == settlementId } ?: return
        runMutation("已记录本方确认；CONFIRMED 仍不代表支付完成") {
            port.confirmSettlement(settlementId, settlement.basisBalanceRevision, newKey("settlement-confirm"))
        }
    }

    private fun cancelSettlement(settlementId: String) {
        runMutation("已取消结算账记录") {
            port.cancelSettlement(settlementId, "用户在宿舍协作骨架中取消", newKey("settlement-cancel"))
        }
    }

    private fun createDutyRota() {
        val state = mutableState.value
        val snapshot = state.snapshot ?: return
        val groupId = snapshot.selectedGroupId ?: return
        val activeMembers = snapshot.members.filter { it.isActive }.map { it.memberId }
        if (activeMembers.size < 2) {
            mutableState.update { it.copy(notice = "至少需要 2 名 active 成员才能创建值日轮班") }
            return
        }
        val today = LocalDate.now(clock)
        runMutation("已创建值日轮班并生成近期任务") {
            port.createDutyRota(
                groupId = groupId,
                input = DormDutyRotaInput(
                    title = state.dutyRotaDraft.title.ifBlank { "宿舍值日" },
                    rotationMemberIds = activeMembers,
                    frequency = if (state.dutyRotaDraft.weekly) "WEEKLY" else "DAILY",
                    startDate = today.toString(),
                    timeZone = clock.zone.id,
                    daysOfWeek = if (state.dutyRotaDraft.weekly) listOf(today.dayOfWeek.value) else emptyList(),
                ),
                idempotencyKey = newKey("duty-rota"),
            )
        }
    }

    private fun completeDutyTask(taskId: String) = runMutation("已标记值日完成，等待确认") {
        port.completeDutyTask(taskId, "骨架 UI 确认完成", newKey("duty-complete"))
    }

    private fun confirmDutyTask(taskId: String) = runMutation("已确认值日完成") {
        port.confirmDutyTask(taskId, "骨架 UI 复核确认", newKey("duty-confirm"))
    }

    private fun skipDutyTask(taskId: String) = runMutation("已记录请假/跳过原因") {
        port.skipDutyTask(taskId, "请假且暂无 accepted 代值（骨架 UI 记录）", newKey("duty-skip"))
    }

    private fun requestDutySwap(taskId: String) {
        val snapshot = mutableState.value.snapshot ?: return
        val task = snapshot.dutyTasks.firstOrNull { it.taskId == taskId } ?: return
        val target = snapshot.members.filter { it.isActive && it.memberId != task.assignedMemberId }.firstOrNull() ?: return
        runMutation("已发起换班/代值请求；对方接受前不会改派") {
            port.requestDutySwap(
                rotaId = task.rotaId,
                taskId = task.taskId,
                type = "COVER",
                targetMemberId = target.memberId,
                reason = "骨架 UI 发起单次代值请求",
                idempotencyKey = newKey("duty-swap"),
            )
        }
    }

    private fun resolveDutySwap(swapId: String, action: String) = runMutation("已处理换班请求") {
        port.resolveDutySwap(swapId, action, "骨架 UI 处理", newKey("duty-swap-resolve"))
    }

    private fun submitRepairTicket() {
        val state = mutableState.value
        val groupId = state.snapshot?.selectedGroupId ?: return
        val draft = state.repairDraft
        if (draft.title.isBlank() || draft.locationText.isBlank() || draft.description.isBlank()) {
            mutableState.update { it.copy(notice = "报修标题、位置和描述都不能为空") }
            return
        }
        runMutation("已创建宿舍内部报修协调单") {
            port.createRepairTicket(
                groupId = groupId,
                input = DormRepairInput(
                    title = draft.title.trim(),
                    category = draft.category,
                    locationText = draft.locationText.trim(),
                    description = draft.description.trim(),
                ),
                idempotencyKey = newKey("repair"),
            )
        }
    }

    private fun transitionRepair(repairId: String, toStatus: String) = runMutation("已更新报修协调状态（不代表学校/物业系统状态）") {
        port.transitionRepairStatus(repairId, toStatus, "骨架 UI 状态流转", null, newKey("repair-transition"))
    }

    private fun reviewRepair(repairId: String) = runMutation("已提交报修协调评价") {
        port.submitRepairReview(repairId, 5, "骨架 UI 默认满意评价", newKey("repair-review"))
    }

    private fun buildLedgerInput(draft: LedgerEntryDraft, members: List<DormMember>): DormLedgerEntryInput {
        val activeMembers = members.filter { it.isActive }
        require(activeMembers.isNotEmpty()) { "当前宿舍群组没有 active 成员" }
        val amountFen = parseYuanToFen(draft.amountYuan)
        val payer = draft.paidByMemberId ?: activeMembers.first().memberId
        require(activeMembers.any { it.memberId == payer }) { "垫付人必须是 active 成员" }
        val rule = when (draft.splitType) {
            DormSplitRuleType.EQUAL -> DormSplitRule(DormSplitRuleType.EQUAL)
            DormSplitRuleType.SELECTED -> {
                val selected = draft.selectedParticipantIds.filter { id -> activeMembers.any { it.memberId == id } }
                require(selected.isNotEmpty()) { "请至少选择一名分摊成员" }
                DormSplitRule(DormSplitRuleType.SELECTED, participantMemberIds = selected.sorted())
            }
            DormSplitRuleType.RATIOS -> {
                val weights = activeMembers.mapNotNull { member ->
                    val raw = draft.ratioWeightTexts[member.memberId]?.trim().orEmpty()
                    if (raw.isEmpty() || raw == "0") null else member.memberId to (raw.toLongOrNull() ?: throw IllegalArgumentException("比例权重必须是非负整数"))
                }.toMap()
                require(weights.values.any { it > 0L }) { "比例分摊至少需要一个正权重" }
                DormSplitRule(DormSplitRuleType.RATIOS, ratioWeights = weights)
            }
            DormSplitRuleType.STAY_DAYS -> {
                val days = activeMembers.mapNotNull { member ->
                    val raw = draft.stayDayTexts[member.memberId]?.trim().orEmpty()
                    if (raw.isEmpty() || raw == "0") null else member.memberId to (raw.toLongOrNull() ?: throw IllegalArgumentException("入住天数必须是非负整数"))
                }.toMap()
                require(days.values.any { it > 0L }) { "入住天数分摊至少需要一个正天数" }
                DormSplitRule(DormSplitRuleType.STAY_DAYS, stayDays = days)
            }
        }
        require(draft.title.isNotBlank()) { "请填写账本标题" }
        return DormLedgerEntryInput(
            title = draft.title.trim(),
            amountFen = amountFen,
            paidByMemberId = payer,
            occurredAt = Instant.now(clock).toString(),
            splitRule = rule,
            entryKind = if (amountFen < 0L) DormLedgerEntryKind.REFUND else DormLedgerEntryKind.EXPENSE,
            note = draft.note.trim().takeIf { it.isNotEmpty() },
        )
    }

    private fun runMutation(successNotice: String, block: suspend () -> Unit) {
        if (mutableState.value.actionInFlight) return
        mutableState.update { it.copy(actionInFlight = true, notice = null) }
        viewModelScope.launch {
            try {
                block()
                mutableState.update { it.copy(actionInFlight = false) }
                refresh(notice = successNotice)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(actionInFlight = false) }
                applyError(error)
            }
        }
    }

    private fun newKey(prefix: String): String = prefix + "-" + UUID.randomUUID().toString()

    companion object {
        fun factory(
            port: DormCollaborationPort,
            utilityStatusPort: DormUtilityStatusPort,
            schoolId: String,
            clock: Clock = Clock.systemUTC(),
            currentCloudUserId: suspend () -> String? = { null },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(DormViewModel::class.java))
                return DormViewModel(port, utilityStatusPort, schoolId, clock, currentCloudUserId) as T
            }
        }
    }
}
