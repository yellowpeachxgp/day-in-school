package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.model.OccurrenceOverride
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 旧版调入核对 journal 的擦除、demo reset 与 Profile 防串号回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyRestoreReviewJournalStoreTest {

    private fun reviewTarget(id: String): OccurrenceOverride = OccurrenceOverride(
        id = id,
        termId = "term_legacy_review",
        courseRuleId = null,
        courseId = "course_legacy_review",
        date = LocalDate.of(2026, 10, 14),
        startPeriod = 1,
        endPeriod = 2,
        room = "合成旧教室",
        canceled = false,
        reason = "由 2026-10-12 调入",
        mutationToken = "mutation-" + id
    )

    private suspend fun seedJournal(
        fixture: RepairTestFixture,
        id: String
    ): OccurrenceOverride {
        val target = reviewTarget(id)
        fixture.dao.upsertOccurrenceOverride(target.toEntity())
        fixture.repository.timetableRepository.appendLegacyRestoreReviewTargets(listOf(target))
        return target
    }

    @Test
    fun wipeAndDemoResetClearRawJournalKeyAndFlow() = runBlocking {
        val fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_legacy_journal_wipe_" + System.nanoTime()
        )
        try {
            val first = seedJournal(fixture, "ov_legacy_review_wipe")
            assertNotNull(fixture.dataStore.data.first()[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON])
            assertEquals(
                listOf(first.id),
                fixture.repository.timetableRepository.legacyRestoreReviewTargetsFlow.first().map { it.id }
            )

            fixture.repository.wipeAllPersonalData()

            assertNull(fixture.dataStore.data.first()[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON])
            assertTrue(fixture.repository.timetableRepository.legacyRestoreReviewTargetsFlow.first().isEmpty())

            seedJournal(fixture, "ov_legacy_review_demo")
            assertNotNull(fixture.dataStore.data.first()[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON])

            fixture.repository.resetToDemoSeedData()

            assertNull(fixture.dataStore.data.first()[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON])
            assertTrue(fixture.repository.timetableRepository.legacyRestoreReviewTargetsFlow.first().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun wrongProfilePayloadIsRejectedBeforeUiConsumption() = runBlocking {
        val fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_legacy_journal_profile_" + System.nanoTime()
        )
        try {
            val target = reviewTarget("ov_legacy_review_profile")
            fixture.dao.upsertOccurrenceOverride(target.toEntity())
            val foreignPayload = LegacyRestoreReviewJournalCodec.encode(
                profileId = "another-profile",
                targets = listOf(
                    LegacyRestoreReviewTargetRef(
                        id = target.id,
                        termId = target.termId,
                        mutationToken = target.mutationToken
                    )
                )
            )
            fixture.dataStore.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON] = foreignPayload
            }

            assertNotNull(fixture.dataStore.data.first()[CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON])
            assertTrue(
                LegacyRestoreReviewJournalCodec.decode(
                    raw = foreignPayload,
                    expectedProfileId = fixture.profile.id
                ).isEmpty()
            )
            assertTrue(fixture.repository.timetableRepository.legacyRestoreReviewTargetsFlow.first().isEmpty())
        } finally {
            fixture.close()
        }
    }
}
