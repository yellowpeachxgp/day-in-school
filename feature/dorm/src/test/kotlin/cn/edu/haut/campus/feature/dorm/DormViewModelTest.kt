package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.feature.dorm.preview.DormPreviewFixtures
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DormViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialRefreshShowsContentAndCurrentCloudIdentity() = runTest {
        val port = FakeDormCollaborationPort(DormPreviewFixtures.contentSnapshot)
        val viewModel = DormViewModel(
            port = port,
            utilityStatusPort = FakeUtilityPort,
            schoolId = "haut",
            currentCloudUserId = { DormPreviewFixtures.cloudUser1 },
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DormScreenStatus.CONTENT, state.status)
        assertEquals(DormPreviewFixtures.cloudUser1, state.currentCloudUserId)
        assertEquals(DormPreviewFixtures.groupId, state.snapshot?.selectedGroupId)
    }

    @Test
    fun authenticationAndNetworkErrorsMapToNeedsLoginAndOffline() = runTest {
        val authPort = FakeDormCollaborationPort(
            DormPreviewFixtures.contentSnapshot,
            loadError = DormCollaborationException(DormErrorKind.AUTHENTICATION_REQUIRED, "auth"),
        )
        val authVm = DormViewModel(authPort, FakeUtilityPort, "haut")
        advanceUntilIdle()
        assertEquals(DormScreenStatus.NEEDS_LOGIN, authVm.uiState.value.status)

        val networkPort = FakeDormCollaborationPort(
            DormPreviewFixtures.contentSnapshot,
            loadError = DormCollaborationException(DormErrorKind.NETWORK, "network", retryable = true),
        )
        val networkVm = DormViewModel(networkPort, FakeUtilityPort, "haut")
        advanceUntilIdle()
        assertEquals(DormScreenStatus.OFFLINE, networkVm.uiState.value.status)

        val permissionPort = FakeDormCollaborationPort(
            DormPreviewFixtures.contentSnapshot,
            loadError = DormCollaborationException(DormErrorKind.PERMISSION_DENIED, "forbidden"),
        )
        val permissionVm = DormViewModel(permissionPort, FakeUtilityPort, "haut")
        advanceUntilIdle()
        assertEquals(DormScreenStatus.NEEDS_PERMISSION, permissionVm.uiState.value.status)
    }

    @Test
    fun proposeSettlementUsesLargestDebtorCreditorAndPositiveMinAmount() = runTest {
        val port = FakeDormCollaborationPort(DormPreviewFixtures.contentSnapshot)
        val viewModel = DormViewModel(port, FakeUtilityPort, "haut")
        advanceUntilIdle()

        viewModel.dispatch(DormUiAction.ProposeSettlement)
        advanceUntilIdle()

        val input = port.settlementInputs.single()
        assertEquals(DormPreviewFixtures.member2, input.debtorMemberId)
        assertEquals(DormPreviewFixtures.member1, input.creditorMemberId)
        assertEquals(333L, input.amountFen)
        assertTrue(input.amountFen > 0L)
        assertEquals(7L, input.basisBalanceRevision)
    }

    @Test
    fun reverseEntryAppendsNegativeCorrectionAndSwapUsesExplicitTarget() = runTest {
        val port = FakeDormCollaborationPort(DormPreviewFixtures.contentSnapshot)
        val viewModel = DormViewModel(port, FakeUtilityPort, "haut")
        advanceUntilIdle()
        val original = DormPreviewFixtures.contentSnapshot.ledgerEntries.first { it.amountFen == 1000L }

        viewModel.dispatch(DormUiAction.ReverseLedgerEntry(original.entryId))
        advanceUntilIdle()
        assertEquals(-1000L, port.amendInputs.single().input.amountFen)

        viewModel.dispatch(DormUiAction.RequestDutySwap(DormPreviewFixtures.contentSnapshot.dutyTasks.first().taskId, DormPreviewFixtures.member3))
        advanceUntilIdle()
        assertEquals(DormPreviewFixtures.member3, port.swapRequests.single().second)
    }

    private object FakeUtilityPort : DormUtilityStatusPort {
        override fun reminders(): Flow<List<DormUtilityReminder>> = flowOf(emptyList())
    }

    private class FakeDormCollaborationPort(
        private val snapshot: DormSnapshot,
        private val loadError: DormCollaborationException? = null,
    ) : DormCollaborationPort {
        val settlementInputs = mutableListOf<DormSettlementInput>()
        val amendInputs = mutableListOf<TupleAmendInput>()
        val swapRequests = mutableListOf<Pair<String, String>>()

        override suspend fun loadSnapshot(schoolId: String, preferredGroupId: String?): DormSnapshot {
            loadError?.let { throw it }
            return snapshot
        }

        override suspend fun createDormGroup(schoolId: String, name: String, description: String?, idempotencyKey: String): DormGroupSummary = unsupported()
        override suspend fun createLedgerEntry(groupId: String, input: DormLedgerEntryInput, idempotencyKey: String): DormLedgerEntry = unsupported()

        override suspend fun amendLedgerEntry(groupId: String, entryId: String, reason: String, input: DormLedgerEntryInput, idempotencyKey: String): DormLedgerEntry {
            amendInputs += TupleAmendInput(groupId, entryId, input)
            return snapshot.ledgerEntries.first()
        }

        override suspend fun proposeSettlement(groupId: String, input: DormSettlementInput, idempotencyKey: String): DormSettlement {
            settlementInputs += input
            return snapshot.settlements.first()
        }

        override suspend fun confirmSettlement(settlementId: String, basisBalanceRevision: Long, idempotencyKey: String): DormSettlement = unsupported()
        override suspend fun cancelSettlement(settlementId: String, reason: String?, idempotencyKey: String): DormSettlement = unsupported()
        override suspend fun createDutyRota(groupId: String, input: DormDutyRotaInput, idempotencyKey: String): DormDutyRota = unsupported()
        override suspend fun completeDutyTask(taskId: String, note: String?, idempotencyKey: String): DormDutyTask = unsupported()
        override suspend fun confirmDutyTask(taskId: String, note: String?, idempotencyKey: String): DormDutyTask = unsupported()
        override suspend fun skipDutyTask(taskId: String, reason: String, idempotencyKey: String): DormDutyTask = unsupported()

        override suspend fun requestDutySwap(rotaId: String, taskId: String, type: String, targetMemberId: String, reason: String?, idempotencyKey: String): DormDutySwap {
            swapRequests += taskId to targetMemberId
            return snapshot.dutySwaps.first()
        }

        override suspend fun resolveDutySwap(swapId: String, action: String, note: String?, idempotencyKey: String): DormDutySwap = unsupported()
        override suspend fun createRepairTicket(groupId: String, input: DormRepairInput, idempotencyKey: String): DormRepairTicket = unsupported()
        override suspend fun transitionRepairStatus(repairId: String, toStatus: String, note: String?, assignedMemberId: String?, idempotencyKey: String): DormRepairTicket = unsupported()
        override suspend fun submitRepairReview(repairId: String, rating: Int, comment: String?, idempotencyKey: String) = Unit

        private fun <T> unsupported(): T = error("not used by this test")
    }

    private data class TupleAmendInput(val groupId: String, val entryId: String, val input: DormLedgerEntryInput)
}
