package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.edu.haut.campus.core.model.OccurrenceOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private suspend fun writeRawMomentsSnapshotForOfflineTest(rawJson: String) {
        fixture.dataStore.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] = rawJson
            // 非授权域名在云端 HTTP helper 的域名栅栏处即失败：测试永不进行真实网络连接。
            prefs[CampusRepositoryPrefKeys.CLOUD_API_BASE_URL] = "https://unauthorized.example.com"
            prefs.remove(CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN)
            prefs.remove(CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN)
        }
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

        assertEquals(setOf("post_w005b"), afterAdd.favoritePostIds)
        assertEquals(setOf("book_w005b"), afterAdd.favoriteBookIds)

        // 游客态读取时隐藏登录态 viewer 集合，但原始本地 CSV 不丢失；重新出现会话标记后仍可恢复并继续切换。
        fixture.dataStore.edit { prefs ->
            prefs.remove(CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN)
        }
        val guestSnapshot = repo.preferencesFlow.first().campusMomentsSnapshot
        val rawAfterLogout = fixture.dataStore.data.first()
        assertTrue(guestSnapshot.favoritePostIds.isEmpty())
        assertTrue(guestSnapshot.favoriteBookIds.isEmpty())
        assertEquals("post_w005b", rawAfterLogout[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS])
        assertEquals("book_w005b", rawAfterLogout[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS])

        fixture.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("cloud_access_token_v1")] = "test-cloud-token"
        }
        repo.toggleMomentsPostFavorite("post_w005b")
        val afterRemove = repo.preferencesFlow.first().campusMomentsSnapshot

        assertTrue(afterRemove.favoritePostIds.isEmpty())
        assertEquals(setOf("book_w005b"), afterRemove.favoriteBookIds)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun w005b_moments_offline_refresh_guest_snapshot_scrubs_identity_and_contact() = runBlocking {
        writeRawMomentsSnapshotForOfflineTest(
            """
            {
              "updated_at": "2026-11-10T01:30:00Z",
              "posts": [
                {
                  "id": "mp_w005b_secret",
                  "type": "LOST",
                  "title": "遗失校园一卡通",
                  "category": "证件",
                  "campus": "莲花街校区",
                  "location": "31号楼",
                  "description": "蓝色卡套",
                  "contact": "微信: secret_wx_w005b",
                  "contact_visibility": "ON_REQUEST",
                  "has_protected_contact": false,
                  "is_owner": true,
                  "can_manage": true,
                  "can_claim_legacy": true,
                  "viewer_role": "OWNER",
                  "pending_contact_requests_count": 2,
                  "has_my_contact_request": true,
                  "my_contact_request_status": "PENDING",
                  "comments": []
                },
                {
                  "id": "mp_w005b_public",
                  "type": "FOUND",
                  "title": "招领雨伞",
                  "category": "水杯雨伞",
                  "campus": "莲花街校区",
                  "location": "二楼大厅",
                  "description": "灰色折叠伞",
                  "contact": "QQ: 66778899",
                  "contact_visibility": "PUBLIC",
                  "is_owner": false,
                  "can_manage": false,
                  "viewer_role": "AUTHENTICATED",
                  "comments": []
                }
              ],
              "book_listings": [
                {
                  "id": "mb_w005b_secret",
                  "title": "高等数学教材",
                  "contact": "QQ: 11223344",
                  "status": "AVAILABLE",
                  "is_owner": true,
                  "can_manage": true
                }
              ],
              "anti_lost_tags": [
                {
                  "id": "tag_w005b_secret",
                  "tag_code": "HAUT-TAG-W005B",
                  "item_name": "黑色水杯",
                  "owner_contact": "13900009999",
                  "is_owner": true,
                  "can_manage": true
                }
              ]
            }
            """.trimIndent()
        )
        fixture.dataStore.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS] = "mp_w005b_secret"
            prefs[CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS] = "mb_w005b_secret"
            prefs[CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES] = "HAUT-TAG-W005B"
        }

        val result = repo.refreshCampusMomentsSnapshot()

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        val protectedPost = snapshot.posts.single { it.id == "mp_w005b_secret" }
        val publicPost = snapshot.posts.single { it.id == "mp_w005b_public" }
        assertEquals(cn.edu.haut.campus.core.model.MASKED_ON_REQUEST_MOMENTS_CONTACT, protectedPost.contact)
        assertTrue(protectedPost.hasProtectedContact)
        assertFalse(protectedPost.isOwner)
        assertFalse(protectedPost.canManage)
        assertFalse(protectedPost.canClaimLegacy)
        assertEquals("GUEST", protectedPost.viewerRole)
        assertEquals(0, protectedPost.pendingContactRequestsCount)
        assertFalse(protectedPost.hasMyContactRequest)
        assertEquals("", protectedPost.myContactRequestStatus)
        assertEquals("QQ: 66778899", publicPost.contact)
        assertTrue(publicPost.canViewerReadRealContact)
        val book = snapshot.bookListings.single { it.id == "mb_w005b_secret" }
        assertFalse(book.isOwner)
        assertFalse(book.canManage)
        assertEquals("AVAILABLE", book.status)
        assertTrue(snapshot.antiLostTags.isEmpty())
        assertTrue(snapshot.myPostIds.isEmpty())
        assertTrue(snapshot.myBookIds.isEmpty())
        assertTrue(snapshot.myTagCodes.isEmpty())

        val rawPrefs = fixture.dataStore.data.first()
        val storedJson = rawPrefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON].orEmpty()
        assertFalse(storedJson.contains("secret_wx_w005b"))
        assertFalse(storedJson.contains("HAUT-TAG-W005B"))
        assertFalse(storedJson.contains("13900009999"))
        assertNull(rawPrefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS])
    }

    @Test
    fun w005b_moments_auth_guarded_mutations_fail_without_local_write() = runBlocking {
        writeRawMomentsSnapshotForOfflineTest(
            """
            {
              "updated_at": "2026-11-10T01:30:00Z",
              "posts": [
                {
                  "id": "mp_w005b_guarded",
                  "type": "LOST",
                  "title": "遗失钥匙",
                  "category": "钥匙配饰",
                  "location": "图书馆",
                  "description": "蓝色钥匙扣",
                  "contact": "QQ: 55667788",
                  "status": "OPEN",
                  "comments": []
                }
              ],
              "book_listings": [
                {
                  "id": "mb_w005b_guarded",
                  "title": "线性代数教材",
                  "contact": "QQ: 55667788",
                  "status": "AVAILABLE"
                }
              ]
            }
            """.trimIndent()
        )
        val before = repo.preferencesFlow.first().campusMomentsSnapshot

        val outcomes = listOf(
            repo.toggleMomentsPostResolved("mp_w005b_guarded"),
            repo.deleteMomentsPost("mp_w005b_guarded"),
            repo.toggleMomentsBookSold("mb_w005b_guarded"),
            repo.deleteMomentsBookListing("mb_w005b_guarded")
        )

        assertTrue(outcomes.all { it.isFailure })
        assertTrue(outcomes.all { it.exceptionOrNull()?.message?.contains("登录云端账号") == true })
        assertEquals(before, repo.preferencesFlow.first().campusMomentsSnapshot)
    }
}
