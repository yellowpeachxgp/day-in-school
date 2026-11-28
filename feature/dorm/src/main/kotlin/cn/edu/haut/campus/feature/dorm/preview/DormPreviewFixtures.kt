package cn.edu.haut.campus.feature.dorm.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.feature.dorm.DormDutyRota
import cn.edu.haut.campus.feature.dorm.DormDutySwap
import cn.edu.haut.campus.feature.dorm.DormDutyTask
import cn.edu.haut.campus.feature.dorm.DormDutyTaskStatus
import cn.edu.haut.campus.feature.dorm.DormGroupSummary
import cn.edu.haut.campus.feature.dorm.DormLedgerBalances
import cn.edu.haut.campus.feature.dorm.DormLedgerEntry
import cn.edu.haut.campus.feature.dorm.DormLedgerEntryKind
import cn.edu.haut.campus.feature.dorm.DormMember
import cn.edu.haut.campus.feature.dorm.DormMemberBalance
import cn.edu.haut.campus.feature.dorm.DormRepairTicket
import cn.edu.haut.campus.feature.dorm.DormSettlement
import cn.edu.haut.campus.feature.dorm.DormSettlementStatus
import cn.edu.haut.campus.feature.dorm.DormSnapshot
import cn.edu.haut.campus.feature.dorm.DormSplitRule
import cn.edu.haut.campus.feature.dorm.DormSplitRuleType
import cn.edu.haut.campus.feature.dorm.DormSplitShare
import cn.edu.haut.campus.feature.dorm.DormUtilityReminder
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import cn.edu.haut.campus.feature.dorm.state.DormUiState
import cn.edu.haut.campus.feature.dorm.state.LedgerEntryDraft
import cn.edu.haut.campus.feature.dorm.ui.DormScreen

/** 11 态矩阵 fixtures；全部使用合成 UUID/合成宿舍数据，不对应真实学生或房间。 */
object DormPreviewFixtures {
    const val groupId = "00000000-0000-4000-8000-000000000101"
    const val member1 = "00000000-0000-4000-8000-000000000001"
    const val member2 = "00000000-0000-4000-8000-000000000002"
    const val member3 = "00000000-0000-4000-8000-000000000003"
    const val cloudUser1 = "10000000-0000-4000-8000-000000000001"
    const val cloudUser2 = "10000000-0000-4000-8000-000000000002"
    const val cloudUser3 = "10000000-0000-4000-8000-000000000003"

    val members = listOf(
        DormMember(member1, cloudUser1, "示例室友甲", "OWNER", "ACTIVE"),
        DormMember(member2, cloudUser2, "示例室友乙", "MEMBER", "ACTIVE"),
        DormMember(member3, cloudUser3, "示例室友丙", "MEMBER", "ACTIVE"),
    )
    private val group = DormGroupSummary(groupId, "haut", "示例宿舍 101", "合成预览群组", 3, "OWNER")
    private val equalRule = DormSplitRule(DormSplitRuleType.EQUAL)
    private val entry = DormLedgerEntry(
        entryId = "20000000-0000-4000-8000-000000000001",
        groupId = groupId,
        schoolId = "haut",
        kind = DormLedgerEntryKind.EXPENSE,
        correctsEntryId = null,
        correctionReason = null,
        title = "公共洗衣液",
        note = "合成预览账目",
        amountFen = 1000L,
        paidByMemberId = member1,
        occurredAt = "2026-11-20T12:00:00Z",
        splitRule = equalRule,
        shares = listOf(
            DormSplitShare(member1, 334L),
            DormSplitShare(member2, 333L),
            DormSplitShare(member3, 333L),
        ),
        createdByMemberId = member1,
        createdAt = "2026-11-20T12:00:01Z",
    )
    private val correction = entry.copy(
        entryId = "20000000-0000-4000-8000-000000000002",
        kind = DormLedgerEntryKind.CORRECTION,
        correctsEntryId = entry.entryId,
        correctionReason = "金额录错后全额冲正",
        amountFen = -1000L,
        shares = listOf(
            DormSplitShare(member1, -334L),
            DormSplitShare(member2, -333L),
            DormSplitShare(member3, -333L),
        ),
    )
    private val balances = DormLedgerBalances(
        groupId = groupId,
        balanceRevision = 7L,
        generatedAt = "2026-11-20T12:05:00Z",
        zeroSumCheckFen = 0L,
        members = listOf(
            DormMemberBalance(member1, 1000L, 334L, 0L, 0L, 666L),
            DormMemberBalance(member2, 0L, 333L, 0L, 0L, -333L),
            DormMemberBalance(member3, 0L, 333L, 0L, 0L, -333L),
        ),
    )
    private val settlement = DormSettlement(
        settlementId = "30000000-0000-4000-8000-000000000001",
        debtorMemberId = member2,
        creditorMemberId = member1,
        amountFen = 333L,
        basisBalanceRevision = 7L,
        status = DormSettlementStatus.PENDING_CREDITOR_CONFIRMATION,
        note = "合成两方确认记录；不是支付",
        createdAt = "2026-11-20T12:06:00Z",
        debtorConfirmedAt = "2026-11-20T12:07:00Z",
        creditorConfirmedAt = null,
        confirmedAt = null,
        cancelledAt = null,
    )
    private val rota = DormDutyRota(
        rotaId = "40000000-0000-4000-8000-000000000001",
        title = "宿舍值日",
        description = "合成轮班",
        rotationMemberIds = listOf(member1, member2, member3),
        frequency = "WEEKLY",
        daysOfWeek = listOf(6),
        startDate = "2026-11-21",
        endDate = null,
        timeZone = "Asia/Shanghai",
        status = "ACTIVE",
        generatedThroughDate = "2026-12-05",
    )
    private val task = DormDutyTask(
        taskId = "50000000-0000-4000-8000-000000000001",
        rotaId = rota.rotaId,
        title = "扫地倒垃圾",
        assignedMemberId = member2,
        dueDate = "2026-11-28",
        status = DormDutyTaskStatus.PENDING,
        completionNote = null,
        confirmationNote = null,
        skipReason = null,
    )
    private val swap = DormDutySwap(
        swapId = "60000000-0000-4000-8000-000000000001",
        rotaId = rota.rotaId,
        dutyTaskId = task.taskId,
        type = "COVER",
        requestedByMemberId = member2,
        targetMemberId = member3,
        reason = "合成请假代值请求",
        status = "PENDING",
        resolutionNote = null,
    )
    private val repair = DormRepairTicket(
        repairId = "70000000-0000-4000-8000-000000000001",
        title = "水龙头滴水",
        category = "PLUMBING",
        locationText = "示例宿舍 101 洗手间",
        description = "合成报修协调单，不代表学校或物业系统状态。",
        reporterMemberId = member3,
        assignedMemberId = null,
        status = "OPEN",
        resolutionNote = null,
        reviewId = null,
        createdAt = "2026-11-20T13:00:00Z",
        updatedAt = "2026-11-20T13:00:00Z",
    )

