package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.datapack.DIS_DATAPACK_FORMAT
import cn.edu.haut.campus.core.packages.datapack.DIS_DATAPACK_SIGNATURE_SCHEME
import cn.edu.haut.campus.core.packages.datapack.DataPackFreshness
import cn.edu.haut.campus.core.packages.datapack.DataPackMaterial
import cn.edu.haut.campus.core.packages.datapack.DataPackSignatureVerification
import cn.edu.haut.campus.core.packages.datapack.DataPackSource
import cn.edu.haut.campus.core.packages.datapack.DetachedDataPackSignature
import cn.edu.haut.campus.core.packages.datapack.DisDatapack
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.DisDatapackLoader
import cn.edu.haut.campus.core.packages.datapack.DisDatapackSignature
import cn.edu.haut.campus.core.packages.datapack.OpportunityRequirement
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestion
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestionOption
import cn.edu.haut.campus.core.packages.datapack.ProcedureStep
import cn.edu.haut.campus.core.packages.datapack.Rfc8785Json
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

    /** 用 W5-01 真实 exact-byte/schema loader 生成 decoded，再补上测试用 detached signature 类型边界。 */
    fun verified(pack: DisDatapack): VerifiedDataPack {
        val canonicalBytes = canonicalBytesFor(pack)
        val decoded = DisDatapackLoader.decode(canonicalBytes)
        require(decoded.pack == pack) { "测试 datapack 序列化夹具与 typed model 不一致" }
        val payload = DisDatapackSignature.payloadSha256ForCanonicalBytes(decoded.canonicalBytes)
        val fingerprint = "c".repeat(64)
        val signature = DetachedDataPackSignature(
            algorithm = "Ed25519",
            scheme = DIS_DATAPACK_SIGNATURE_SCHEME,
            publicKeyFingerprint = fingerprint,
            valueBase64 = "A".repeat(86) + "==",
            signedPayloadSha256 = payload
        )
        return VerifiedDataPack(
            decoded = decoded,
            signatureVerification = DataPackSignatureVerification(signature, fingerprint, payload)
        )
    }

    private fun canonicalBytesFor(pack: DisDatapack): ByteArray {
        val root = JsonValue.Obj(linkedMapOf(
            "format" to JsonValue.Str(DIS_DATAPACK_FORMAT),
            "packId" to JsonValue.Str(pack.packId),
            "schoolId" to JsonValue.Str(pack.schoolId),
            "kind" to JsonValue.Str(pack.kind.wireId),
            "version" to JsonValue.Str(pack.version),
            "sources" to JsonValue.Arr(pack.sources.map(::sourceJson)),
            "verifiedAt" to JsonValue.Str(pack.verifiedAt),
            "reviewBy" to JsonValue.Str(pack.reviewBy),
            "maintainers" to JsonValue.Arr(pack.maintainers.map { JsonValue.Str(it) }),
            "verificationLevel" to JsonValue.Str(pack.verificationLevel.name),
            "items" to JsonValue.Arr(pack.items.map(::itemJson))
        ))
        return Rfc8785Json.canonicalize(root).toByteArray(Charsets.UTF_8)
    }

    private fun itemJson(item: DisDatapackItem): JsonValue = when (item) {
        is DisDatapackItem.ProcedureGuide -> procedureJson(item)
        is DisDatapackItem.OpportunityCatalog -> opportunityJson(item)
        else -> throw IllegalArgumentException("测试夹具暂不支持该 datapack item kind")
    }

    private fun baseItemFields(fields: LinkedHashMap<String, JsonValue>, item: DisDatapackItem) {
        fields["id"] = JsonValue.Str(item.id)
        fields["title"] = JsonValue.Str(item.title)
        item.summary?.let { fields["summary"] = JsonValue.Str(it) }
        item.provenance?.let { provenance ->
            val provenanceFields = LinkedHashMap<String, JsonValue>()
            provenance.sources?.let { sources -> provenanceFields["sources"] = JsonValue.Arr(sources.map(::sourceJson)) }
            provenance.verifiedAt?.let { value -> provenanceFields["verifiedAt"] = JsonValue.Str(value) }
            provenance.reviewBy?.let { value -> provenanceFields["reviewBy"] = JsonValue.Str(value) }
            provenance.verificationLevel?.let { value -> provenanceFields["verificationLevel"] = JsonValue.Str(value.name) }
            fields["provenance"] = JsonValue.Obj(provenanceFields)
        }
    }

    private fun procedureJson(item: DisDatapackItem.ProcedureGuide): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        baseItemFields(fields, item)
        fields["steps"] = JsonValue.Arr(item.steps.map { step ->
            JsonValue.Obj(LinkedHashMap<String, JsonValue>().also { stepFields ->
                stepFields["id"] = JsonValue.Str(step.id)
                stepFields["title"] = JsonValue.Str(step.title)
                stepFields["instruction"] = JsonValue.Str(step.instruction)
                step.placeRef?.let { stepFields["placeRef"] = JsonValue.Str(it) }
                step.estimatedMinutes?.let { stepFields["estimatedMinutes"] = JsonValue.Num(it.toString()) }
            })
        })
        fields["questions"] = JsonValue.Arr(item.questions.map { question ->
            JsonValue.Obj(LinkedHashMap<String, JsonValue>().also { questionFields ->
                questionFields["id"] = JsonValue.Str(question.id)
                questionFields["question"] = JsonValue.Str(question.question)
                questionFields["options"] = JsonValue.Arr(question.options.map { option ->
                    JsonValue.Obj(LinkedHashMap<String, JsonValue>().also { optionFields ->
                        optionFields["value"] = JsonValue.Str(option.value)
                        optionFields["label"] = JsonValue.Str(option.label)
                        option.nextStepId?.let { optionFields["nextStepId"] = JsonValue.Str(it) }
                    })
                })
            })
        })
        fields["materials"] = JsonValue.Arr(item.materials.map(::materialJson))
        item.placeRef?.let { fields["placeRef"] = JsonValue.Str(it) }
        item.officeHours?.let { fields["officeHours"] = JsonValue.Str(it) }
        fields["contacts"] = JsonValue.Arr(item.contacts.map { contact ->
            JsonValue.Obj(LinkedHashMap<String, JsonValue>().also { contactFields ->
                contactFields["kind"] = JsonValue.Str(contact.kind.wireId)
                contactFields["value"] = JsonValue.Str(contact.value)
                contact.label?.let { contactFields["label"] = JsonValue.Str(it) }
            })
        })
        fields["dependsOn"] = JsonValue.Arr(item.dependsOn.map { JsonValue.Str(it) })
        return JsonValue.Obj(fields)
    }

    private fun opportunityJson(item: DisDatapackItem.OpportunityCatalog): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        baseItemFields(fields, item)
        fields["category"] = JsonValue.Str(item.category.wireId)
        item.organizer?.let { fields["organizer"] = JsonValue.Str(it) }
        item.deadlineAt?.let { fields["deadlineAt"] = JsonValue.Str(it) }
        fields["requirements"] = JsonValue.Arr(item.requirements.map { requirement ->
            JsonValue.Obj(linkedMapOf(
                "id" to JsonValue.Str(requirement.id),
                "text" to JsonValue.Str(requirement.text),
                "computable" to JsonValue.Bool(requirement.computable)
            ))
        })
        fields["materials"] = JsonValue.Arr(item.materials.map(::materialJson))
        item.applicationUrl?.let { fields["applicationUrl"] = JsonValue.Str(it) }
        return JsonValue.Obj(fields)
    }

    private fun materialJson(material: DataPackMaterial): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["name"] = JsonValue.Str(material.name)
        fields["required"] = JsonValue.Bool(material.required)
        material.validDays?.let { fields["validDays"] = JsonValue.Num(it.toString()) }
        material.note?.let { fields["note"] = JsonValue.Str(it) }
        return JsonValue.Obj(fields)
    }

    private fun sourceJson(source: DataPackSource): JsonValue = JsonValue.Obj(linkedMapOf(
        "url" to JsonValue.Str(source.url),
        "title" to JsonValue.Str(source.title),
        "retrievedAt" to JsonValue.Str(source.retrievedAt)
    ))

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
