package cn.edu.haut.campus.core.model

import cn.edu.haut.campus.core.model.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelRefsTest {

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )

    @Test
    fun `实体引用保留显式学校账号作用域`() {
        val ref = EntityRef(
            type = EntityType.COURSE,
            id = "course-2026-math-01",
            scope = scope
        )

        assertEquals(EntityType.COURSE, ref.type)
        assertEquals("course-2026-math-01", ref.id)
        assertEquals(scope, ref.scope)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `实体引用拒绝空白不稳定标识`() {
        EntityRef(EntityType.COURSE, "bad id", scope)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `实体引用拒绝空标识`() {
        EntityRef(EntityType.COURSE, "", scope)
    }

    @Test
    fun `地点引用接受校园图分层标识`() {
        val place = PlaceRef(
            id = "haut/lianhuajie/room/5-111",
            scope = scope
        )

        assertEquals("haut/lianhuajie/room/5-111", place.id)
        assertEquals(CampusId("lianhuajie"), place.scope.campusId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点引用必须落在明确校区作用域`() {
        PlaceRef(
            id = "haut/lianhuajie/room/5-111",
            scope = scope.copy(campusId = null)
        )
    }

    @Test
    fun `来源引用同时保留实体身份和来源证据`() {
        val entity = EntityRef(EntityType.EXAM, "exam-2026-math", scope)
        val provenance = provenance()
        val source = SourceRef(entity = entity, provenance = provenance)

        assertEquals(entity, source.entity)
        assertEquals(provenance, source.provenance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `来源证据不得跨账号归因`() {
        val entity = EntityRef(EntityType.EXAM, "exam-2026-math", scope)
        SourceRef(
            entity = entity,
            provenance = provenance().copy(accountId = AccountId("another-account"))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `来源证据不得跨学校归因`() {
        val entity = EntityRef(EntityType.EXAM, "exam-2026-math", scope)
        SourceRef(
            entity = entity,
            provenance = provenance().copy(schoolId = SchoolId("another-school"))
        )
    }

    @Test
    fun `字段差异区分不存在和 JSON null`() {
        val diff = FieldDiff(
            listOf(
                FieldChange(
                    path = "/note",
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = JsonValue.Null
                ),
                FieldChange(
                    path = "/room",
                    kind = FieldChangeKind.MODIFIED,
                    before = JsonValue.Str("W111"),
                    after = JsonValue.Str("W112")
                )
            )
        )

        assertEquals(setOf("/note", "/room"), diff.paths)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `修改字段不得使用相同前后值伪造变更`() {
        FieldChange(
            path = "/room",
            kind = FieldChangeKind.MODIFIED,
            before = JsonValue.Str("W111"),
            after = JsonValue.Str("W111")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `新增字段不得携带旧值`() {
        FieldChange(
            path = "/room",
            kind = FieldChangeKind.ADDED,
            before = JsonValue.Str("W111"),
            after = JsonValue.Str("W112")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `字段差异拒绝重复路径`() {
        FieldDiff(
            listOf(
                FieldChange("/room", FieldChangeKind.ADDED, null, JsonValue.Str("W111")),
                FieldChange("/room", FieldChangeKind.ADDED, null, JsonValue.Str("W112"))
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `字段路径必须是非根 JSON Pointer`() {
        FieldChange("room", FieldChangeKind.ADDED, null, JsonValue.Str("W111"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `字段路径拒绝未按 JSON Pointer 转义的波浪号`() {
        FieldChange("/a~b", FieldChangeKind.ADDED, null, JsonValue.Str("x"))
    }

    @Test
    fun `写操作者集合与总体规划保持一致`() {
        assertEquals(
            listOf(
                Actor.SYNC,
                Actor.USER,
                Actor.AUTOMATION,
                Actor.PLUGIN,
                Actor.RESTORE,
                Actor.INBOX
            ),
            Actor.entries.toList()
        )
    }

    @Test
    fun `变更种类显式区分恢复与新建`() {
        assertTrue(ChangeKind.RESTORED != ChangeKind.CREATED)
        assertEquals(
            listOf(ChangeKind.CREATED, ChangeKind.UPDATED, ChangeKind.DELETED, ChangeKind.RESTORED),
            ChangeKind.entries.toList()
        )
    }

    private fun provenance(): DataProvenance = DataProvenance(
        providerId = "official-academic",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "academic-system",
        sourceRecordId = "exam-2026-math",
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED
    )
}
