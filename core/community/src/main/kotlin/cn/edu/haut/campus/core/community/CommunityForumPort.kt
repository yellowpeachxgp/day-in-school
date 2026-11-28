package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 本地产品功能开关状态。它只表达「本功能是否开放」，不替代学校能力证据
 * [cn.edu.haut.campus.core.model.CapabilityState] 或数据可信等级。
 */
sealed interface FeatureAvailability {
    data object Enabled : FeatureAvailability
    data class Disabled(val reason: FeatureDisabledReason) : FeatureAvailability
}

enum class FeatureDisabledReason {
    NOT_ENABLED,
}

/** D2 的稳定功能开关键；当前唯一实现 DisabledForumPort 恒按默认关闭处理。 */
object CommunityFeatureFlags {
    const val FORUM_KEY = "community.forum"
    const val FORUM_DEFAULT_ENABLED = false
}

/** D2 保留插件槽的登记事实；保留不是 supported，不代表宿主存在可渲染入口。 */
object CommunityReservedExtensionSlots {
    const val BOARDS_SLOT_ID = "community.boards"
}

/**
 * CT-05 故意不定义成功响应 schema，因此这些类型也不发明标题、正文、审核或私信字段。
 * 它们只是保留 Port 的类型占位；未来若 G6 合规通过并启用，必须在同一 PR 用真实契约类型替换。
 */
interface Board
interface PostPage
interface PostDraft
interface PostRef
interface ReportReason

/** 论坛与失物招领私聊需求共用、但当前明确不开放的社区接口预留。 */
interface CommunityForumPort {
    val availability: Flow<FeatureAvailability>

    suspend fun listBoards(scope: SchoolAccountScope): CommunityResult<List<Board>>

    suspend fun listPosts(
        scope: SchoolAccountScope,
        boardId: String,
        cursor: String?,
    ): CommunityResult<PostPage>

    suspend fun createPost(
        scope: SchoolAccountScope,
        draft: PostDraft,
        idempotencyKey: String,
    ): CommunityResult<PostRef>

    suspend fun report(
        scope: SchoolAccountScope,
        target: EntityRef,
        reason: ReportReason,
    ): CommunityResult<Unit>
}

/** D2 当前唯一实现：不触网、不落盘、不校验输入、不产生事件，全部返回 NotEnabled。 */
object DisabledForumPort : CommunityForumPort {
    override val availability: Flow<FeatureAvailability> = flowOf(
        FeatureAvailability.Disabled(FeatureDisabledReason.NOT_ENABLED),
    )

    override suspend fun listBoards(scope: SchoolAccountScope): CommunityResult<List<Board>> =
        CommunityResult.NotEnabled

    override suspend fun listPosts(
        scope: SchoolAccountScope,
        boardId: String,
        cursor: String?,
    ): CommunityResult<PostPage> = CommunityResult.NotEnabled

    override suspend fun createPost(
        scope: SchoolAccountScope,
        draft: PostDraft,
        idempotencyKey: String,
    ): CommunityResult<PostRef> = CommunityResult.NotEnabled

    override suspend fun report(
        scope: SchoolAccountScope,
        target: EntityRef,
        reason: ReportReason,
    ): CommunityResult<Unit> = CommunityResult.NotEnabled
}
