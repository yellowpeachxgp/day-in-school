package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.datapack.DataPackFreshness
import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestionOption
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GuidePackageLoaderTest {

    @Test
    fun verified_procedure_pack_loads_with_item_metadata_and_review_state() {
        val pack = GuideTestFixtures.disPack(DisDatapackKind.PROCEDURE_GUIDE, listOf(GuideTestFixtures.branchProcedure()))
        val verified = GuideTestFixtures.verified(pack)

        val loaded = GuidePackageLoader.loadVerified(verified, LocalDate.parse("2027-05-21"))

        assertEquals("haut.procedures", loaded.packId)
        assertEquals(Digests.sha256Hex("{}".toByteArray(Charsets.UTF_8)), loaded.artifactSha256)
        assertEquals(DataPackFreshness.REVIEW_DUE, loaded.freshness)
        assertEquals(DataPackFreshness.REVIEW_DUE, loaded.metadataFor("student-certificate").freshness)
        assertEquals("https://example.com/haut/public-guide", loaded.metadataFor("student-certificate").sources.single().url)
    }

    @Test
    fun loader_rejects_branch_target_outside_declared_steps() {
        val original = GuideTestFixtures.branchProcedure()
        val question = original.questions.single()
        val invalid = original.copy(
            questions = listOf(
                question.copy(
                    options = listOf(
                        ProcedureQuestionOption("yes", "需要", nextStepId = "missing"),
                        ProcedureQuestionOption("no", "不需要", nextStepId = "submit")
                    )
                )
            )
        )
        val pack = GuideTestFixtures.disPack(DisDatapackKind.PROCEDURE_GUIDE, listOf(invalid))

        assertFailsWith<GuideDefinitionException> {
            GuidePackageLoader.loadVerified(GuideTestFixtures.verified(pack), LocalDate.parse("2026-11-28"))
        }
    }

    @Test
    fun loader_rejects_place_ref_that_is_not_campus_graph_stable_id() {
        val invalid = GuideTestFixtures.branchProcedure().copy(placeRef = "office-building")
        val pack = GuideTestFixtures.disPack(DisDatapackKind.PROCEDURE_GUIDE, listOf(invalid))

        assertFailsWith<GuideDefinitionException> {
            GuidePackageLoader.loadVerified(GuideTestFixtures.verified(pack), LocalDate.parse("2026-11-28"))
        }
    }

    @Test
    fun loader_rejects_manually_forged_verified_wrapper_with_bad_payload_digest() {
        val pack = GuideTestFixtures.disPack(DisDatapackKind.PROCEDURE_GUIDE, listOf(GuideTestFixtures.branchProcedure()))
        val verified = GuideTestFixtures.verified(pack)
        val forged = verified.copy(
            signatureVerification = verified.signatureVerification.copy(payloadSha256 = "0".repeat(64))
        )

        assertFailsWith<GuideSecurityException> {
            GuidePackageLoader.loadVerified(forged, LocalDate.parse("2026-11-28"))
        }
    }

    @Test
    fun loader_rejects_non_guide_datapack_kind() {
        val facility = GuideTestFixtures.disPack(DisDatapackKind.FACILITY_CATALOG, emptyList())

        assertFailsWith<GuideDefinitionException> {
            GuidePackageLoader.loadVerified(GuideTestFixtures.verified(facility), LocalDate.parse("2026-11-28"))
        }
    }

    @Test
    fun loader_rejects_depends_on_because_cross_item_semantics_are_not_contractual() {
        val invalid = GuideTestFixtures.branchProcedure().copy(dependsOn = listOf("other-procedure"))
        val pack = GuideTestFixtures.disPack(DisDatapackKind.PROCEDURE_GUIDE, listOf(invalid))

        assertFailsWith<GuideDefinitionException> {
            GuidePackageLoader.loadVerified(GuideTestFixtures.verified(pack), LocalDate.parse("2026-11-28"))
        }
    }

    @Test
    fun loader_rejects_detached_signature_with_invalid_shape_even_if_digests_match() {
        val pack = GuideTestFixtures.disPack(DisDatapackKind.PROCEDURE_GUIDE, listOf(GuideTestFixtures.branchProcedure()))
        val verified = GuideTestFixtures.verified(pack)
        val invalidSignature = verified.signatureVerification.signature.copy(valueBase64 = "not-base64")
        val forged = verified.copy(
            signatureVerification = verified.signatureVerification.copy(signature = invalidSignature)
        )

        assertFailsWith<GuideSecurityException> {
            GuidePackageLoader.loadVerified(forged, LocalDate.parse("2026-11-28"))
        }
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("预期抛出 " + T::class.simpleName)
        throw AssertionError("unreachable")
    }
}
