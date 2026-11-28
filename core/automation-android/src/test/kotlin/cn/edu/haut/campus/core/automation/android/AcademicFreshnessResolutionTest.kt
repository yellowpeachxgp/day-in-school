package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportAudit
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcademicFreshnessResolutionTest {
    @Test
    fun newerAuditForSameCurrentTermWinsOverOlderPreference() {
        val prefs = AppPreferencesState(
            lastAcademicDataSyncEpochMillis = 1000L,
            lastAcademicDataSyncTermId = "2025-fall"
        )

        val resolved = resolveAcademicDataFreshnessEpochMs(
            prefs = prefs,
            currentTermId = "2025-fall",
            importAudits = listOf(audit("2025-fall", 2000L), audit("2026-spring", 9000L))
        )

        assertEquals(2000L, resolved)
    }

    @Test
    fun otherTermEvidenceCannotImpersonateCurrentTermFreshness() {
        val resolved = resolveAcademicDataFreshnessEpochMs(
            prefs = AppPreferencesState(
                lastAcademicDataSyncEpochMillis = 9000L,
                lastAcademicDataSyncTermId = "2026-spring"
            ),
            currentTermId = "2025-fall",
            importAudits = listOf(audit("2026-spring", 9000L))
        )

        assertNull(resolved)
    }

    private fun audit(termId: String, atMs: Long) = ImportAudit(
        id = "audit-" + termId + "-" + atMs,
        termId = termId,
        importSource = CourseSource.OFFICIAL_JW,
        importedAt = Instant.ofEpochMilli(atMs),
        rowCount = 1,
        conflictCount = 0,
        outcome = "test"
    )
}