    val contentSnapshot = DormSnapshot(
        groups = listOf(group),
        selectedGroupId = groupId,
        members = members,
        ledgerEntries = listOf(correction, entry),
        balances = balances,
        settlements = listOf(settlement),
        rotas = listOf(rota),
        dutyTasks = listOf(task),
        dutySwaps = listOf(swap),
        repairs = listOf(repair),
    )

    private val utility = listOf(
        DormUtilityReminder(
            meterLabel = "示例宿舍 101 · 电表",
            utilityKind = "ELECTRICITY",
            balanceText = "12.30度",
            lowBalance = true,
            observedAtEpochMillis = 1763700000000L,
            detail = "合成低余额提醒；不执行缴费",
        ),
    )

    private val content = DormUiState(
        loading = false,
        status = DormScreenStatus.CONTENT,
        snapshot = contentSnapshot,
        utilityReminders = utility,
        currentCloudUserId = cloudUser1,
        ledgerDraft = LedgerEntryDraft(title = "公共垃圾袋", amountYuan = "10.00"),
    )
    private val emptySnapshot = DormSnapshot(groups = emptyList(), selectedGroupId = null)
    private val empty = DormUiState(
        loading = false,
        status = DormScreenStatus.EMPTY,
        statusDetail = "当前账号还没有宿舍群组。",
        snapshot = emptySnapshot,
    )

    val states: Map<DormScreenStatus, DormUiState> = linkedMapOf(
        DormScreenStatus.LOADING to DormUiState(loading = true, status = DormScreenStatus.LOADING),
        DormScreenStatus.EMPTY to empty,
        DormScreenStatus.CONTENT to content,
        DormScreenStatus.PARTIAL to content.copy(status = DormScreenStatus.PARTIAL, statusDetail = "部分协作列表超过首屏分页，已明确标注为不完整"),
        DormScreenStatus.STALE to content.copy(
            status = DormScreenStatus.STALE,
            statusDetail = "群组元数据来自本地缓存；协作明细未伪装成新数据",
            snapshot = contentSnapshot.copy(staleness = Staleness.STALE, cachedAtEpochMillis = 1763700000000L),
        ),
        DormScreenStatus.ERROR_RETRYABLE to content.copy(status = DormScreenStatus.ERROR_RETRYABLE, statusDetail = "云端暂时不可用，可重试", statusRetryable = true),
        DormScreenStatus.ERROR_NON_RETRYABLE to content.copy(status = DormScreenStatus.ERROR_NON_RETRYABLE, statusDetail = "契约响应缺少必填字段，已 fail-closed"),
        DormScreenStatus.NEEDS_PERMISSION to content.copy(status = DormScreenStatus.NEEDS_PERMISSION, statusDetail = "需要用户授权后才能继续"),
        DormScreenStatus.NEEDS_LOGIN to content.copy(status = DormScreenStatus.NEEDS_LOGIN, statusDetail = "需要登录 XPeach 校园云后才能使用宿舍协作"),
        DormScreenStatus.OFFLINE to content.copy(status = DormScreenStatus.OFFLINE, statusDetail = "当前离线；账本、值日和报修明细不可用"),
        DormScreenStatus.UNSUPPORTED to content.copy(status = DormScreenStatus.UNSUPPORTED, statusDetail = "当前云端未启用宿舍协作能力"),
    )

    fun stateFor(status: DormScreenStatus): DormUiState = states.getValue(status)
}

@Composable
internal fun DormPreviewHarness(state: DormUiState) {
    HautCampusTheme(
        darkTheme = false,
        themeMode = "LIGHT",
        uiDesignStyleKey = "FLUTTER_M3",
        dynamicColor = false,
        themePaletteKey = "HAUT_BLUE",
    ) {
        DormScreen(state = state, onAction = {})
    }
}

@Preview(name = "Dorm Content", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DormContentPreview() = DormPreviewHarness(DormPreviewFixtures.stateFor(DormScreenStatus.CONTENT))

@Preview(name = "Dorm Empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DormEmptyPreview() = DormPreviewHarness(DormPreviewFixtures.stateFor(DormScreenStatus.EMPTY))

@Preview(name = "Dorm Offline", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DormOfflinePreview() = DormPreviewHarness(DormPreviewFixtures.stateFor(DormScreenStatus.OFFLINE))
