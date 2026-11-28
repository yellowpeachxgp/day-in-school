package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalEventStore
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.ZoneId

/** 本地通知端口。implementation 必须用 actionExecutionKey 派生稳定通知 ID，重试只做同 ID 更新。 */
interface LocalNotificationActionPort {
    suspend fun post(
        scope: SchoolAccountScope,
        actionExecutionKey: String,
        title: String,
        body: String,
        channel: String?,
        deeplink: String?
    ): Boolean
}

/** 首页卡片/小组件刷新端口；刷新本身必须无并发副作用，重复调用只产生等价刷新。 */
interface HomeRefreshActionPort {
    suspend fun refresh(scope: SchoolAccountScope, reason: String): Boolean
}

class LocalNotificationActionExecutor(
    private val port: LocalNotificationActionPort?
) : ActionExecutor {
    override val type: String = BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION
    override val risk: ActionRisk = ActionRisk.LOCAL_NOTIFY
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"required":["title","body"],"properties":{"title":{"type":"string","minLength":1,"maxLength":80},"body":{"type":"string","minLength":1,"maxLength":240},"channel":{"type":"string","minLength":1,"maxLength":64},"deeplink":{"type":"string","minLength":1,"maxLength":512}}}"""
    )

    override suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome {
        val actionKey = requireNotNull(ctx.actionExecutionKey) { "通知动作缺少稳定 actionExecutionKey" }
        val title = spec.config.str("title").orEmpty()
        val body = spec.config.str("body").orEmpty()
        val posted = port?.post(
            scope = ctx.scope,
            actionExecutionKey = actionKey,
            title = title,
            body = body,
            channel = spec.config.str("channel"),
            deeplink = spec.config.str("deeplink")
        ) == true
        return ActionOutcome(
            actionType = type,
            risk = risk,
            status = if (posted) ActionOutcomeStatus.SUCCESS else ActionOutcomeStatus.FAILED,
            detail = if (posted) "本地通知已按稳定动作键投递/更新" else "本地通知投递失败或未授权",
            evidence = listOf(Evidence(source = "local-notification", detail = "posted=" + posted, at = ctx.now))
        )
    }
}

/** 创建待办截止或本地日程。所有时间相对 trigger firedAt 而不是重试时刻，保证动作级原子幂等。 */
class CreatePersonalEventActionExecutor(
    private val store: PersonalEventStore?,
    private val zoneProvider: (RunContext) -> ZoneId
) : ActionExecutor {
    override val type: String = BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT
    override val risk: ActionRisk = ActionRisk.LOCAL_SILENT
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"required":["kind","title"],"properties":{"kind":{"type":"string","enum":["PERSONAL","TASK_DUE"]},"title":{"type":"string","minLength":1,"maxLength":120},"note":{"type":"string","maxLength":512},"startInMinutes":{"type":"integer","minimum":0,"maximum":10080},"dueInMinutes":{"type":"integer","minimum":0,"maximum":10080},"durationMinutes":{"type":"integer","minimum":1,"maximum":1440},"placeText":{"type":"string","maxLength":120}}}"""
    )

    override suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome {
        val personalStore = store ?: return ActionOutcome(
            actionType = type,
            risk = risk,
            status = ActionOutcomeStatus.FAILED,
            detail = "缺少 personal_events 写入端口"
        )
        val actionKey = requireNotNull(ctx.actionExecutionKey) { "创建日程动作缺少稳定 actionExecutionKey" }
        val kind = TimelineKind.valueOf(spec.config.str("kind") ?: TimelineKind.TASK_DUE.name)
        val title = spec.config.str("title").orEmpty()
        val zone = zoneProvider(ctx)
        val start = if (kind == TimelineKind.TASK_DUE) {
            ctx.firedAt.plusSeconds((spec.config.obj("dueInMinutes")?.intOrNull() ?: 60).toLong() * 60L)
        } else {
            ctx.firedAt.plusSeconds((spec.config.obj("startInMinutes")?.intOrNull() ?: 0).toLong() * 60L)
        }
        val end = if (kind == TimelineKind.TASK_DUE) {
            null
        } else {
            start.plusSeconds((spec.config.obj("durationMinutes")?.intOrNull() ?: 60).toLong() * 60L)
        }
        val timelineId = TimelineEventId(
            PersonalEventDraft.PERSONAL_TIMELINE_SOURCE + ":auto-" + AutomationKeys.sha256Hex(actionKey).substring(0, 32)
        )
        val provenance = DataProvenance(
            providerId = "core.automation",
            schoolId = ctx.scope.schoolId,
            accountId = ctx.scope.accountId,
            sourceSystem = "automation-rule",
            sourceRecordId = actionKey,
            fetchedAt = ctx.firedAt,
            completeness = SnapshotCompleteness.COMPLETE,
            trust = VerificationLevel.SOURCE_CONFIRMED,
            schemaVersion = 1
        )
        val ruleRef = EntityRef(EntityType.AUTOMATION_RULE, ctx.rule.id, ctx.scope)
        val draft = PersonalEventDraft(
            id = timelineId,
            scope = ctx.scope,
            kind = kind,
            title = title,
            note = spec.config.str("note"),
            start = start,
            end = end,
            timeSemantics = if (end == null) TimelineTimeSemantics.DEADLINE else TimelineTimeSemantics.TIMED,
            zone = zone,
            placeText = spec.config.str("placeText"),
            status = EventStatus.SCHEDULED,
            busy = if (kind == TimelineKind.TASK_DUE) BusyLevel.FREE else BusyLevel.BUSY,
            provenance = provenance,
            sourceEntity = ruleRef,
            links = listOf(ruleRef)
        )
        val writtenId = personalStore.upsert(draft, Actor.AUTOMATION)
        return ActionOutcome(
            actionType = type,
            risk = risk,
            status = ActionOutcomeStatus.SUCCESS,
            detail = if (kind == TimelineKind.TASK_DUE) "待办已按稳定动作键创建/更新" else "日程已按稳定动作键创建/更新",
            evidence = listOf(Evidence(source = "personal-event-store", detail = "id=" + writtenId.value, at = ctx.now))
        )
    }
}

class RefreshHomeActionExecutor(
    private val port: HomeRefreshActionPort?
) : ActionExecutor {
    override val type: String = BuiltinAutomationTypes.ACTION_REFRESH_HOME
    override val risk: ActionRisk = ActionRisk.LOCAL_SILENT
    override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT

    override suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome {
        val refreshed = port?.refresh(ctx.scope, "automation:" + ctx.rule.id) == true
        return ActionOutcome(
            actionType = type,
            risk = risk,
            status = if (refreshed) ActionOutcomeStatus.SUCCESS else ActionOutcomeStatus.FAILED,
            detail = if (refreshed) "首页卡片与小组件刷新已请求" else "首页卡片与小组件刷新请求失败",
            evidence = listOf(Evidence(source = "home-refresh", detail = "refreshed=" + refreshed, at = ctx.now))
        )
    }
}

class BuiltinActionExecutors(
    notificationPort: LocalNotificationActionPort?,
    personalEventStore: PersonalEventStore?,
    refreshPort: HomeRefreshActionPort?,
    zoneProvider: (RunContext) -> ZoneId
) {
    private val executors = listOf(
        LocalNotificationActionExecutor(notificationPort),
        CreatePersonalEventActionExecutor(personalEventStore, zoneProvider),
        RefreshHomeActionExecutor(refreshPort)
    )

    fun all(): List<ActionExecutor> = executors
}
