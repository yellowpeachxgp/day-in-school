@file:Suppress("DEPRECATION")

package cn.edu.haut.campus.feature.teamtime

import android.content.Context
import cn.edu.haut.campus.core.community.CommunityCacheIdentity
import cn.edu.haut.campus.core.community.CommunityClientFactory
import cn.edu.haut.campus.core.community.CommunityFeatureGate
import cn.edu.haut.campus.core.community.CommunitySessionSnapshot
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.CloudIdentityRepository
import cn.edu.haut.campus.core.database.automationDatabase
import cn.edu.haut.campus.core.database.automationEntityWritePort
import cn.edu.haut.campus.core.database.automationScope
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineInputProvider
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineSnapshot
import cn.edu.haut.campus.core.timeline.CourseTimelineSource
import cn.edu.haut.campus.core.timeline.DefaultTimelineService
import cn.edu.haut.campus.core.timeline.ExamTimelineInputProvider
import cn.edu.haut.campus.core.timeline.ExamTimelineSnapshot
import cn.edu.haut.campus.core.timeline.ExamTimelineSource
import cn.edu.haut.campus.core.timeline.PersonalTimelineSource
import cn.edu.haut.campus.core.timeline.PracticeTimelineSource
import cn.edu.haut.campus.core.timeline.TimelineService
import cn.edu.haut.campus.core.vault.EntityWriter
import cn.edu.haut.campus.core.vault.RoomPersonalEventStore
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** feature 侧窄身份 port：UI/Repository 只能按快照取一次请求令牌，不能接触 vault、refresh token 或任意 facade。 */
internal interface TeamTimeCloudIdentityPort {
    suspend fun snapshot(): CampusRepository.CloudIdentitySessionSnapshot?
    suspend fun resolveAccessToken(snapshot: CampusRepository.CloudIdentitySessionSnapshot): String
}

internal class CloudIdentityTeamTimePort(
    private val repository: CloudIdentityRepository,
) : TeamTimeCloudIdentityPort {
    override suspend fun snapshot(): CampusRepository.CloudIdentitySessionSnapshot? =
        repository.snapshotCloudIdentitySession()

    override suspend fun resolveAccessToken(snapshot: CampusRepository.CloudIdentitySessionSnapshot): String =
        repository.resolveCommunityAccessToken(snapshot)
}

/**
 * W4-07 的组合根。每次组合会话重新装配；access token 只存在于 CommunityClient 单次请求闭包内，
 * feature/UI 永不持久化或展示。Timeline/PersonalEventStore 都只绑定当前 ActiveSession 的 profile scope。
 */
internal suspend fun createCommunityTeamTimeRepository(
    context: Context,
    activeSession: ActiveSession,
    identityPort: TeamTimeCloudIdentityPort = CloudIdentityTeamTimePort(activeSession.repository.cloudIdentityRepository),
    clock: Clock = Clock.systemUTC(),
): CommunityTeamTimeRepository {
    val repository = activeSession.repository
    val scope = repository.automationScope
    val initialIdentity = identityPort.snapshot()
    val baseUrl = initialIdentity?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(
        repository.preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl,
    )
    val sessionSnapshotProvider: suspend () -> CommunitySessionSnapshot = {
        val identity = identityPort.snapshot()
        if (identity == null) {
            CommunitySessionSnapshot(accessToken = null, cacheIdentity = null)
        } else {
            val token = identityPort.resolveAccessToken(identity)
            CommunitySessionSnapshot(
                accessToken = token.takeIf { it.isNotBlank() },
                cacheIdentity = CommunityCacheIdentity(
                    cloudUserId = identity.cloudUserId,
                    identityGeneration = identity.generation,
                ),
            )
        }
    }
    val communityAssembly = CommunityClientFactory.createFileBacked(
        filesDir = context.filesDir,
        baseUrl = baseUrl,
        scope = scope,
        sessionSnapshotProvider = sessionSnapshotProvider,
        featureGate = CommunityFeatureGate.ENABLED,
        clock = clock,
    )
    val database = repository.automationDatabase
    val personalEventStore = (repository.automationEntityWritePort as? EntityWriter)?.let { writer ->
        RoomPersonalEventStore(
            database = database,
            scope = scope,
            writer = writer,
            searchIndex = RoomSearchIndex(database),
        )
    }
    val timelineService = createTeamTimeTimelineService(
        repository = repository,
        scope = scope,
        personalEventStore = personalEventStore,
    )
    return CommunityTeamTimeRepository(
        client = communityAssembly.client,
        cloudUserIdProvider = { identityPort.snapshot()?.cloudUserId },
        timelineService = timelineService,
        personalEventStore = personalEventStore,
        scope = scope,
        zone = repository.timeZone,
        clock = clock,
    )
}

