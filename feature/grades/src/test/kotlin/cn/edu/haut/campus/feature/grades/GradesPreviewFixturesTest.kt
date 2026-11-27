package cn.edu.haut.campus.feature.grades

import cn.edu.haut.campus.feature.grades.preview.GradesPreviewFixtures
import cn.edu.haut.campus.feature.grades.state.GradesScreenStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GradesPreviewFixturesTest {
    @Test
    fun fixturesCoverCompleteStatusMatrix() {
        assertEquals(GradesScreenStatus.entries.size, GradesPreviewFixtures.states.size)
        GradesScreenStatus.entries.forEach { status ->
            assertEquals(status, GradesPreviewFixtures.stateFor(status).status)
        }
    }
}
