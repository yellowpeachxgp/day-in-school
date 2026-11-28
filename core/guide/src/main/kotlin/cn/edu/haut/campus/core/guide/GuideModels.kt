package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.packages.datapack.DataPackMaterial
import cn.edu.haut.campus.core.packages.datapack.DataPackSource
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.DataPackFreshness
import cn.edu.haut.campus.core.packages.datapack.ProcedureStep
import cn.edu.haut.campus.core.model.VerificationLevel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 数据包条目级有效 provenance：item override 优先，否则回落到 pack 级字段。 */
data class GuideItemMetadata(
    val sources: List<DataPackSource>,
    val verifiedAt: LocalDate,
    val reviewBy: LocalDate,
    val verificationLevel: VerificationLevel,
    val freshness: DataPackFreshness
)

/** 已通过 W5-01 exact-byte/schema/signature 边界后才允许进入 guide 的目录快照。 */
data class GuidePackage(
    val packId: String,
    val schoolId: String,
    val kind: DisDatapackKind,
    val version: String,
    val artifactSha256: String,
    val payloadSha256: String,
    val signerFingerprint: String,
    val freshness: DataPackFreshness,
    val verificationLevel: VerificationLevel,
    val sources: List<DataPackSource>,
    val items: List<DisDatapackItem>,
    val itemMetadata: Map<String, GuideItemMetadata>
) {
    init {
        require(kind == DisDatapackKind.PROCEDURE_GUIDE || kind == DisDatapackKind.OPPORTUNITY_CATALOG) {
            "guide 只执行 procedure-guide/opportunity-catalog 数据包"
        }
        require(items.map { it.id }.distinct().size == items.size) { "guide 数据包条目 ID 重复" }
        require(itemMetadata.keys == items.map { it.id }.toSet()) { "guide 条目 provenance 索引不完整" }
    }

    fun procedureOrNull(itemId: String): DisDatapackItem.ProcedureGuide? =
        items.filterIsInstance<DisDatapackItem.ProcedureGuide>().firstOrNull { it.id == itemId }

    fun opportunityOrNull(itemId: String): DisDatapackItem.OpportunityCatalog? =
        items.filterIsInstance<DisDatapackItem.OpportunityCatalog>().firstOrNull { it.id == itemId }

    fun metadataFor(itemId: String): GuideItemMetadata =
        itemMetadata[itemId] ?: throw GuideDefinitionException("guide 数据包缺少条目元数据：" + itemId)
}

/** 当前答案已能确定的个人路径；遇到未回答分支问题时立即停止，不猜测后续路径。 */
data class PersonalPath(
    val steps: List<ProcedureStep> = emptyList(),
    val materials: List<DataPackMaterial> = emptyList(),
    val complete: Boolean,
    val blockedByQuestionId: String? = null
) {
    val totalEstimatedMinutes: Int?
        get() = if (steps.any { it.estimatedMinutes == null }) {
            null
        } else {
            steps.sumOf { it.estimatedMinutes ?: 0 }
        }
}

enum class GuideStepStatus {
    COMPLETED,
    CURRENT,
    UPCOMING
}

data class GuidePathStep(
    val id: String,
    val title: String,
    val instruction: String,
    val placeRef: String?,
    val estimatedMinutes: Int?,
    val status: GuideStepStatus
)

enum class GuideRunStatus {
    IN_PROGRESS,
    AWAITING_QUESTION,
    COMPLETED
}

/** 用户预约/计划执行某步骤的显式时间；没有时间时绝不伪造 deadline 或占用窗口。 */
data class ScheduledStep(
    val startAt: Instant,
    val duration: Duration? = null
) {
    init {
        require(duration == null || isPersistablePositiveDuration(duration)) {
            "步骤计划时长必须为可无损持久化的正微秒精度"
        }
    }
}

/** v1 进度格式使用整数微秒；拒绝会被截断成 0 或损失纳秒尾数的时长，绝不静默改写用户计划。 */
internal fun durationToWholeMicros(duration: Duration): Long? {
    if (duration.nano % 1_000 != 0) return null
    return try {
        Math.addExact(
            Math.multiplyExact(duration.seconds, 1_000_000L),
            (duration.nano / 1_000).toLong()
        )
    } catch (error: ArithmeticException) {
        null
    }
}

private fun isPersistablePositiveDuration(duration: Duration): Boolean =
    !duration.isZero && !duration.isNegative && durationToWholeMicros(duration)?.let { it > 0 } == true

