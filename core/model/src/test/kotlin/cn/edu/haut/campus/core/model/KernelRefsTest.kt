package cn.edu.haut.campus.core.model

import cn.edu.haut.campus.core.model.json.Json
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

    @Test(expected = IllegalArgumentException::class)
    fun `实体引用拒绝控制字符`() {
        EntityRef(EntityType.COURSE, "course\u0001", scope)
    }

    @Test
    fun `实体引用接受 256 字符边界`() {
        val id = "a".repeat(256)
        assertEquals(id, EntityRef(EntityType.COURSE, id, scope).id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `实体引用拒绝 257 字符`() {
        EntityRef(EntityType.COURSE, "a".repeat(257), scope)
    }

    @Test
    fun `地点引用按学校校区级图实体身份解析`() {
        val place = PlaceRef(
            id = "haut/lianhuajie/room/5-111",
            schoolId = SchoolId("haut"),
            campusId = CampusId("lianhuajie")
        )

        assertEquals("haut/lianhuajie/room/5-111", place.id)
        assertEquals(SchoolId("haut"), place.schoolId)
        assertEquals(CampusId("lianhuajie"), place.campusId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点引用拒绝学校前缀矛盾`() {
        PlaceRef(
            id = "other-school/lianhuajie/room/5-111",
            schoolId = SchoolId("haut"),
            campusId = CampusId("lianhuajie")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点引用拒绝校区前缀矛盾`() {
        PlaceRef(
            id = "haut/songshanlu/room/5-111",
            schoolId = SchoolId("haut"),
            campusId = CampusId("lianhuajie")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点引用拒绝不符合四段稳定图 ID`() {
        PlaceRef(
            id = "haut/lianhuajie/room",
            schoolId = SchoolId("haut"),
            campusId = CampusId("lianhuajie")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点引用拒绝控制字符`() {
        PlaceRef(
            id = "haut/lianhuajie/room/5-11\u0001",
            schoolId = SchoolId("haut"),
            campusId = CampusId("lianhuajie")
        )
    }

    @Test
    fun `地点引用接受总长 256 字符边界`() {
        val prefix = "haut/lianhuajie/room/"
        val id = prefix + "a".repeat(256 - prefix.length)
        assertEquals(256, id.length)
        assertEquals(id, PlaceRef(id, SchoolId("haut"), CampusId("lianhuajie")).id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地点引用拒绝总长 257 字符`() {
        val prefix = "haut/lianhuajie/room/"
        val id = prefix + "a".repeat(257 - prefix.length)
        assertEquals(257, id.length)
        PlaceRef(id, SchoolId("haut"), CampusId("lianhuajie"))
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

    @Test(expected = IllegalArgumentException::class)
    fun `来源证据拒绝空白 providerId`() {
        SourceRef(EntityRef(EntityType.EXAM, "exam-2026-math", scope), provenance().copy(providerId = "  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `来源证据拒绝空白 sourceSystem`() {
        SourceRef(EntityRef(EntityType.EXAM, "exam-2026-math", scope), provenance().copy(sourceSystem = ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `来源证据拒绝空 sourceRecordId`() {
        SourceRef(EntityRef(EntityType.EXAM, "exam-2026-math", scope), provenance().copy(sourceRecordId = " "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `来源证据拒绝含控制字符 sourceRecordId`() {
        SourceRef(EntityRef(EntityType.EXAM, "exam-2026-math", scope), provenance().copy(sourceRecordId = "exam\u0002"))
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
    fun `修改字段不得用规范等值数字伪造变更`() {
        FieldChange(
            path = "/payload",
            kind = FieldChangeKind.MODIFIED,
            before = Json.parse("""{"a":1.0,"nested":{"x":2.50}}"""),
            after = Json.parse("""{"nested":{"x":2.5},"a":1}""")
        )
    }

    @Test
    fun `修改字段接受 CanonicalJson 语义下真实差异`() {
        val change = FieldChange(
            path = "/payload",
            kind = FieldChangeKind.MODIFIED,
            before = Json.parse("""{"a":1,"nested":{"x":2.5}}"""),
            after = Json.parse("""{"nested":{"x":2.5},"a":2}""")
        )

        assertEquals("/payload", change.path)
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

    @Test
    fun `JSON Pointer 根斜杠是空键合法路径`() {
        val change = FieldChange("/", FieldChangeKind.ADDED, null, JsonValue.Str("empty-key"))
        assertEquals("/", change.path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `字段路径必须以斜杠开始`() {
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

    @Test
    fun `字段差异快照不受 hostile backing array 构造后改写`() {
        val original = FieldChange(
            path = "/room",
            kind = FieldChangeKind.MODIFIED,
            before = JsonValue.Str("W111"),
            after = JsonValue.Str("W112")
        )
        val hostile = HostileBackingArrayList(listOf(original))

        val diff = FieldDiff(hostile)

        hostile.overwrite(
            0,
            FieldChange(
                path = "/injectedAfterConstruction",
                kind = FieldChangeKind.ADDED,
                before = null,
                after = JsonValue.Str("must-not-enter-diff")
            )
        )

        assertEquals(1, diff.changes.size)
        assertEquals("/room", diff.changes.single().path)
        assertEquals(JsonValue.Str("W112"), diff.changes.single().after)
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

/**
 * toArray() 违反约定返回自有 backing array 的自定义 Collection 探针。
 *
 * 主流 JDK/Android 运行时的 ArrayList(Collection) 构造直接采用该数组而不复制，
 * 因此若快照边界退回使用 ArrayList(Collection)，构造后 overwrite 即可改写快照；
 * 逐元素复制的快照边界不消费 toArray() 结果，与运行时版本实现细节无关。
 */
private class HostileBackingArrayList<T>(elements: List<T>) : AbstractList<T>() {
    private val backing: Array<Any?> = arrayOfNulls(elements.size)

    init {
        elements.forEachIndexed { index, element -> backing[index] = element }
    }

    override val size: Int
        get() = backing.size

    override fun get(index: Int): T {
        @Suppress("UNCHECKED_CAST")
        return backing[index] as T
    }

    override fun toArray(): Array<Any?> = backing

    fun overwrite(index: Int, value: T) {
        backing[index] = value
    }
}
