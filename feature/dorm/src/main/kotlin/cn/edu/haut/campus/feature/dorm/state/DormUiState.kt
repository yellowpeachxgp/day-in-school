package cn.edu.haut.campus.feature.dorm.state

import cn.edu.haut.campus.feature.dorm.DormSnapshot
import cn.edu.haut.campus.feature.dorm.DormSplitRuleType
import cn.edu.haut.campus.feature.dorm.DormUtilityReminder

/** MASTER_PLAN §10.2 的 11 态矩阵；不得用本地自定义类型替代共享 provenance/Staleness。 */
enum class DormScreenStatus {
    LOADING,
    EMPTY,
    CONTENT,
    PARTIAL,
    STALE,
    ERROR_RETRYABLE,
    ERROR_NON_RETRYABLE,
    NEEDS_PERMISSION,
    NEEDS_LOGIN,
    OFFLINE,
    UNSUPPORTED,
}

enum class DormTab(val label: String) {
    LEDGER("账本"),
    DUTY("值日"),
    REPAIRS("报修"),
}

data class LedgerEntryDraft(
    val title: String = "",
    val amountYuan: String = "",
    val paidByMemberId: String? = null,
    val note: String = "",
    val splitType: DormSplitRuleType = DormSplitRuleType.EQUAL,
    val selectedParticipantIds: Set<String> = emptySet(),
    val ratioWeightTexts: Map<String, String> = emptyMap(),
    val stayDayTexts: Map<String, String> = emptyMap(),
)

data class DutyRotaDraft(
    val title: String = "宿舍值日",
    val weekly: Boolean = true,
    val leaveReason: String = "请假且暂无 accepted 代值",
)

data class RepairDraft(
    val title: String = "",
    val category: String = "OTHER",
    val locationText: String = "",
    val description: String = "",
)

data class DormUiState(
    val loading: Boolean = true,
    val status: DormScreenStatus = DormScreenStatus.LOADING,
    val statusDetail: String? = null,
    val statusRetryable: Boolean = false,
    val snapshot: DormSnapshot? = null,
    val selectedTab: DormTab = DormTab.LEDGER,
    val utilityReminders: List<DormUtilityReminder> = emptyList(),
    val currentCloudUserId: String? = null,
    val actionInFlight: Boolean = false,
    val notice: String? = null,
    val newGroupName: String = "",
    val ledgerDraft: LedgerEntryDraft = LedgerEntryDraft(),
    val dutyRotaDraft: DutyRotaDraft = DutyRotaDraft(),
    val repairDraft: RepairDraft = RepairDraft(),
)

sealed interface DormUiAction {
    data object Refresh : DormUiAction
    data object Back : DormUiAction
    data class SelectTab(val tab: DormTab) : DormUiAction
    data class SelectGroup(val groupId: String) : DormUiAction
    data class UpdateNewGroupName(val value: String) : DormUiAction
    data object CreateDormGroup : DormUiAction

    data class UpdateLedgerDraft(val draft: LedgerEntryDraft) : DormUiAction
    data object SubmitLedgerEntry : DormUiAction
    data class ReverseLedgerEntry(val entryId: String) : DormUiAction
    data object ProposeSettlement : DormUiAction
    data class ConfirmSettlement(val settlementId: String) : DormUiAction
    data class CancelSettlement(val settlementId: String) : DormUiAction

    data class UpdateDutyRotaDraft(val draft: DutyRotaDraft) : DormUiAction
    data object CreateDutyRota : DormUiAction
    data class CompleteDutyTask(val taskId: String) : DormUiAction
    data class ConfirmDutyTask(val taskId: String) : DormUiAction
    data class SkipDutyTask(val taskId: String, val reason: String) : DormUiAction
    data class RequestDutySwap(val taskId: String, val targetMemberId: String) : DormUiAction
    data class ResolveDutySwap(val swapId: String, val action: String) : DormUiAction

    data class UpdateRepairDraft(val draft: RepairDraft) : DormUiAction
    data object SubmitRepairTicket : DormUiAction
    data class TransitionRepair(val repairId: String, val toStatus: String) : DormUiAction
    data class ReviewRepair(val repairId: String, val rating: Int) : DormUiAction

    data object OpenElectricity : DormUiAction
    data object ConsumeNotice : DormUiAction
}