/** procedure 进度的纯数据快照；状态机位置由 answers/completedStepIds 确定性推导。 */
data class ProcedureRunSnapshot(
    val scope: SchoolAccountScope,
    val packId: String,
    val itemId: String,
    val packVersion: String,
    val artifactSha256: String,
    val answers: Map<String, String> = emptyMap(),
    val completedStepIds: List<String> = emptyList(),
    val scheduledSteps: Map<String, ScheduledStep> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long
) {
    init {
        require(revision > 0) { "procedure 进度 revision 必须为正" }
        require(!updatedAt.isBefore(createdAt)) { "procedure 进度 updatedAt 不能早于 createdAt" }
        requireProgressIdentity(packId, itemId, artifactSha256, "procedure")
        require(answers.keys.all { validProgressToken(it) } && answers.values.all(::validOptionValue)) {
            "procedure 答案键含非法字符，或答案值不符合 datapack option value schema"
        }
        require(completedStepIds.distinct().size == completedStepIds.size) { "procedure 已完成步骤重复" }
        require(completedStepIds.all(::validProgressToken)) { "procedure 已完成步骤 ID 含非法字符" }
        require(scheduledSteps.keys.all(::validProgressToken)) { "procedure 计划步骤 ID 不能为空或含非法字符" }
    }
}

data class ProcedureRunState(
    val snapshot: ProcedureRunSnapshot,
    val path: PersonalPath,
    val stepStates: List<GuidePathStep>,
    val currentStep: GuidePathStep?,
    val pendingQuestion: cn.edu.haut.campus.core.packages.datapack.ProcedureQuestion?,
    val actionableQuestion: cn.edu.haut.campus.core.packages.datapack.ProcedureQuestion?,
    val status: GuideRunStatus,
    val completedCount: Int,
    val resolvedStepCount: Int
)

enum class RequirementSatisfaction {
    SATISFIED,
    UNKNOWN,
    NOT_SATISFIED
}

enum class RequirementFactSource {
    COMPUTED,
    USER_ATTESTATION
}

data class OpportunityRequirementFact(
    val satisfaction: RequirementSatisfaction,
    val source: RequirementFactSource,
    val note: String? = null,
    val recordedAt: Instant? = null
) {
    init {
        require(note == null || note.length <= 500) { "资格事实备注过长" }
    }
}

enum class EligibilityState {
    ELIGIBLE,
    NEEDS_VERIFICATION,
    NOT_ELIGIBLE
}

/** 用户显式修正资格判断；必须携带原因，且保留基础判断供审计。 */
data class ManualEligibilityOverride(
    val state: EligibilityState,
    val reason: String,
    val at: Instant
) {
    init {
        require(reason.isNotBlank() && reason.length <= 500) { "资格人工修正原因不能为空或过长" }
    }
}

enum class OpportunityApplicationStatus {
    TRACKING,
    SUBMITTED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    ARCHIVED
}

/** opportunity 进度；completedMaterials 的键是同一 artifact 内材料下标，包内容变化时整份进度拒绝恢复。 */
data class OpportunityProgressSnapshot(
    val scope: SchoolAccountScope,
    val packId: String,
    val itemId: String,
    val packVersion: String,
    val artifactSha256: String,
    val facts: Map<String, OpportunityRequirementFact> = emptyMap(),
    val completedMaterials: Map<Int, Instant> = emptyMap(),
    val eligibilityOverride: ManualEligibilityOverride? = null,
    val applicationStatus: OpportunityApplicationStatus = OpportunityApplicationStatus.TRACKING,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long
) {
    init {
        require(revision > 0) { "opportunity 进度 revision 必须为正" }
        require(!updatedAt.isBefore(createdAt)) { "opportunity 进度 updatedAt 不能早于 createdAt" }
        requireProgressIdentity(packId, itemId, artifactSha256, "opportunity")
        require(facts.keys.all(::validProgressToken)) { "opportunity 资格要求 ID 含非法字符" }
        require(completedMaterials.keys.all { it >= 0 }) { "opportunity 材料下标不能为负" }
    }
}

private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")

private fun validProgressToken(value: String): Boolean =
    value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() || it.isWhitespace() }

/** option value 是 datapack 声明的不透明字符串，不是 token；按 schema 只强制 1..80 个 Unicode code point。 */
private fun validOptionValue(value: String): Boolean =
    value.isNotEmpty() && value.codePointCount(0, value.length) <= 80

private fun requireProgressIdentity(packId: String, itemId: String, artifactSha256: String, label: String) {
    require(validProgressToken(packId) && validProgressToken(itemId)) { label + " 进度缺少合法包/条目身份" }
    require(HEX_SHA256.matches(artifactSha256)) { label + " 进度 artifactSha256 必须是小写 SHA-256 hex" }
}

sealed interface GuideRestoreResult {
    data class Restored(val state: ProcedureRunState) : GuideRestoreResult
    data object NotFound : GuideRestoreResult
    data class DefinitionChanged(
        val storedArtifactSha256: String,
        val currentArtifactSha256: String
    ) : GuideRestoreResult
}
