package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.datapack.DIS_DATAPACK_SIGNATURE_SCHEME
import cn.edu.haut.campus.core.packages.datapack.DataPackFreshness
import cn.edu.haut.campus.core.packages.datapack.DataPackMaterial
import cn.edu.haut.campus.core.packages.datapack.DataPackSignatureVerification
import cn.edu.haut.campus.core.packages.datapack.DataPackSource
import cn.edu.haut.campus.core.packages.datapack.DecodedDataPack
import cn.edu.haut.campus.core.packages.datapack.DetachedDataPackSignature
import cn.edu.haut.campus.core.packages.datapack.DisDatapack
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.DisDatapackSignature
import cn.edu.haut.campus.core.packages.datapack.OpportunityRequirement
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestion
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestionOption
import cn.edu.haut.campus.core.packages.datapack.ProcedureStep
import cn.edu.haut.campus.core.packages.datapack.VerifiedDataPack
import java.time.LocalDate

object GuideTestFixtures {
    val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )

    val source = DataPackSource(
        url = "https://example.com/haut/public-guide",
        title = "公开来源示例",
        retrievedAt = "2026-11-20"
    )

    fun branchProcedure(): DisDatapackItem.ProcedureGuide = DisDatapackItem.ProcedureGuide(
        id = "student-certificate",
        title = "学生证办理",
        summary = "测试流程",
        steps = listOf(
            ProcedureStep(
                id = "prepare",
                title = "准备材料",
                instruction = "检查学生证或校园卡",
                placeRef = "haut/lianhuajie/service/student-affairs",
                estimatedMinutes = 10
            ),
            ProcedureStep(
                id = "guardian",
                title = "补充监护人同意",
                instruction = "未成年申请者先补充监护人同意材料",
                placeRef = "haut/lianhuajie/service/student-affairs",
                estimatedMinutes = 20
            ),
            ProcedureStep(
                id = "submit",
                title = "提交申请",
                instruction = "到服务台提交申请",
                placeRef = "haut/lianhuajie/service/student-affairs",
                estimatedMinutes = null
            )
        ),
        questions = listOf(
            ProcedureQuestion(
                id = "minor",
                question = "是否属于需要监护人同意材料的情形？",
                options = listOf(
                    ProcedureQuestionOption("yes", "需要", nextStepId = "guardian"),
                    ProcedureQuestionOption("no", "不需要", nextStepId = "submit")
                )
            )
        ),
        materials = listOf(
            DataPackMaterial("学生证或校园卡", required = true, validDays = null),
            DataPackMaterial("一寸照片", required = false, validDays = 30)
        ),
        placeRef = "haut/lianhuajie/service/student-affairs",
        officeHours = "工作日白天"
    )

    fun simpleProcedure(id: String = "simple"): DisDatapackItem.ProcedureGuide = DisDatapackItem.ProcedureGuide(
        id = id,
        title = "简单流程",
        steps = listOf(
            ProcedureStep("only", "唯一步骤", "无时间估计", "haut/lianhuajie/campus/main", null)
        ),
        materials = listOf(DataPackMaterial("身份凭证"))
    )

    fun opportunity(): DisDatapackItem.OpportunityCatalog = DisDatapackItem.OpportunityCatalog(
        id = "innovation-grant",
        title = "创新训练项目",
        summary = "测试机会",
        category = cn.edu.haut.campus.core.packages.datapack.OpportunityCategory.PROGRAM,
        organizer = "测试单位",
        deadlineAt = "2026-03-05T16:00:00+08:00",
        requirements = listOf(
            OpportunityRequirement("enrolled", "在读学生", computable = true),
            OpportunityRequirement("mentor", "有指导教师", computable = false)
        ),
        materials = listOf(
            DataPackMaterial("项目申报书", required = true, validDays = 7),
            DataPackMaterial("指导教师意见", required = true, validDays = null)
        ),
        applicationUrl = "https://example.com/apply"
    )

    fun disPack(kind: DisDatapackKind, items: List<DisDatapackItem>): DisDatapack = DisDatapack(
        packId = if (kind == DisDatapackKind.PROCEDURE_GUIDE) "haut.procedures" else "haut.opportunities",
        schoolId = "haut",
        kind = kind,
        version = "2026.11.1",
        sources = listOf(source),
        verifiedAt = "2026-11-20",
        reviewBy = "2027-05-20",
        maintainers = listOf("github:example-maintainer"),
        verificationLevel = VerificationLevel.UNVERIFIED,
        items = items
    )

    fun verified(pack: DisDatapack, canonicalBytes: ByteArray = byteArrayOf('{', '}')): VerifiedDataPack {
        val artifact = Digests.sha256Hex(canonicalBytes)
        val payload = DisDatapackSignature.payloadSha256ForCanonicalBytes(canonicalBytes)
        val fingerprint = "c".repeat(64)
        val signature = DetachedDataPackSignature(
            algorithm = "Ed25519",
            scheme = DIS_DATAPACK_SIGNATURE_SCHEME,
            publicKeyFingerprint = fingerprint,
            valueBase64 = "A".repeat(86) + "==",
            signedPayloadSha256 = payload
        )
        return VerifiedDataPack(
            decoded = DecodedDataPack(
                pack = pack,
                canonicalText = String(canonicalBytes, Charsets.UTF_8),
                canonicalBytes = canonicalBytes,
                artifactSha256 = artifact
            ),
            signatureVerification = DataPackSignatureVerification(signature, fingerprint, payload)
        )
    }

    fun guidePackage(
        kind: DisDatapackKind,
        items: List<DisDatapackItem>,
        artifactSha256: String = "a".repeat(64),
        version: String = "2026.11.1",
        reviewBy: LocalDate = LocalDate.parse("2027-05-20")
    ): GuidePackage {
        val packId = if (kind == DisDatapackKind.PROCEDURE_GUIDE) "haut.procedures" else "haut.opportunities"
        val freshness = if (LocalDate.parse("2026-11-28").isAfter(reviewBy)) {
            DataPackFreshness.REVIEW_DUE
        } else {
            DataPackFreshness.CURRENT
        }
        return GuidePackage(
            packId = packId,
            schoolId = "haut",
            kind = kind,
            version = version,
            artifactSha256 = artifactSha256,
            payloadSha256 = "b".repeat(64),
            signerFingerprint = "c".repeat(64),
            freshness = freshness,
            verificationLevel = VerificationLevel.UNVERIFIED,
            sources = listOf(source),
            items = items,
            itemMetadata = items.associate { item ->
                item.id to GuideItemMetadata(
                    sources = listOf(source),
                    verifiedAt = LocalDate.parse("2026-11-20"),
                    reviewBy = reviewBy,
                    verificationLevel = VerificationLevel.UNVERIFIED,
                    freshness = freshness
                )
            }
        )
    }
}
