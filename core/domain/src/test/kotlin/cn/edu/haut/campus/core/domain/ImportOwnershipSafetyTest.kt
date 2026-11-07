package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.*
import java.time.DayOfWeek
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportOwnershipSafetyTest {
    @Test fun emptyOfficialSnapshotCannotDeleteFileImportedCourses() {
        val course = Course("csv", name = "Local course", colorArgb = 0L, source = CourseSource.CSV_ICS)
        val rule = CourseScheduleRule("rule", course.id, "term", DayOfWeek.MONDAY, 1, 2, setOf(1), "room")
        val preview = ImportDiffEngine.computeDiff("term", CourseSource.OFFICIAL_JW,
            listOf(course), listOf(rule), emptyList())
        assertTrue(preview.entries.isEmpty())
    }
}
