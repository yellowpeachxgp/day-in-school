package cn.edu.haut.campus.feature.knowledge

import cn.edu.haut.campus.feature.knowledge.preview.CourseSpacePreviewFixtures
import cn.edu.haut.campus.feature.knowledge.state.CourseSpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseSpacePreviewFixturesTest {
    @Test
    fun previewFixturesCoverCompleteElevenStateMatrix() {
        assertEquals(CourseSpaceStatus.entries.size, CourseSpacePreviewFixtures.states.size)
        assertEquals(11, CourseSpacePreviewFixtures.states.size)
        CourseSpaceStatus.entries.forEach { status ->
            assertEquals(status, CourseSpacePreviewFixtures.stateFor(status).status)
        }
    }

    @Test
    fun contentFixtureShowsSearchLocationsAndTimelineDeadline() {
        val content = CourseSpacePreviewFixtures.stateFor(CourseSpaceStatus.CONTENT)
        assertTrue(content.searchHits.any { it.locationLabel.contains("页") })
        assertTrue(content.searchHits.any { it.locationLabel.contains("图片") })
        assertTrue(content.deadlines.all { it.inTimeline })
        assertTrue(content.reviewTasks.any { it.stateLabel == "手动计划" })
    }
}
