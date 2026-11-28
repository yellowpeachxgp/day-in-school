package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * guide 进度快照的白名单 JSON codec。字段、枚举和类型全部显式校验；
 * 未知字段、未来版本、非法时间、重复/越界结构一律 fail-closed。
 */
object GuideProgressCodec {
    const val SCHEMA: String = "dis-guide-progress/v1"

    fun encodeProcedureRun(snapshot: ProcedureRunSnapshot): String {
        val scheduled = LinkedHashMap<String, JsonValue>()
        snapshot.scheduledSteps.forEach { (stepId, schedule) ->
            scheduled[stepId] = JsonValue.Obj(linkedMapOf(
                "startAt" to JsonValue.Str(schedule.startAt.toString()),
                "durationMicros" to (schedule.duration?.let { duration ->
                    JsonValue.Num(
                        durationToWholeMicros(duration)?.toString()
                            ?: throw GuidePersistenceException("procedure 计划时长不能无损编码为微秒")
                    )
                } ?: JsonValue.Null)
            ))
        }
        return Json.canonical(JsonValue.Obj(linkedMapOf(
            "schema" to JsonValue.Str(SCHEMA),
            "kind" to JsonValue.Str("procedure-run"),
            "scope" to JsonValue.Obj(scopeFields(snapshot.scope)),
            "packId" to JsonValue.Str(snapshot.packId),
            "itemId" to JsonValue.Str(snapshot.itemId),
            "packVersion" to JsonValue.Str(snapshot.packVersion),
            "artifactSha256" to JsonValue.Str(snapshot.artifactSha256),
            "answers" to JsonValue.Obj(snapshot.answers.mapValues { JsonValue.Str(it.value) }),
            "completedStepIds" to JsonValue.Arr(snapshot.completedStepIds.map { JsonValue.Str(it) }),
            "scheduledSteps" to JsonValue.Obj(scheduled),
            "createdAt" to JsonValue.Str(snapshot.createdAt.toString()),
            "updatedAt" to JsonValue.Str(snapshot.updatedAt.toString()),
            "revision" to JsonValue.Num(snapshot.revision.toString())
        )))
    }

    fun decodeProcedureRun(text: String): ProcedureRunSnapshot {
        val root = expectObj(parse(text), "$")
        expectExactKeys(root, "$", setOf(
            "schema", "kind", "scope", "packId", "itemId", "packVersion", "artifactSha256",
            "answers", "completedStepIds", "scheduledSteps", "createdAt", "updatedAt", "revision"
        ))
        expectFixedString(root.fields["schema"], "$.schema", SCHEMA)
        expectFixedString(root.fields["kind"], "$.kind", "procedure-run")
        val answersObject = expectObj(root.fields["answers"], "$.answers")
        val answers = LinkedHashMap<String, String>()
        answersObject.fields.forEach { (key, value) ->
            answers[key] = expectString(value, "$.answers." + key)
        }
        val completed = expectArray(root.fields["completedStepIds"], "$.completedStepIds").items.mapIndexed { index, value ->
            expectString(value, "$.completedStepIds[" + index + "]")
        }
        val scheduledObject = expectObj(root.fields["scheduledSteps"], "$.scheduledSteps")
        val scheduled = LinkedHashMap<String, ScheduledStep>()
        scheduledObject.fields.forEach { (stepId, value) ->
            val path = "$.scheduledSteps." + stepId
            val obj = expectObj(value, path)
            expectExactKeys(obj, path, setOf("startAt", "durationMicros"))
            val durationValue = obj.fields["durationMicros"]
            val durationMicros = if (durationValue is JsonValue.Null) null else expectLong(durationValue, path + ".durationMicros")
            if (durationMicros != null && durationMicros <= 0) {
                throw GuidePersistenceException(path + ".durationMicros 必须为正")
            }
            scheduled[stepId] = ScheduledStep(
                startAt = expectInstant(obj.fields["startAt"], path + ".startAt"),
                duration = durationMicros?.let { micros ->
                    Duration.ofSeconds(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L)
                }
            )
        }
        return try {
            ProcedureRunSnapshot(
                scope = decodeScope(expectObj(root.fields["scope"], "$.scope")),
                packId = expectString(root.fields["packId"], "$.packId"),
                itemId = expectString(root.fields["itemId"], "$.itemId"),
                packVersion = expectString(root.fields["packVersion"], "$.packVersion"),
                artifactSha256 = expectString(root.fields["artifactSha256"], "$.artifactSha256"),
                answers = answers,
                completedStepIds = completed,
                scheduledSteps = scheduled,
                createdAt = expectInstant(root.fields["createdAt"], "$.createdAt"),
                updatedAt = expectInstant(root.fields["updatedAt"], "$.updatedAt"),
                revision = expectLong(root.fields["revision"], "$.revision")
            )
        } catch (error: IllegalArgumentException) {
            throw GuidePersistenceException("procedure 进度快照 invariant 不满足：" + error.message)
        }
    }

