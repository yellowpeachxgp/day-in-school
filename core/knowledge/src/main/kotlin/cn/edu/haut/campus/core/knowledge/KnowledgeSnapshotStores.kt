package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.vault.EntitySnapshotStore
import java.time.Instant

/** course_materials 的过渡 Vault snapshot store；物理版本在 blob_store，逻辑快照保持 <=64 KiB。 */
internal class CourseMaterialSnapshotStore(
    private val documents: VersionedBlobDocumentStore,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now
) : EntitySnapshotStore {
    override val type: EntityType = EntityType.COURSE_MATERIAL

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        requireValidRef(ref)
        val document = documents.readDocument(ref.id) ?: return null
        if (isDeletedDocument(document)) return null
        return CanonicalJson.of(canonicalJson(document))
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        requireValidRef(ref)
        if (snapshot == null) {
            val current = documents.readDocument(ref.id)
            val revision = current?.nullableLong("revision") ?: 0L
            documents.writeDocument(
                ownerId = ref.id,
                document = jsonObjectOf(
                    "format" to JsonValue.Str(COURSE_MATERIAL_FORMAT),
                    "deleted" to jsonBool(true),
                    "id" to JsonValue.Str(ref.id),
                    "scope" to encodeScope(scope),
                    "revision" to JsonValue.Num((revision + 1L).toString()),
                    "updatedAt" to JsonValue.Str(clock().toString())
                )
            )
            return
        }
        val obj = Json.parse(snapshot.text).asObjOrNull
            ?: throw IllegalArgumentException("课程资料快照必须是 object")
        require(obj.requiredString("id") == ref.id) { "课程资料快照 id 与 EntityRef 不一致" }
        require(decodeScope(obj.requiredField("scope")) == ref.scope) { "课程资料快照 scope 与 EntityRef 不一致" }
        documents.writeDocument(ref.id, obj)
    }

    private fun requireValidRef(ref: EntityRef) {
        require(ref.type == type) { "CourseMaterialSnapshotStore 只能处理 COURSE_MATERIAL" }
        require(ref.scope == scope) { "CourseMaterialSnapshotStore 只能处理本 profile scope" }
    }
}

/** review_tasks 的过渡 Vault snapshot store；W3-05 排程前不引入额外运行时语义。 */
internal class ReviewTaskSnapshotStore(
    private val documents: VersionedBlobDocumentStore,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now
) : EntitySnapshotStore {
    override val type: EntityType = EntityType.REVIEW_ITEM

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        requireValidRef(ref)
        val document = documents.readDocument(ref.id) ?: return null
        if (isDeletedDocument(document)) return null
        return CanonicalJson.of(canonicalJson(document))
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        requireValidRef(ref)
        if (snapshot == null) {
            val current = documents.readDocument(ref.id)
            val revision = current?.nullableLong("revision") ?: 0L
            documents.writeDocument(
                ownerId = ref.id,
                document = jsonObjectOf(
                    "format" to JsonValue.Str(REVIEW_TASK_FORMAT),
                    "deleted" to jsonBool(true),
                    "id" to JsonValue.Str(ref.id),
                    "scope" to encodeScope(scope),
                    "revision" to JsonValue.Num((revision + 1L).toString()),
                    "updatedAt" to JsonValue.Str(clock().toString())
                )
            )
            return
        }
        val obj = Json.parse(snapshot.text).asObjOrNull
            ?: throw IllegalArgumentException("复习任务快照必须是 object")
        require(obj.requiredString("id") == ref.id) { "复习任务快照 id 与 EntityRef 不一致" }
        require(decodeScope(obj.requiredField("scope")) == ref.scope) { "复习任务快照 scope 与 EntityRef 不一致" }
        documents.writeDocument(ref.id, obj)
    }

    private fun requireValidRef(ref: EntityRef) {
        require(ref.type == type) { "ReviewTaskSnapshotStore 只能处理 REVIEW_ITEM" }
        require(ref.scope == scope) { "ReviewTaskSnapshotStore 只能处理本 profile scope" }
    }
}
