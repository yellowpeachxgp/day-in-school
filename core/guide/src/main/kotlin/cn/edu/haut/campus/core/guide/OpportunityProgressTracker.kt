package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.packages.datapack.DataPackMaterial
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** 材料 checklist 项；validDays 从用户标记完成时刻起算，到期后不再视为有效完成。 */
data class MaterialChecklistItem(
    val index: Int,
    val material: DataPackMaterial,
    val completedAt: Instant?,
    val expiresAt: Instant?,
    val expired: Boolean
) {
    val effectivelyCompleted: Boolean get() = completedAt != null && !expired
}

data class OpportunityProgressState(
    val snapshot: OpportunityProgressSnapshot,
    val assessment: EligibilityAssessment,
    val materials: List<MaterialChecklistItem>,
    val deadlineAt: Instant?,
    val deadlinePassed: Boolean
)

/** 机会中心进度与资格状态追踪；所有事实都以用户/计算输入显式记录，缺失不推断符合。 */
class OpportunityProgressTracker(
    private val store: GuideProgressStore,
    private val clock: Clock = Clock.systemUTC()
) {

    suspend fun track(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): OpportunityProgressState {
        val item = requireOpportunity(guidePackage, itemId)
        requireScopeSchool(scope, guidePackage)
        val existing = store.loadOpportunityProgress(scope, guidePackage.packId, itemId)
        val snapshot = if (existing == null) {
            val now = clock.instant()
            OpportunityProgressSnapshot(
                scope = scope,
                packId = guidePackage.packId,
                itemId = item.id,
                packVersion = guidePackage.version,
                artifactSha256 = guidePackage.artifactSha256,
                createdAt = now,
                updatedAt = now,
                revision = 1
            ).also { store.saveOpportunityProgress(it) }
        } else {
            ensureCompatible(existing, guidePackage, item.id)
            existing
        }
        return evaluate(guidePackage, itemId, snapshot)
    }

    suspend fun load(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): OpportunityProgressState? {
        val snapshot = store.loadOpportunityProgress(scope, guidePackage.packId, itemId) ?: return null
        ensureCompatible(snapshot, guidePackage, itemId)
        return evaluate(guidePackage, itemId, snapshot)
    }

    fun evaluate(
        guidePackage: GuidePackage,
        itemId: String,
        snapshot: OpportunityProgressSnapshot
    ): OpportunityProgressState {
        val item = requireOpportunity(guidePackage, itemId)
        ensureCompatible(snapshot, guidePackage, item.id)
        val assessment = OpportunityEligibilityEvaluator.assess(item, snapshot.facts, snapshot.eligibilityOverride)
        val now = clock.instant()
        val materials = item.materials.mapIndexed { index, material ->
            val completedAt = snapshot.completedMaterials[index]
            val validDays = material.validDays
            val expiresAt = if (completedAt != null && validDays != null) {
                completedAt.plus(Duration.ofDays(validDays.toLong()))
            } else {
                null
            }
            MaterialChecklistItem(
                index = index,
                material = material,
                completedAt = completedAt,
                expiresAt = expiresAt,
                expired = expiresAt != null && !now.isBefore(expiresAt)
            )
        }
        snapshot.completedMaterials.keys.forEach { index ->
            if (index !in item.materials.indices) {
                throw GuidePersistenceException("opportunity 材料下标超出当前 artifact：" + index)
            }
        }
        val deadline = item.deadlineAt?.let { OffsetDateTime.parse(it).toInstant() }
        return OpportunityProgressState(
            snapshot = snapshot,
            assessment = assessment,
            materials = materials,
            deadlineAt = deadline,
            deadlinePassed = deadline != null && !now.isBefore(deadline)
        )
    }

    suspend fun recordFact(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        requirementId: String,
        satisfaction: RequirementSatisfaction,
        source: RequirementFactSource,
        note: String? = null
    ): OpportunityProgressState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val item = requireOpportunity(guidePackage, itemId)
        val requirement = item.requirements.firstOrNull { it.id == requirementId }
            ?: throw GuideOperationException("资格要求不存在：" + requirementId)
        if (source == RequirementFactSource.COMPUTED && !requirement.computable) {
            throw GuideOperationException("不可计算要求不能接受 COMPUTED 事实：" + requirementId)
        }
        val fact = OpportunityRequirementFact(satisfaction, source, note, clock.instant())
        val updated = touch(snapshot.copy(facts = snapshot.facts + (requirementId to fact)))
        store.saveOpportunityProgress(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun clearFact(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        requirementId: String
    ): OpportunityProgressState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        if (!snapshot.facts.containsKey(requirementId)) {
            throw GuideOperationException("资格事实不存在：" + requirementId)
        }
        val updated = touch(snapshot.copy(facts = snapshot.facts - requirementId))
        store.saveOpportunityProgress(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun setMaterialCompleted(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        materialIndex: Int,
        completed: Boolean
    ): OpportunityProgressState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val item = requireOpportunity(guidePackage, itemId)
        if (materialIndex !in item.materials.indices) {
            throw GuideOperationException("材料下标不存在：" + materialIndex)
        }
        val updatedMaterials = if (completed) {
            snapshot.completedMaterials + (materialIndex to clock.instant())
        } else {
            snapshot.completedMaterials - materialIndex
        }
        val updated = touch(snapshot.copy(completedMaterials = updatedMaterials))
        store.saveOpportunityProgress(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun setOverride(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        state: EligibilityState,
        reason: String
    ): OpportunityProgressState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val manualOverride = ManualEligibilityOverride(state, reason, clock.instant())
        val updated = touch(snapshot.copy(eligibilityOverride = manualOverride))
        store.saveOpportunityProgress(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun clearOverride(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): OpportunityProgressState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        if (snapshot.eligibilityOverride == null) throw GuideOperationException("资格人工修正不存在")
        val updated = touch(snapshot.copy(eligibilityOverride = null))
        store.saveOpportunityProgress(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun setApplicationStatus(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        status: OpportunityApplicationStatus
    ): OpportunityProgressState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val updated = touch(snapshot.copy(applicationStatus = status))
        store.saveOpportunityProgress(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    private suspend fun loadCompatible(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): OpportunityProgressSnapshot {
        requireScopeSchool(scope, guidePackage)
        val snapshot = store.loadOpportunityProgress(scope, guidePackage.packId, itemId)
            ?: throw GuideOperationException("机会进度不存在：" + itemId)
        ensureCompatible(snapshot, guidePackage, itemId)
        return snapshot
    }

    private fun requireOpportunity(guidePackage: GuidePackage, itemId: String): DisDatapackItem.OpportunityCatalog =
        guidePackage.opportunityOrNull(itemId)
            ?: throw GuideDefinitionException("数据包不包含机会条目：" + itemId)

    private fun requireScopeSchool(scope: SchoolAccountScope, guidePackage: GuidePackage) {
        if (scope.schoolId.value != guidePackage.schoolId) {
            throw GuideOperationException("机会数据包学校与当前 scope 不一致")
        }
    }

    private fun ensureCompatible(snapshot: OpportunityProgressSnapshot, guidePackage: GuidePackage, itemId: String) {
        val compatible = snapshot.packId == guidePackage.packId &&
            snapshot.itemId == itemId &&
            snapshot.packVersion == guidePackage.version &&
            snapshot.artifactSha256 == guidePackage.artifactSha256
        if (!compatible) {
            throw GuideOperationException("机会定义已变化，拒绝把旧进度静默套用到新 artifact")
        }
    }

    private fun touch(snapshot: OpportunityProgressSnapshot): OpportunityProgressSnapshot =
        snapshot.copy(updatedAt = clock.instant(), revision = snapshot.revision + 1)
}