    fun encodeOpportunityProgress(snapshot: OpportunityProgressSnapshot): String {
        val facts = LinkedHashMap<String, JsonValue>()
        snapshot.facts.forEach { (requirementId, fact) ->
            facts[requirementId] = JsonValue.Obj(linkedMapOf(
                "satisfaction" to JsonValue.Str(fact.satisfaction.name),
                "source" to JsonValue.Str(fact.source.name),
                "note" to (fact.note?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "recordedAt" to (fact.recordedAt?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null)
            ))
        }
        val materials = LinkedHashMap<String, JsonValue>()
        snapshot.completedMaterials.forEach { (index, at) ->
            materials[index.toString()] = JsonValue.Str(at.toString())
        }
        return Json.canonical(JsonValue.Obj(linkedMapOf(
            "schema" to JsonValue.Str(SCHEMA),
            "kind" to JsonValue.Str("opportunity-progress"),
            "scope" to JsonValue.Obj(scopeFields(snapshot.scope)),
            "packId" to JsonValue.Str(snapshot.packId),
            "itemId" to JsonValue.Str(snapshot.itemId),
            "packVersion" to JsonValue.Str(snapshot.packVersion),
            "artifactSha256" to JsonValue.Str(snapshot.artifactSha256),
            "facts" to JsonValue.Obj(facts),
            "completedMaterials" to JsonValue.Obj(materials),
            "override" to encodeOverride(snapshot.eligibilityOverride),
            "applicationStatus" to JsonValue.Str(snapshot.applicationStatus.name),
            "createdAt" to JsonValue.Str(snapshot.createdAt.toString()),
            "updatedAt" to JsonValue.Str(snapshot.updatedAt.toString()),
            "revision" to JsonValue.Num(snapshot.revision.toString())
        )))
    }

    fun decodeOpportunityProgress(text: String): OpportunityProgressSnapshot {
        val root = expectObj(parse(text), "$")
        expectExactKeys(root, "$", setOf(
            "schema", "kind", "scope", "packId", "itemId", "packVersion", "artifactSha256",
            "facts", "completedMaterials", "override", "applicationStatus", "createdAt", "updatedAt", "revision"
        ))
        expectFixedString(root.fields["schema"], "$.schema", SCHEMA)
        expectFixedString(root.fields["kind"], "$.kind", "opportunity-progress")
        val factsObject = expectObj(root.fields["facts"], "$.facts")
        val facts = LinkedHashMap<String, OpportunityRequirementFact>()
        factsObject.fields.forEach { (requirementId, value) ->
            val path = "$.facts." + requirementId
            val obj = expectObj(value, path)
            expectExactKeys(obj, path, setOf("satisfaction", "source", "note", "recordedAt"))
            facts[requirementId] = OpportunityRequirementFact(
                satisfaction = expectEnum(obj.fields["satisfaction"], path + ".satisfaction"),
                source = expectEnum(obj.fields["source"], path + ".source"),
                note = expectNullableString(obj.fields["note"], path + ".note"),
                recordedAt = expectNullableInstant(obj.fields["recordedAt"], path + ".recordedAt")
            )
        }
        val materialObject = expectObj(root.fields["completedMaterials"], "$.completedMaterials")
        val materials = LinkedHashMap<Int, Instant>()
        materialObject.fields.forEach { (indexRaw, value) ->
            val index = indexRaw.toIntOrNull()
                ?: throw GuidePersistenceException("opportunity 材料下标不是非负整数：" + indexRaw)
            if (index < 0 || indexRaw != index.toString()) {
                throw GuidePersistenceException("opportunity 材料下标必须是非负规范整数：" + indexRaw)
            }
            if (materials.put(index, expectInstant(value, "$.completedMaterials." + indexRaw)) != null) {
                throw GuidePersistenceException("opportunity 材料下标重复：" + indexRaw)
            }
        }
        return try {
            OpportunityProgressSnapshot(
                scope = decodeScope(expectObj(root.fields["scope"], "$.scope")),
                packId = expectString(root.fields["packId"], "$.packId"),
                itemId = expectString(root.fields["itemId"], "$.itemId"),
                packVersion = expectString(root.fields["packVersion"], "$.packVersion"),
                artifactSha256 = expectString(root.fields["artifactSha256"], "$.artifactSha256"),
                facts = facts,
                completedMaterials = materials,
                eligibilityOverride = decodeOverride(root.fields["override"]),
                applicationStatus = expectEnum(root.fields["applicationStatus"], "$.applicationStatus"),
                createdAt = expectInstant(root.fields["createdAt"], "$.createdAt"),
                updatedAt = expectInstant(root.fields["updatedAt"], "$.updatedAt"),
                revision = expectLong(root.fields["revision"], "$.revision")
            )
        } catch (error: IllegalArgumentException) {
            throw GuidePersistenceException("opportunity 进度快照 invariant 不满足：" + error.message)
        }
    }

    private fun scopeFields(scope: SchoolAccountScope): Map<String, JsonValue> = linkedMapOf(
        "schoolId" to JsonValue.Str(scope.schoolId.value),
        "accountId" to JsonValue.Str(scope.accountId.value),
        "profileId" to JsonValue.Str(scope.profileId.value),
        "campusId" to (scope.campusId?.let { JsonValue.Str(it.value) } ?: JsonValue.Null)
    )

    private fun decodeScope(obj: JsonValue.Obj): SchoolAccountScope {
        expectExactKeys(obj, "$.scope", setOf("schoolId", "accountId", "profileId", "campusId"))
        return SchoolAccountScope(
            schoolId = SchoolId(expectString(obj.fields["schoolId"], "$.scope.schoolId")),
            accountId = AccountId(expectString(obj.fields["accountId"], "$.scope.accountId")),
            profileId = ProfileId(expectString(obj.fields["profileId"], "$.scope.profileId")),
            campusId = expectNullableString(obj.fields["campusId"], "$.scope.campusId")?.let(::CampusId)
        )
    }

    private fun encodeOverride(manualOverride: ManualEligibilityOverride?): JsonValue {
        if (manualOverride == null) return JsonValue.Null
        return JsonValue.Obj(linkedMapOf(
            "state" to JsonValue.Str(manualOverride.state.name),
            "reason" to JsonValue.Str(manualOverride.reason),
            "at" to JsonValue.Str(manualOverride.at.toString())
        ))
    }

    private fun decodeOverride(value: JsonValue?): ManualEligibilityOverride? {
        if (value is JsonValue.Null) return null
        val obj = expectObj(value, "$.override")
        expectExactKeys(obj, "$.override", setOf("state", "reason", "at"))
        return ManualEligibilityOverride(
            state = expectEnum(obj.fields["state"], "$.override.state"),
            reason = expectString(obj.fields["reason"], "$.override.reason"),
            at = expectInstant(obj.fields["at"], "$.override.at")
        )
    }

    private fun parse(text: String): JsonValue = try {
        Json.parse(text)
    } catch (error: IllegalArgumentException) {
        throw GuidePersistenceException("guide 进度快照不是合法 JSON：" + error.message)
    }

    private fun expectObj(value: JsonValue?, path: String): JsonValue.Obj =
        value as? JsonValue.Obj ?: throw GuidePersistenceException(path + " 必须是对象")

    private fun expectArray(value: JsonValue?, path: String): JsonValue.Arr =
        value as? JsonValue.Arr ?: throw GuidePersistenceException(path + " 必须是数组")

    private fun expectString(value: JsonValue?, path: String): String =
        (value as? JsonValue.Str)?.value ?: throw GuidePersistenceException(path + " 必须是字符串")

    private fun expectNullableString(value: JsonValue?, path: String): String? = when (value) {
        is JsonValue.Null -> null
        else -> expectString(value, path)
    }

    private fun expectFixedString(value: JsonValue?, path: String, expected: String) {
        if (expectString(value, path) != expected) {
            throw GuidePersistenceException(path + " 必须是 " + expected)
        }
    }

    private fun expectInstant(value: JsonValue?, path: String): Instant {
        val raw = expectString(value, path)
        return try {
            Instant.parse(raw)
        } catch (error: DateTimeParseException) {
            throw GuidePersistenceException(path + " 必须是 ISO Instant")
        }
    }

    private fun expectNullableInstant(value: JsonValue?, path: String): Instant? = when (value) {
        is JsonValue.Null -> null
        else -> expectInstant(value, path)
    }

    private fun expectLong(value: JsonValue?, path: String): Long {
        val literal = (value as? JsonValue.Num)?.literal
            ?: throw GuidePersistenceException(path + " 必须是整数")
        if (!Regex("^-?(0|[1-9][0-9]*)$").matches(literal)) {
            throw GuidePersistenceException(path + " 必须是整数")
        }
        return literal.toLongOrNull() ?: throw GuidePersistenceException(path + " 整数越界")
    }

    private inline fun <reified T : Enum<T>> expectEnum(value: JsonValue?, path: String): T {
        val raw = expectString(value, path)
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw GuidePersistenceException(path + " 包含未知枚举值：" + raw)
    }

    private fun expectExactKeys(obj: JsonValue.Obj, path: String, expected: Set<String>) {
        if (obj.fields.keys != expected) {
            val missing = expected - obj.fields.keys
            val unknown = obj.fields.keys - expected
            throw GuidePersistenceException(
                path + " 字段集合不匹配；缺少=" + missing.sorted() + "，未知=" + unknown.sorted()
            )
        }
    }
}
