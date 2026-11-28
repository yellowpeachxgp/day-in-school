package cn.edu.haut.campus.feature.today

import cn.edu.haut.campus.feature.today.preview.TodayPreviewFixtures
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayPreviewFixturesTest {
    @Test
    fun fixturesCoverUnifiedElevenStateMatrix() {
        assertEquals(11, TodayPreviewFixtures.states.size)
        assertEquals(TodayScreenStatus.entries.size, TodayPreviewFixtures.states.size)
        TodayScreenStatus.entries.forEach { status ->
            assertTrue("缺少状态 " + status, TodayPreviewFixtures.states.containsKey(status))
            assertEquals(status, TodayPreviewFixtures.stateFor(status).status)
        }
    }
}
