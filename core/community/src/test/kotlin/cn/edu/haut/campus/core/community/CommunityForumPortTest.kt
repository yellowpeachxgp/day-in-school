package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommunityForumPortTest {
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account_test"),
        profileId = ProfileId("profile_test"),
    )

    @Test
    fun forumSwitchDefaultsOffAndDisabledPortNeverTouchesInput() = runTest {
        assertEquals("community.forum", CommunityFeatureFlags.FORUM_KEY)
        assertFalse(CommunityFeatureFlags.FORUM_DEFAULT_ENABLED)
        assertEquals(
            FeatureAvailability.Disabled(FeatureDisabledReason.NOT_ENABLED),
            DisabledForumPort.availability.first(),
        )

        val draft = object : PostDraft {}
        val reason = object : ReportReason {}
        val target = EntityRef(EntityType.GROUP, "reserved-target", scope)
        assertEquals(CommunityResult.NotEnabled, DisabledForumPort.listBoards(scope))
        assertEquals(CommunityResult.NotEnabled, DisabledForumPort.listPosts(scope, boardId = "", cursor = "anything"))
        assertEquals(CommunityResult.NotEnabled, DisabledForumPort.createPost(scope, draft, idempotencyKey = ""))
        assertEquals(CommunityResult.NotEnabled, DisabledForumPort.report(scope, target, reason))
    }

    @Test
    fun communityBoardsIsRegisteredAsReservedButNotSupportedRuntimeSlot() {
        assertEquals("community.boards", CommunityReservedExtensionSlots.BOARDS_SLOT_ID)
        val matrix = File("core/school-packages/src/main/resources/runtime-host-api-matrix.json").readText()
        assertTrue(
            "community.boards must be explicitly registered as reserved",
            Regex("\"reserved_ui_slots\"\s*:\s*\[\s*\"community\.boards\"\s*\]").containsMatchIn(matrix),
        )
        val supportedSection = matrix.substringBefore("\"reserved_ui_slots\"")
        assertFalse(
            "reserved must not become a supported/rendering slot",
            supportedSection.contains("community.boards"),
        )
    }

    @Test
    fun forumPortAndReservedSlotHaveNoAndroidUiEntry() {
        val kotlinSources = sequenceOf(File("app/src"), File("feature"))
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(kotlinSources.isNotEmpty())
        val offenders = kotlinSources.filter { file ->
            val text = file.readText()
            text.contains("CommunityForumPort") || text.contains("community.boards")
        }.map { it.path }
        assertEquals("D2 forbids any user-visible forum entry in app/feature", emptyList<String>(), offenders)
    }
}
