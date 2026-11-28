package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/** W0-04/C03：节次编辑器、作息方案持久化、事务拒绝与擦除链路回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PeriodTimeSchemeRepositoryTest {
    private lateinit var fixture: RepairTestFixture
    private val dao get() = fixture.dao
    private val repo get() = fixture.repository

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekCount = 20,
        timeTableId = HautMockSeedData.DEFAULT_TIMETABLE_ID,
        current = true
    )

    @Before
    fun setUp() = runBlocking {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_period_scheme_" + System.nanoTime())
        dao.upsertTerms(listOf(term.toEntity()))
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun draftPeriod(no: Int, start: String, end: String): PeriodTime = PeriodTime(
        id = "",
        timeTableId = term.timeTableId,
        periodNo = no,
        startLocalTime = LocalTime.parse(start),
        endLocalTime = LocalTime.parse(end)
    )

    @Test
    fun updatePeriodTimeRejectsOverlapWithoutPartialWrite() = runBlocking {
        val original = dao.getPeriodTimesSnapshot().first { it.periodNo == 1 }.toDomain()
        val second = dao.getPeriodTimesSnapshot().first { it.periodNo == 2 }.toDomain()
        val invalid = original.copy(endLocalTime = second.startLocalTime.plusMinutes(5))
        val result = runCatching { repo.termRepository.updatePeriodTime(invalid) }
        assertTrue(result.isFailure)
        val persisted = dao.getPeriodTimesSnapshot().first { it.periodNo == 1 }.toDomain()
        assertEquals(original.startLocalTime, persisted.startLocalTime)
        assertEquals(original.endLocalTime, persisted.endLocalTime)
    }

    @Test
    fun saveBasePeriodTimesRejectsInvalidGroupAtomically() = runBlocking {
        val before = dao.getPeriodTimesSnapshot().map { it.id to it.startLocalTimeIso + "-" + it.endLocalTimeIso }.toMap()
        val invalidGroup = listOf(
            draftPeriod(1, "08:00", "09:00"),
            draftPeriod(2, "08:30", "09:30")
        )
        val result = runCatching {
            repo.termRepository.saveBasePeriodTimes(term.timeTableId, invalidGroup)
        }
        assertTrue(result.isFailure)
        val after = dao.getPeriodTimesSnapshot().map { it.id to it.startLocalTimeIso + "-" + it.endLocalTimeIso }.toMap()
        assertEquals(before, after)
    }

    @Test
    fun saveAndDeleteDatedSchemePersistsEntriesAtomically() = runBlocking {
        val schemeId = repo.termRepository.savePeriodTimeScheme(
            term = term,
            name = "冬季作息",
            startDate = LocalDate.of(2026, 10, 1),
            endDate = LocalDate.of(2026, 12, 31),
            periods = listOf(
                draftPeriod(1, "09:00", "09:45"),
                draftPeriod(2, "09:55", "10:40")
            )
        )
        assertEquals(1, dao.countPeriodTimeSchemes())
        assertEquals(2, dao.countSchemePeriodTimes())
        val rows = dao.getPeriodTimesSnapshot().filter { it.schemeId == schemeId }
        assertEquals(listOf(1, 2), rows.sortedBy { it.periodNo }.map { it.periodNo })
        assertTrue(rows.all { it.termId == term.id })
        assertTrue(rows.all { it.validStartDateIso == "2026-10-01" })
        assertTrue(rows.all { it.validEndDateIso == "2026-12-31" })
        assertTrue(rows.all { it.userModified })

        val overlap = runCatching {
            repo.termRepository.savePeriodTimeScheme(
                term = term,
                name = "重叠方案",
                startDate = LocalDate.of(2026, 12, 31),
                endDate = LocalDate.of(2027, 1, 7),
                periods = listOf(draftPeriod(1, "08:00", "08:45"))
            )
        }
        assertTrue(overlap.isFailure)
        assertEquals(1, dao.countPeriodTimeSchemes())
        assertEquals(2, dao.countSchemePeriodTimes())

        repo.termRepository.savePeriodTimeScheme(
            term = term,
            name = "冬季作息",
            startDate = LocalDate.of(2026, 10, 1),
            endDate = LocalDate.of(2026, 12, 31),
            periods = listOf(draftPeriod(1, "09:05", "09:50")),
            schemeId = schemeId
        )
        assertEquals(1, dao.countSchemePeriodTimes())
        val edited = dao.getPeriodTimesSnapshot().single { it.schemeId == schemeId }
        assertEquals("09:05", edited.startLocalTimeIso)

        repo.termRepository.deletePeriodTimeScheme(schemeId)
        assertEquals(0, dao.countPeriodTimeSchemes())
        assertEquals(0, dao.countSchemePeriodTimes())
        assertTrue(dao.getPeriodTimesSnapshot().any { it.schemeId == null && it.timeTableId == term.timeTableId })
    }

    @Test
    fun userEditedBasePeriodsAreNotReexpandedByLegacySeedRepair() = runBlocking {
        repo.termRepository.saveBasePeriodTimes(
            timeTableId = term.timeTableId,
            periods = listOf(draftPeriod(1, "08:00", "08:45"))
        )
        repo.termRepository.ensureExtendedPeriodTimes11To12IfNeeded()
        val baseRows = dao.getPeriodTimesSnapshot().filter {
            it.timeTableId == term.timeTableId && it.schemeId == null
        }
        assertEquals(1, baseRows.size)
        assertEquals(1, baseRows.single().periodNo)
        assertTrue(baseRows.single().userModified)
    }

    @Test
    fun wipeAllPersonalDataClearsSchemesAndRestoresReferencePeriods() = runBlocking {
        // 模拟 v10 老库：用户可能已改第 1 节，但迁移无法反证编辑史，userModified 仍为 false。
        val legacyEdited = HautMockSeedData.defaultPeriodTimes.first { it.periodNo == 1 }
            .copy(startLocalTime = LocalTime.of(7, 50), endLocalTime = LocalTime.of(8, 35), userModified = false)
        dao.upsertPeriodTimes(listOf(legacyEdited.toEntity()))
        repo.termRepository.savePeriodTimeScheme(
            term = term,
            name = "擦除测试",
            startDate = LocalDate.of(2026, 10, 1),
            endDate = LocalDate.of(2026, 10, 31),
            periods = listOf(draftPeriod(1, "09:00", "09:45"))
        )
        assertEquals(1, dao.countPeriodTimeSchemes())
        assertEquals(1, dao.countSchemePeriodTimes())
        repo.wipeAllPersonalData()
        assertEquals(0, dao.countPeriodTimeSchemes())
        assertEquals(0, dao.countSchemePeriodTimes())
        val restored = dao.getPeriodTimesSnapshot().filter {
            it.timeTableId == term.timeTableId && it.schemeId == null
        }
        assertEquals(12, restored.size)
        assertTrue(restored.all { !it.userModified })
        assertEquals("08:30", restored.first { it.periodNo == 1 }.startLocalTimeIso)
    }
}
