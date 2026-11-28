package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.datapack.DataPackFreshness
import cn.edu.haut.campus.core.packages.datapack.DisDatapack
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.DisDatapackLoader
import cn.edu.haut.campus.core.packages.datapack.DisDatapackSignature
import cn.edu.haut.campus.core.packages.datapack.VerifiedDataPack
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 把 W5-01 已验签数据包转换为 guide 可执行目录。
 *
 * 本对象故意不提供 unsigned/overrideTrust 入口：调用方不能把仅 decode 的包当作可执行包。
 * VerifiedDataPack 是 Kotlin 类型边界，这里仍复查 artifact/payload/signature 摘要链，防止
 * 调用方手工构造类型后绕过 W5-01 验签语义。guide 只解释固定字段，不反射、不 eval、不加载代码。
 */
object GuidePackageLoader {

    fun loadVerified(verified: VerifiedDataPack, today: LocalDate): GuidePackage {
        verifyTrustBoundary(verified)
        val pack = verified.pack
        if (pack.kind != DisDatapackKind.PROCEDURE_GUIDE && pack.kind != DisDatapackKind.OPPORTUNITY_CATALOG) {
            throw GuideDefinitionException("guide 不支持数据包 kind：" + pack.kind.wireId)
        }
        val duplicatedItems = pack.items.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicatedItems.isNotEmpty()) {
            throw GuideDefinitionException("guide 数据包条目 ID 重复：" + duplicatedItems.joinToString())
        }
        when (pack.kind) {
            DisDatapackKind.PROCEDURE_GUIDE -> pack.items.forEach { item ->
                validateProcedure(pack, item as DisDatapackItem.ProcedureGuide)
            }
            DisDatapackKind.OPPORTUNITY_CATALOG -> pack.items.forEach { item ->
                validateOpportunity(item as DisDatapackItem.OpportunityCatalog)
            }
            else -> Unit
        }
        return GuidePackage(
            packId = pack.packId,
            schoolId = pack.schoolId,
            kind = pack.kind,
            version = pack.version,
            artifactSha256 = verified.decoded.artifactSha256,
            payloadSha256 = verified.signatureVerification.payloadSha256,
            signerFingerprint = verified.signatureVerification.publicKeyFingerprint,
            freshness = DisDatapackLoader.freshness(pack, today),
            verificationLevel = pack.verificationLevel,
            sources = pack.sources,
            items = pack.items,
            itemMetadata = pack.items.associate { item -> item.id to metadataFor(pack, item, today) }
        )
    }

    private fun verifyTrustBoundary(verified: VerifiedDataPack) {
        val canonicalBytes = verified.decoded.canonicalBytes
        val expectedArtifact = Digests.sha256Hex(canonicalBytes)
        if (verified.decoded.artifactSha256 != expectedArtifact) {
            throw GuideSecurityException("datapack artifactSha256 与 canonical bytes 不一致")
        }
        val expectedPayload = DisDatapackSignature.payloadSha256ForCanonicalBytes(canonicalBytes)
        val verification = verified.signatureVerification
        try {
            DisDatapackSignature.validateShape(verification.signature)
        } catch (error: IllegalArgumentException) {
            throw GuideSecurityException("datapack detached signature 形状非法：" + error.message)
        }
        if (verification.payloadSha256 != expectedPayload) {
            throw GuideSecurityException("datapack payloadSha256 与 canonical bytes 不一致")
        }
        if (verification.signature.signedPayloadSha256 != verification.payloadSha256) {
            throw GuideSecurityException("datapack detached signature 与验签结果摘要链不一致")
        }
        if (verification.signature.publicKeyFingerprint != verification.publicKeyFingerprint) {
            throw GuideSecurityException("datapack signer fingerprint 与验签结果不一致")
        }
    }

    private fun metadataFor(pack: DisDatapack, item: DisDatapackItem, today: LocalDate): GuideItemMetadata {
        val provenanceOverride = item.provenance
        val verifiedAt = LocalDate.parse(provenanceOverride?.verifiedAt ?: pack.verifiedAt)
        val reviewBy = LocalDate.parse(provenanceOverride?.reviewBy ?: pack.reviewBy)
        if (reviewBy.isBefore(verifiedAt)) {
            throw GuideDefinitionException("guide 条目 reviewBy 早于 verifiedAt：" + item.id)
        }
        return GuideItemMetadata(
            sources = provenanceOverride?.sources ?: pack.sources,
            verifiedAt = verifiedAt,
            reviewBy = reviewBy,
            verificationLevel = provenanceOverride?.verificationLevel ?: pack.verificationLevel,
            freshness = if (today.isAfter(reviewBy)) DataPackFreshness.REVIEW_DUE else DataPackFreshness.CURRENT
        )
    }

    private fun validateProcedure(pack: DisDatapack, item: DisDatapackItem.ProcedureGuide) {
        requireStableTokens(item.steps.map { it.id }, "流程步骤 ID", item.id)
        requireStableTokens(item.questions.map { it.id }, "流程问题 ID", item.id)
        requireUnique(item.steps.map { it.id }, "流程步骤 ID", item.id)
        requireUnique(item.questions.map { it.id }, "流程问题 ID", item.id)
        requireUnique(item.materials.map { it.name }, "流程材料名称", item.id)
        if (item.dependsOn.isNotEmpty()) {
            throw GuideDefinitionException("procedure-guide dependsOn 语义尚未由契约明确，拒绝静默执行：" + item.id)
        }
        val stepIndex = item.steps.mapIndexed { index, step -> step.id to index }.toMap()
        item.questions.forEach { question ->
            requireUnique(question.options.map { it.value }, "问题选项值", item.id + "/" + question.id)
            question.options.forEach { option ->
                val target = option.nextStepId ?: return@forEach
                if (target !in stepIndex) {
                    throw GuideDefinitionException(
                        "流程分支目标不存在：" + item.id + "/" + question.id + " -> " + target
                    )
                }
            }
        }
        val schoolId = SchoolId(pack.schoolId)
        item.placeRef?.let { GuidePlaceRefs.parse(it, schoolId, "流程 placeRef") }
        item.steps.forEach { step ->
            step.placeRef?.let { GuidePlaceRefs.parse(it, schoolId, "流程步骤 placeRef") }
        }
    }

    private fun validateOpportunity(item: DisDatapackItem.OpportunityCatalog) {
        requireStableTokens(item.requirements.map { it.id }, "资格要求 ID", item.id)
        requireUnique(item.requirements.map { it.id }, "资格要求 ID", item.id)
        requireUnique(item.materials.map { it.name }, "机会材料名称", item.id)
        item.deadlineAt?.let { raw ->
            try {
                OffsetDateTime.parse(raw)
            } catch (error: DateTimeParseException) {
                throw GuideDefinitionException("机会 deadlineAt 不是合法 ISO date-time：" + item.id)
            }
        }
    }

    private fun requireUnique(values: List<String>, label: String, owner: String) {
        if (values.distinct().size != values.size) {
            throw GuideDefinitionException(label + "重复：" + owner)
        }
    }

    private fun requireStableTokens(values: List<String>, label: String, owner: String) {
        values.forEach { value ->
            val valid = value.isNotBlank() && value.length <= 120 &&
                value.none { it.isISOControl() || it.isWhitespace() } &&
                value.all { it.isLetterOrDigit() || it in "_-./:" }
            if (!valid) {
                throw GuideDefinitionException(label + "含空白、控制字符或非法字符：" + owner)
            }
        }
    }
}
