package cn.edu.haut.campus.feature.teamtime

import cn.edu.haut.campus.feature.teamtime.preview.TeamTimePreviewFixtures
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamTimePreviewFixturesTest {
    @Test
    fun previewFixturesCoverCompleteElevenStateMatrix() {
        assertEquals(11, TeamTimeStatus.entries.size)
        assertEquals(TeamTimeStatus.entries.size, TeamTimePreviewFixtures.states.size)
        TeamTimeStatus.entries.forEach { status ->
            assertEquals(status, TeamTimePreviewFixtures.stateFor(status).status)
        }
    }

    @Test
    fun contentFixtureShowsAggregateOnlyAndPersonalComparison() {
        val content = TeamTimePreviewFixtures.stateFor(TeamTimeStatus.CONTENT)
        assertTrue(content.candidates.isNotEmpty())
        assertTrue(content.candidates.all { it.quorumLabel.contains("可参加") })
        assertTrue(content.candidates.any { it.personalConflicts })
        assertTrue(content.candidates.any { !it.personalConflicts })
        assertFalse(content.share.detailLabel.contains("原始块"))
        assertTrue(content.poll != null)
    }
}
