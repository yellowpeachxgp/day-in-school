package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.feature.dorm.preview.DormPreviewFixtures
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DormPreviewFixturesTest {
    @Test
    fun previewFixturesCoverCompleteElevenStateMatrix() {
        assertEquals(11, DormPreviewFixtures.states.size)
        assertEquals(DormScreenStatus.entries.size, DormPreviewFixtures.states.size)
        DormScreenStatus.entries.forEach { status ->
            assertEquals(status, DormPreviewFixtures.stateFor(status).status)
        }
    }

    @Test
    fun contentFixtureKeepsLedgerZeroSumAndNonPaymentSemanticsVisible() {
        val snapshot = DormPreviewFixtures.contentSnapshot
        assertEquals(0L, snapshot.balances?.members?.sumOf { it.balanceFen })
        snapshot.ledgerEntries.forEach { entry ->
            assertEquals(entry.amountFen, entry.shares.sumOf { it.amountFen })
        }
        assertTrue(snapshot.settlements.first().note.orEmpty().contains("不是支付"))
        assertTrue(DormPreviewFixtures.stateFor(DormScreenStatus.CONTENT).utilityReminders.first().lowBalance)
    }
}
