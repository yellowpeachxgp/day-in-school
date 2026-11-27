package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.time.Instant

/** 课程搜索贡献者：别名只来自 R01 的可审查显式表。 */
class CourseSearchIndexContributor(
    database: HautDatabase
) : SearchIndexContributor {
    private val dao = database.campusDao()
    override val type: EntityType = EntityType.COURSE

    override suspend fun fullRebuild(scope: SchoolAccountScope): Sequence<SearchDocument> =
        dao.getCoursesSnapshot().sortedBy { it.id }.asSequence().map { row ->
            SearchDocument(
                ref = EntityRef(EntityType.COURSE, row.id, scope),
                type = EntityType.COURSE,
                title = row.name,
                body = listOfNotNull(row.teacher, row.creditPlain?.let { it + " 学分" }).joinToString(" "),
                code = row.externalId,
                aliases = CourseAliasRules.aliasesFor(row.name),
                deeplink = "dayinschool://course/" + row.id,
                facets = mapOf("source" to row.source)
            )
        }
}

/** 成绩 attempt 搜索贡献者。 */
class GradeAttemptSearchIndexContributor(
    database: HautDatabase
) : SearchIndexContributor {
    private val dao = database.campusDao()
    override val type: EntityType = EntityType.GRADE_ATTEMPT

    override suspend fun fullRebuild(scope: SchoolAccountScope): Sequence<SearchDocument> =
        dao.getGradeAttemptsSnapshot().sortedBy { it.id }.asSequence().map { row ->
            SearchDocument(
                ref = EntityRef(EntityType.GRADE_ATTEMPT, row.id, scope),
                type = EntityType.GRADE_ATTEMPT,
                title = row.name,
                body = listOf(row.category, row.rawGrade, row.attemptType).joinToString(" "),
                code = row.courseKey,
                aliases = CourseAliasRules.aliasesFor(row.name),
                termId = row.termId,
                at = Instant.ofEpochMilli(row.syncedAtEpochMilli),
                deeplink = "dayinschool://grade/" + row.id,
                facets = mapOf("termId" to row.termId, "source" to row.source)
            )
        }
}

/** 考试安排搜索贡献者；教室/楼室文本进入 body，支撑 W111 等编码检索。 */
class ExamScheduleSearchIndexContributor(
    database: HautDatabase
) : SearchIndexContributor {
    private val dao = database.campusDao()
    override val type: EntityType = EntityType.EXAM

    override suspend fun fullRebuild(scope: SchoolAccountScope): Sequence<SearchDocument> =
        dao.getExamSchedulesSnapshot().sortedBy { it.id }.asSequence().map { row ->
            SearchDocument(
                ref = EntityRef(EntityType.EXAM, row.id, scope),
                type = EntityType.EXAM,
                title = row.courseName,
                body = listOf(
                    row.examBatchName,
                    row.examStageLabel,
                    row.examFormat,
                    row.rawTimeText,
                    row.rawRoom,
                    row.campusName
                ).joinToString(" "),
                code = row.courseCode,
                aliases = CourseAliasRules.aliasesFor(row.courseName),
                termId = row.termId,
                at = Instant.ofEpochMilli(row.syncedAtEpochMilli),
                deeplink = "dayinschool://exam/" + row.id,
                facets = mapOf("termId" to row.termId, "campusName" to row.campusName)
            )
        }
}

/** personal_events 搜索贡献者；行 ID 即 TimelineEventId 的稳定字符串。 */
class PersonalEventSearchIndexContributor(
    database: HautDatabase
) : SearchIndexContributor {
    private val dao = database.vaultDao()
    override val type: EntityType = EntityType.PERSONAL_EVENT

    override suspend fun fullRebuild(scope: SchoolAccountScope): Sequence<SearchDocument> =
        dao.getPersonalEventsSnapshot().sortedBy { it.id }.asSequence().map { row ->
            SearchDocument(
                ref = EntityRef(EntityType.PERSONAL_EVENT, row.id, scope),
                type = EntityType.PERSONAL_EVENT,
                title = row.title,
                body = listOfNotNull(row.note, row.placeText).joinToString(" "),
                code = null,
                aliases = emptyList(),
                termId = row.termId,
                at = Instant.ofEpochMilli(row.startAtEpochMs),
                deeplink = "dayinschool://personal-event/" + row.id,
                facets = mapOf("kind" to row.kind, "status" to row.status)
            )
        }
}
