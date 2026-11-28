package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineSource
import cn.edu.haut.campus.core.timeline.TimelineTextSafety
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val GUIDE_TITLE_METADATA_KEY = "guide.title"

/**
 * guide → Timeline 投影。只投影用户显式追踪/计划的数据：
 * procedure 必须有用户登记的开始时间；opportunity deadline 只在已有进度记录时出现。
 * 没有正时长的步骤使用 UNCERTAIN 锚点，deadline 使用 DEADLINE 点事件，二者绝不互相伪装。
 */
class GuideTimelineSource(
    private val guidePackages: List<GuidePackage>,
    private val store: GuideProgressStore,
    private val zone: ZoneId
) : TimelineSource {

    override val id: String = "guide"
    override val kinds: Set<TimelineKind> = setOf(
        TimelineKind.PROCEDURE_STEP,
        TimelineKind.OPPORTUNITY_MILESTONE
    )

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>> =
        combine(store.observeProcedureRuns(scope), store.observeOpportunityProgress(scope)) { runs, opportunities ->
            project(scope, window, runs, opportunities)
        }

    private fun project(
        scope: SchoolAccountScope,
        window: TimeWindow,
        runs: List<ProcedureRunSnapshot>,
        opportunities: List<OpportunityProgressSnapshot>
    ): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()
        runs.forEach { run -> events += procedureEvents(scope, run) }
        opportunities.forEach { progress -> opportunityEvent(scope, progress)?.let(events::add) }
        return events.filter { it.overlaps(window) }.sortedWith(
            compareBy<TimelineEvent> { it.start }.thenBy { it.id.value }
        )
    }

    private fun procedureEvents(scope: SchoolAccountScope, run: ProcedureRunSnapshot): List<TimelineEvent> {
        if (run.scope != scope) throw GuideTimelineProjectionException("procedure 进度跨 scope 流入 Timeline")
        val pack = packFor(run.packId)
        requireArtifact(pack, run.packVersion, run.artifactSha256, "procedure")
        val item = pack.procedureOrNull(run.itemId)
            ?: throw GuideTimelineProjectionException("procedure 进度引用的条目不在当前已验签包中：" + run.itemId)
        val evaluation = ProcedurePathEvaluator.evaluate(item, run.answers, run.completedStepIds)
        val routeSteps = evaluation.path.steps.associateBy { it.id }
        val provenance = provenance(scope, pack, item.id)
        val entity = EntityRef(EntityType.PROCEDURE, stableEntityId("procedure", run.packId, run.itemId), scope)
        return run.scheduledSteps.mapNotNull { (stepId, schedule) ->
            val step = routeSteps[stepId] ?: return@mapNotNull null
            val duration = schedule.duration ?: step.estimatedMinutes?.takeIf { it > 0 }?.let { minutes ->
                Duration.ofMinutes(minutes.toLong())
            }
            val end = duration?.let { schedule.startAt.plus(it) }
            val timed = end != null
            val stepDigest = Digests.sha256Hex(stepId)
            val safeTitle = TimelineTextSafety.safeTimelineTitle(step.title, blankFallback = "流程步骤")
            TimelineEvent(
                id = TimelineEventId("guide:procedure/" + stableRunDigest(run.packId, run.itemId) + "/" + stepDigest),
                scope = scope,
                kind = TimelineKind.PROCEDURE_STEP,
                title = safeTitle,
                start = schedule.startAt,
                end = end,
                timeSemantics = if (timed) TimelineTimeSemantics.TIMED else TimelineTimeSemantics.UNCERTAIN,
                zone = zone,
                place = step.placeRef?.let { GuidePlaceRefs.parse(it, SchoolId(pack.schoolId), "流程步骤 placeRef") },
                source = SourceRef(entity, provenance),
                provenance = provenance,
                status = if (step.id in run.completedStepIds) EventStatus.DONE else EventStatus.SCHEDULED,
                busy = if (timed) BusyLevel.TENTATIVE else BusyLevel.FREE,
                revision = run.revision,
                links = listOf(entity),
                sourceMetadata = metadataWithLosslessTitle(
                    linkedMapOf(
                        "packId" to run.packId,
                        "itemId" to run.itemId,
                        "stepId" to stepId,
                        "artifactSha256" to run.artifactSha256,
                        "pathComplete" to evaluation.path.complete.toString()
                    ),
                    rawTitle = step.title,
                    safeTitle = safeTitle
                )
            )
        }
    }

    private fun opportunityEvent(scope: SchoolAccountScope, progress: OpportunityProgressSnapshot): TimelineEvent? {
        if (progress.scope != scope) throw GuideTimelineProjectionException("opportunity 进度跨 scope 流入 Timeline")
        if (progress.applicationStatus == OpportunityApplicationStatus.REJECTED ||
            progress.applicationStatus == OpportunityApplicationStatus.WITHDRAWN ||
            progress.applicationStatus == OpportunityApplicationStatus.ARCHIVED
        ) {
            return null
        }
        val pack = packFor(progress.packId)
        requireArtifact(pack, progress.packVersion, progress.artifactSha256, "opportunity")
        val item = pack.opportunityOrNull(progress.itemId)
            ?: throw GuideTimelineProjectionException("opportunity 进度引用的条目不在当前已验签包中：" + progress.itemId)
        val deadlineText = item.deadlineAt ?: return null
        val deadline = try {
            OffsetDateTime.parse(deadlineText).toInstant()
        } catch (error: java.time.format.DateTimeParseException) {
            throw GuideTimelineProjectionException("opportunity deadlineAt 非法，拒绝投影：" + item.id)
        }
        val provenance = provenance(scope, pack, item.id)
        val entity = EntityRef(EntityType.OPPORTUNITY, stableEntityId("opportunity", progress.packId, progress.itemId), scope)
        val done = progress.applicationStatus == OpportunityApplicationStatus.SUBMITTED ||
            progress.applicationStatus == OpportunityApplicationStatus.ACCEPTED
        val rawTitle = item.title + " 截止"
        val safeTitle = TimelineTextSafety.safeTimelineTitle(rawTitle, blankFallback = "机会截止")
        return TimelineEvent(
            id = TimelineEventId("guide:opportunity/" + stableRunDigest(progress.packId, progress.itemId) + "/deadline"),
            scope = scope,
            kind = TimelineKind.OPPORTUNITY_MILESTONE,
            title = safeTitle,
            start = deadline,
            end = null,
            timeSemantics = TimelineTimeSemantics.DEADLINE,
            zone = zone,
            place = null,
            source = SourceRef(entity, provenance),
            provenance = provenance,
            status = if (done) EventStatus.DONE else EventStatus.SCHEDULED,
            busy = BusyLevel.FREE,
            revision = progress.revision,
            links = listOf(entity),
            sourceMetadata = metadataWithLosslessTitle(
                linkedMapOf(
                    "packId" to progress.packId,
                    "itemId" to progress.itemId,
                    "artifactSha256" to progress.artifactSha256,
                    "applicationStatus" to progress.applicationStatus.name,
                    "deadlineAt" to deadlineText
                ),
                rawTitle = rawTitle,
                safeTitle = safeTitle
            )
        )
    }

    private fun metadataWithLosslessTitle(
        base: Map<String, String>,
        rawTitle: String,
        safeTitle: String
    ): Map<String, String> = buildMap {
        putAll(base)
        if (safeTitle != rawTitle) {
            TimelineTextSafety.encodeLosslessTextFields(this, listOf(GUIDE_TITLE_METADATA_KEY to rawTitle))
        }
    }

    private fun packFor(packId: String): GuidePackage = guidePackages.firstOrNull { it.packId == packId }
        ?: throw GuideTimelineProjectionException("进度引用的 guide 数据包未加载：" + packId)

    private fun requireArtifact(pack: GuidePackage, version: String, artifactSha256: String, label: String) {
        if (pack.version != version || pack.artifactSha256 != artifactSha256) {
            throw GuideTimelineProjectionException(label + " 进度与当前 artifact 不一致，拒绝投影")
        }
    }

    private fun provenance(scope: SchoolAccountScope, pack: GuidePackage, itemId: String): DataProvenance {
        val metadata = pack.metadataFor(itemId)
        return DataProvenance(
            providerId = "dis-datapack",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "core-guide",
            sourceRecordId = pack.packId + "/" + itemId,
            completeness = SnapshotCompleteness.COMPLETE,
            trust = metadata.verificationLevel,
            schemaVersion = 1,
            contentRevision = pack.version + "@" + pack.artifactSha256
        )
    }

    private fun stableRunDigest(packId: String, itemId: String): String = Digests.sha256Hex(packId, itemId)

    private fun stableEntityId(kind: String, packId: String, itemId: String): String =
        kind + "/" + stableRunDigest(packId, itemId)
}
