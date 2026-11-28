package cn.edu.haut.campus.feature.timetable

import cn.edu.haut.campus.core.model.TimeEvidence
import cn.edu.haut.campus.core.timeline.CourseOccurrenceTimelineMetadata
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import cn.edu.haut.campus.feature.timetable.preview.TimetablePreviewFixtures
import cn.edu.haut.campus.feature.timetable.state.TimetableScreenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun partialFixtureReprojectsUncertainTimelineSemantics() {
        val contentEvent = TimetablePreviewFixtures.stateFor(TimetableScreenStatus.CONTENT)
            .timelineEvents
            .first { it.kind == TimelineKind.COURSE }
        assertEquals(TimelineTimeSemantics.TIMED, contentEvent.timeSemantics)

        val uncertainEvent = TimetablePreviewFixtures.stateFor(TimetableScreenStatus.PARTIAL)
            .timelineEvents
            .first { it.kind == TimelineKind.COURSE }
        assertEquals(TimelineTimeSemantics.UNCERTAIN, uncertainEvent.timeSemantics)
        assertNull("PARTIAL 作息缺失时不能复用精确 end", uncertainEvent.end)
        val decoded = CourseOccurrenceTimelineMetadata.decode(uncertainEvent)
        assertFalse(decoded.hasResolvedTime)
        assertEquals(TimeEvidence.UNRESOLVED, decoded.timeEvidence)
    }
}
