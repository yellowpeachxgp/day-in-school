package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.edu.haut.campus.core.model.OccurrenceOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

/**
 * W0-05-b 搬迁前特征冻结：精确恢复命令、mutationToken、单事务回滚与通知副作用。
 * 这些用例只使用 in-memory Room/DataStore，不访问网络或真实学校账号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005bCharacterizationTest {
    private lateinit var fixture: RepairTestFixture
    private val repo get() = fixture.repository
    private val dao get() = fixture.dao
    private val termId = "2026-2027-1"
    private val sourceDate = LocalDate.of(2026, 10, 12)
    private val targetDate = LocalDate.of(2026, 10, 14)

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_w005b_characterization_" + System.nanoTime()
        )
    }

    @After
    fun tearDown() {
        repo.occurrenceCommandInTransactionHook = null
        repo.occurrenceCommandWriteProgressHook = null
        repo.onScheduleOrPolicyChangedListener = null
        fixture.close()
    }

    private fun overrideRow(
        id: String,
        ruleId: String?,
        canceled: Boolean,
        date: LocalDate,
        moveId: String? = null,
        moveRole: String? = null,
        reason: String? = null
    ) = OccurrenceOverride(
        id = id,
        termId = termId,
        courseRuleId = ruleId,
        courseId = if (ruleId == null) "c_pure" else "c_rule",
        date = date,
        canceled = canceled,
        reason = reason,
        moveId = moveId,
        moveRole = moveRole
    ).toEntity()

    private fun selectedRow(row: OccurrenceOverrideEntity) = CampusRepository.SelectedOverride(
        id = row.id,
        mutationToken = row.mutationToken,
        ruleId = row.courseRuleId,
        dateIso = row.dateIso,
        expectedMove = row.moveId?.takeIf { it.isNotBlank() }
            ?.let { CampusRepository.ExpectedMove.Move(it) }
            ?: CampusRepository.ExpectedMove.NoMove,
        role = row.moveRole
    )

    private fun selectedMove(moveId: String, rows: List<OccurrenceOverrideEntity>) = CampusRepository.SelectedMove(
        moveId = moveId,
        expectedMembers = rows.filter { it.moveId == moveId }
            .map { CampusRepository.RowIdentity(it.id, it.mutationToken) }
    )

    private fun restoreCommand(
        rows: List<CampusRepository.SelectedOverride> = emptyList(),
        moves: List<CampusRepository.SelectedMove> = emptyList()
    ) = CampusRepository.RestoreCommand(
        commandId = "w005b-" + UUID.randomUUID().toString(),
        accountProfileId = repo.profileId,
        termId = termId,
        intent = CampusRepository.RestoreIntent.RESTORE_SELECTION,
        selectedRows = rows,
        selectedMoves = moves
    )

    private suspend fun overrides() = dao.getOccurrenceOverridesSnapshot()

    @Test
    fun w005b_restore_command_applies_atomically_with_single_notification() = runBlocking {
        val moveId = "move_w005b_apply"
        val source = overrideRow("ov_src_apply", "r_src", true, sourceDate, moveId, OccurrenceOverride.MOVE_ROLE_SOURCE, "已调至 " + targetDate)
        val target = overrideRow("ov_target_apply", null, false, targetDate, moveId, OccurrenceOverride.MOVE_ROLE_TARGET, "由 " + sourceDate + " 调入")
        dao.upsertOccurrenceOverrides(listOf(source, target))
        val before = overrides()
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }

        val result = repo.executeRestoreCommand(
            restoreCommand(
                rows = listOf(selectedRow(before.single { it.id == source.id })),
                moves = listOf(selectedMove(moveId, before))
            )
        )

        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, result.status)
        assertEquals(listOf(moveId), result.restoredMoveIds)
        assertEquals(listOf(source.id), result.restoredSourceOverrideIds)
        assertEquals(listOf(target.id), result.deletedTargetOverrideIds)
        assertTrue(overrides().isEmpty())
        assertEquals(listOf("OCCURRENCE_RESTORED"), notifications)
    }

    @Test
    fun w005b_stale_mutation_token_conflicts_without_write_or_notification() = runBlocking {
        val row = overrideRow("ov_plain_conflict", "r_plain", true, sourceDate, reason = "普通停课")
        dao.upsertOccurrenceOverrides(listOf(row))
        val before = overrides()
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }
        val stale = selectedRow(before.single()).copy(mutationToken = "stale-token")

        val result = repo.executeRestoreCommand(restoreCommand(rows = listOf(stale)))

        assertEquals(CampusRepository.OccurrenceCommandStatus.CONFLICT, result.status)
        assertEquals(before, overrides())
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun w005b_write_progress_failure_rolls_back_and_sends_no_notification() = runBlocking {
        val moveId = "move_w005b_rollback"
        val source = overrideRow("ov_src_rollback", "r_src_rb", true, sourceDate, moveId, OccurrenceOverride.MOVE_ROLE_SOURCE, "已调至 " + targetDate)
        val target = overrideRow("ov_target_rollback", null, false, targetDate, moveId, OccurrenceOverride.MOVE_ROLE_TARGET, "由 " + sourceDate + " 调入")
        dao.upsertOccurrenceOverrides(listOf(source, target))
        val before = overrides()
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }
        repo.occurrenceCommandWriteProgressHook = { _, _ -> throw IllegalStateException("injected restore write failure") }

        val outcome = runCatching {
            repo.executeRestoreCommand(
                restoreCommand(
                    rows = listOf(selectedRow(before.single { it.id == source.id })),
                    moves = listOf(selectedMove(moveId, before))
                )
            )
        }

        assertTrue(outcome.isFailure)
        assertEquals(before, overrides())
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun w005b_unpause_stamps_new_mutation_token_and_notifies_once() = runBlocking {
        val row = overrideRow("ov_paused_target", null, true, targetDate, "move_keep", OccurrenceOverride.MOVE_ROLE_TARGET, "暂停")
        dao.upsertOccurrenceOverrides(listOf(row))
        val before = overrides().single()
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }

        val result = repo.unpauseOccurrence(termId, row.id, expectedMoveId = "move_keep")

        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, result.status)
        val after = overrides().single()
        assertEquals(false, after.canceled)
        assertEquals(null, after.reason)
        assertEquals("move_keep", after.moveId)
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, after.moveRole)
        assertNotEquals(before.mutationToken, after.mutationToken)
        assertTrue(after.mutationToken.isNotBlank())
        assertEquals(listOf("OCCURRENCE_RESTORED"), notifications)
    }

    @Test
    fun w005b_plain_override_write_delete_token_and_notification_snapshot() = runBlocking {
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }
        val row = OccurrenceOverride(
            id = "ov_plain_write",
            termId = termId,
            courseRuleId = "r_plain_write",
            courseId = "c_plain_write",
            date = sourceDate,
            canceled = true,
            reason = "普通停课"
        )

        repo.saveOccurrenceOverride(row)
        val first = overrides().single()
        repo.saveOccurrenceOverride(row)
        val second = overrides().single()
        repo.deleteOccurrenceOverride(row.id)

        assertTrue(first.mutationToken.isNotBlank())
        assertNotEquals(first.mutationToken, second.mutationToken)
        assertTrue(overrides().isEmpty())
        assertEquals(
            listOf("OCCURRENCE_OVERRIDE_SAVED", "OCCURRENCE_OVERRIDE_SAVED", "OCCURRENCE_OVERRIDE_DELETED"),
            notifications
        )
    }

    @Test
    fun w005b_moments_local_favorite_snapshot_and_no_notification() = runBlocking {
        fixture.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("cloud_access_token_v1")] = "test-cloud-token"
        }
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }

        repo.toggleMomentsPostFavorite("post_w005b")
        repo.toggleMomentsBookFavorite("book_w005b")
        val afterAdd = repo.preferencesFlow.first().campusMomentsSnapshot
        repo.toggleMomentsPostFavorite("post_w005b")
        val afterRemove = repo.preferencesFlow.first().campusMomentsSnapshot

        assertEquals(setOf("post_w005b"), afterAdd.favoritePostIds)
        assertEquals(setOf("book_w005b"), afterAdd.favoriteBookIds)
        assertTrue(afterRemove.favoritePostIds.isEmpty())
        assertEquals(setOf("book_w005b"), afterRemove.favoriteBookIds)
        assertTrue(notifications.isEmpty())
    }
}
