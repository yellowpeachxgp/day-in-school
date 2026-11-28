package cn.edu.haut.campus.feature.dorm

import kotlinx.coroutines.flow.Flow

enum class DormErrorKind {
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    FEATURE_DISABLED,
    NETWORK,
    SERVER,
    CONTRACT,
    VALIDATION,
    UNSUPPORTED,
}

class DormCollaborationException(
    val kind: DormErrorKind,
    message: String,
    val retryable: Boolean = false,
    val userAction: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

data class DormLedgerEntryInput(
    val title: String,
    val amountFen: Long,
    val paidByMemberId: String,
    val occurredAt: String,
    val splitRule: DormSplitRule,
    val entryKind: DormLedgerEntryKind = DormLedgerEntryKind.EXPENSE,
    val note: String? = null,
) {
    init {
        require(entryKind != DormLedgerEntryKind.CORRECTION) { "更正必须走 amendLedgerEntry" }
    }
}

data class DormSettlementInput(
    val debtorMemberId: String,
    val creditorMemberId: String,
    val amountFen: Long,
    val basisBalanceRevision: Long,
    val note: String? = null,
)

data class DormDutyRotaInput(
    val title: String,
    val rotationMemberIds: List<String>,
    val frequency: String,
    val startDate: String,
    val timeZone: String,
    val description: String? = null,
    val daysOfWeek: List<Int> = emptyList(),
    val endDate: String? = null,
    val generateWeeksAhead: Int = 2,
)

data class DormRepairInput(
    val title: String,
    val category: String,
    val locationText: String,
    val description: String,
)

/** feature 边界 Port。所有写操作幂等键由 ViewModel/调用方显式生成，adapter 不静默重用。 */
interface DormCollaborationPort {
    suspend fun loadSnapshot(schoolId: String, preferredGroupId: String? = null): DormSnapshot

    suspend fun createDormGroup(
        schoolId: String,
        name: String,
        description: String?,
        idempotencyKey: String,
    ): DormGroupSummary

    suspend fun createLedgerEntry(groupId: String, input: DormLedgerEntryInput, idempotencyKey: String): DormLedgerEntry

    suspend fun amendLedgerEntry(
        groupId: String,
        entryId: String,
        reason: String,
        input: DormLedgerEntryInput,
        idempotencyKey: String,
    ): DormLedgerEntry

    suspend fun proposeSettlement(groupId: String, input: DormSettlementInput, idempotencyKey: String): DormSettlement

    suspend fun confirmSettlement(settlementId: String, basisBalanceRevision: Long, idempotencyKey: String): DormSettlement

    suspend fun cancelSettlement(settlementId: String, reason: String?, idempotencyKey: String): DormSettlement

    suspend fun createDutyRota(groupId: String, input: DormDutyRotaInput, idempotencyKey: String): DormDutyRota

    suspend fun completeDutyTask(taskId: String, note: String?, idempotencyKey: String): DormDutyTask

    suspend fun confirmDutyTask(taskId: String, note: String?, idempotencyKey: String): DormDutyTask

    suspend fun skipDutyTask(taskId: String, reason: String, idempotencyKey: String): DormDutyTask

    suspend fun requestDutySwap(
        rotaId: String,
        taskId: String,
        type: String,
        targetMemberId: String,
        reason: String?,
        idempotencyKey: String,
    ): DormDutySwap

    suspend fun resolveDutySwap(swapId: String, action: String, note: String?, idempotencyKey: String): DormDutySwap

    suspend fun createRepairTicket(groupId: String, input: DormRepairInput, idempotencyKey: String): DormRepairTicket

    suspend fun transitionRepairStatus(
        repairId: String,
        toStatus: String,
        note: String?,
        assignedMemberId: String?,
        idempotencyKey: String,
    ): DormRepairTicket

    suspend fun submitRepairReview(repairId: String, rating: Int, comment: String?, idempotencyKey: String)
}

/** 本机水电状态输入 Port；实现方必须只提供本 Profile 已授权保存的水电数据。 */
interface DormUtilityStatusPort {
    fun reminders(): Flow<List<DormUtilityReminder>>

    companion object {
        val EMPTY: DormUtilityStatusPort = object : DormUtilityStatusPort {
            override fun reminders(): Flow<List<DormUtilityReminder>> = kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }
}