private fun createTeamTimeTimelineService(
    repository: cn.edu.haut.campus.core.database.CampusRepository,
    scope: SchoolAccountScope,
    personalEventStore: RoomPersonalEventStore?,
): TimelineService {
    val courseInput = TeamTimeCourseScheduleTimelineInputProvider(repository, scope, repository.timeZone)
    val examInput = TeamTimeExamTimelineInputProvider(repository, scope, repository.timeZone)
    return DefaultTimelineService(
        buildList {
            add(CourseTimelineSource(courseInput))
            add(PracticeTimelineSource(courseInput))
            add(ExamTimelineSource(examInput))
            personalEventStore?.let { add(PersonalTimelineSource(it)) }
        },
    )
}

private class TeamTimeCourseScheduleTimelineInputProvider(
    private val repository: cn.edu.haut.campus.core.database.CampusRepository,
    private val scope: SchoolAccountScope,
    private val zone: java.time.ZoneId,
) : CourseScheduleTimelineInputProvider {
    override fun observe(scope: SchoolAccountScope, window: cn.edu.haut.campus.core.timeline.TimeWindow) = flow {
        require(scope == this@TeamTimeCourseScheduleTimelineInputProvider.scope) { "课程 Timeline 输入 scope 不一致" }
        val data = repository.timetableRepository.getDirectScheduleDataSnapshot()
        emit(
            CourseScheduleTimelineSnapshot(
                zone = zone,
                provenance = teamTimeLocalProvenance(scope, "course-schedule"),
                revision = 1L,
                terms = listOfNotNull(data.term),
                courses = data.courses,
                rules = data.rules,
                periodTimes = data.periodTimes,
                calendarOverrides = data.calendarOverrides,
                occurrenceOverrides = data.occurrenceOverrides,
            ),
        )
    }
}

private class TeamTimeExamTimelineInputProvider(
    private val repository: cn.edu.haut.campus.core.database.CampusRepository,
    private val scope: SchoolAccountScope,
    private val zone: java.time.ZoneId,
) : ExamTimelineInputProvider {
    override fun observe(scope: SchoolAccountScope, window: cn.edu.haut.campus.core.timeline.TimeWindow) = flow {
        require(scope == this@TeamTimeExamTimelineInputProvider.scope) { "考试 Timeline 输入 scope 不一致" }
        emit(
            ExamTimelineSnapshot(
                zone = zone,
                provenance = teamTimeLocalProvenance(scope, "exam-schedule"),
                revision = 1L,
                exams = repository.examRepository.getExamSchedulesSnapshot(),
            ),
        )
    }
}

private fun teamTimeLocalProvenance(scope: SchoolAccountScope, record: String): DataProvenance = DataProvenance(
    providerId = "local.room",
    schoolId = scope.schoolId,
    accountId = scope.accountId,
    sourceSystem = "account-database",
    sourceRecordId = record,
    completeness = SnapshotCompleteness.UNKNOWN,
    trust = VerificationLevel.SOURCE_CONFIRMED,
    schemaVersion = 1,
)
