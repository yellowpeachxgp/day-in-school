package cn.edu.haut.campus.feature.timetable

import cn.edu.haut.campus.feature.timetable.preview.TimetablePreviewFixtures
import cn.edu.haut.campus.feature.timetable.state.TimetableScreenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetablePreviewFixturesTest {
    @Test
    fun fixturesCoverUnifiedElevenStateMatrix() {
        assertEquals(11, TimetablePreviewFixtures.states.size)
        assertEquals(TimetableScreenStatus.entries.size, TimetablePreviewFixtures.states.size)
        TimetableScreenStatus.entries.forEach { status ->
            assertTrue("缺少状态 " + status, TimetablePreviewFixtures.states.containsKey(status))
            assertEquals(status, TimetablePreviewFixtures.stateFor(status).status)
        }
    }
}
