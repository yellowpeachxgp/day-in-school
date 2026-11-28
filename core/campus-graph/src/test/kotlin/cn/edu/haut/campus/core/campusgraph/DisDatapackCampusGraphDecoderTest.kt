package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.bundle.PackageFile
import cn.edu.haut.campus.core.packages.datapack.DataPackSignatureVerification
import cn.edu.haut.campus.core.packages.datapack.DetachedDataPackSignature
import cn.edu.haut.campus.core.packages.datapack.DisDatapackLoader
import cn.edu.haut.campus.core.packages.datapack.VerifiedDataPack
import cn.edu.haut.campus.core.packages.manifest.PackageLicense
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.packages.manifest.PackageTarget
import cn.edu.haut.campus.core.packages.manifest.PackageType
import cn.edu.haut.campus.core.packages.manifest.PackageVerification
import cn.edu.haut.campus.core.packages.manifest.PackageVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisDatapackCampusGraphDecoderTest {

    private val school = SchoolId("haut")
    private val campus = CampusId("lianhuajie")
    private val decoder = DisDatapackCampusGraphDecoder(
        defaultCampusRef = CampusGraphIds.build(school, campus, CampusGraphEntityType.CAMPUS, campus.value)
    )

    @Test
    fun procedureGuideProjectsToProcedureNodeWithExplicitServiceParent() {
        val bytes = procedureBytes("haut/lianhuajie/service/student-affairs")
        val decoded = DisDatapackLoader.decode(bytes)

        val entities = decoder.decode(decoded.pack)

        val procedure = entities.single() as ProcedureNode
        assertEquals(CampusGraphEntityType.PROCEDURE, procedure.type)
        assertEquals("学生证办理", procedure.name)
        assertEquals("haut/lianhuajie/service/student-affairs", procedure.serviceRef?.id)
        assertEquals(procedure.serviceRef, procedure.parent)
        assertEquals(CampusGraphSourceLevel.DATA_PACKAGE, procedure.provenance.sourceLevel)
        assertEquals(VerificationLevel.UNVERIFIED, procedure.provenance.provenance.trust)
        assertEquals("2027-05-20", procedure.provenance.reviewBy.toString())
        assertEquals("haut.procedures", procedure.attributes["datapackId"])
    }

    @Test
    fun facilityCatalogProjectsBuildingScopedFacilityWithoutInventingHierarchy() {
        val bytes = facilityPackBytes()

        val entities = decoder.decode(DisDatapackLoader.decode(bytes).pack)

        val facility = entities.single() as FacilityNode
        assertEquals(CampusGraphFacilityKind.SERVICE_DESK, facility.kind)
        assertEquals("haut/lianhuajie/building/administration", facility.buildingRef?.id)
        assertEquals(facility.buildingRef, facility.parent)
        assertEquals("service-desk", facility.attributes["facilityType"])
        assertEquals("wheelchair", facility.attributes["capabilities"])
    }

    @Test
    fun graphProjectionRejectsUnsupportedKindsAndNonLosslessProcedureParents() {
        val buildingScopedProcedure = DisDatapackLoader.decode(procedureBytes("haut/lianhuajie/building/administration")).pack
        assertThrowsIllegalArgument {
            decoder.decode(buildingScopedProcedure)
        }
        val indoor = DisDatapackLoader.decode(indoorPackBytes()).pack
        assertThrowsIllegalArgument {
            decoder.decode(indoor)
        }
    }

    @Test
    fun multilineDisplayTextProjectsAsLosslessGraphSafeEscapes() {
        val bytes = facilityPackBytesWithMultilineText()
        val decoded = DisDatapackLoader.decode(bytes)
        val typed = decoded.pack.items.single() as cn.edu.haut.campus.core.packages.datapack.DisDatapackItem.FacilityCatalog
        assertTrue(typed.summary!!.contains('\n'))

        val facility = decoder.decode(decoded.pack).single() as FacilityNode

        assertEquals("Weekdays: 09:00-17:00\\nSaturday: 09:00-12:00", facility.attributes["summary"])
        assertEquals("北门进入\\n工作日登记", facility.attributes["accessNotes"])
        assertEquals("工作日 09:00-17:00\\n周六 09:00-12:00", facility.openingHours)
        assertTrue(facility.attributes.values.all { value -> value.none { it.isISOControl() } })
    }

    @Test
    fun datapackItemSeparatorsUseCollisionSafeNamespaceInStandaloneAndEmbeddedPaths() {
        val bytes = procedurePackWithItemIds(listOf("aid/forms", "aid-forms", "aid.forms", "aid_forms"))
        val decoded = DisDatapackLoader.decode(bytes)

        val standalone = decoder.decodeVerified(fakeVerified(decoded))
        assertEquals(4, standalone.size)
        assertEquals(4, standalone.map { it.ref.id }.distinct().size)
        assertTrue(standalone[0].ref.id.endsWith("/procedure/dp-aid:forms"))
        assertTrue(standalone[1].ref.id.endsWith("/procedure/dp-aid-forms"))
        assertTrue(standalone[2].ref.id.endsWith("/procedure/dp-aid.forms"))
        assertTrue(standalone[3].ref.id.endsWith("/procedure/dp-aid_forms"))

        val bundle = PackageBundle.of(listOf(PackageFile.of("assets/haut.procedures.json", bytes)))
        val embedded = decoder.decode(manifest(bundle, id = "haut.procedures"), bundle)
        assertEquals(standalone.map { it.ref.id }.toSet(), embedded.map { it.ref.id }.toSet())
    }

    @Test
    fun decodeVerifiedRejectsDuplicateProjectedStableIdsFailClosed() {
        val bytes = procedurePackWithItemIds(listOf("aid/forms", "aid/forms"))
        val decoded = DisDatapackLoader.decode(bytes)

        assertThrowsIllegalArgument {
            decoder.decodeVerified(fakeVerified(decoded))
        }
    }

    @Test
    fun indoorEdgeAndLevelHaveDedicatedFailClosedNegativeCoverage() {
        for (itemType in listOf("edge", "level")) {
            val bytes = String(indoorPackBytes(), Charsets.UTF_8)
                .replace("\"itemType\":\"node\"", "\"itemType\":\"" + itemType + "\"")
                .toByteArray(Charsets.UTF_8)
            val decoded = DisDatapackLoader.decode(bytes)
            assertEquals(itemType, (decoded.pack.items.single() as cn.edu.haut.campus.core.packages.datapack.DisDatapackItem.IndoorGraph).itemType.wireId)
            try {
                decoder.decodeVerified(fakeVerified(decoded))
                throw AssertionError("expected decodeVerified to reject indoor " + itemType)
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("indoor-graph"))
            }
        }
    }

    @Test
    fun embeddedDecoderChecksSingleJsonAndManifestIdentity() {
        val bytes = procedureBytes("haut/lianhuajie/service/student-affairs")
        val bundle = PackageBundle.of(listOf(PackageFile.of("assets/haut.procedures.json", bytes)))
        val manifest = manifest(bundle, id = "haut.procedures")
        val decoded = decoder.decode(manifest, bundle)
        assertEquals(1, decoded.size)

        assertThrowsIllegalArgument {
            decoder.decode(manifest.copy(id = "other.procedures"), bundle)
        }
        val twoJsonBundle = PackageBundle.of(listOf(
            PackageFile.of("assets/haut.procedures.json", bytes),
            PackageFile.of("assets/other.json", bytes)
        ))
        assertThrowsIllegalArgument {
            decoder.decode(manifest(twoJsonBundle, id = "haut.procedures"), twoJsonBundle)
        }
    }

    private fun procedurePackWithItemIds(itemIds: List<String>): ByteArray {
        val items = itemIds.mapIndexed { index, id ->
            "{\"id\":\"" + id + "\",\"placeRef\":\"haut/lianhuajie/service/student-affairs\",\"steps\":[{\"id\":\"s" + (index + 1) + "\",\"instruction\":\"办理\",\"title\":\"步骤\"}],\"title\":\"事项 " + index + "\"}"
        }.joinToString(",")
        return ("{\"format\":\"dis-datapack/v1\",\"items\":[" + items + "],\"kind\":\"procedure-guide\",\"maintainers\":[\"github:example-maintainer\"],\"packId\":\"haut.procedures\",\"reviewBy\":\"2027-05-20\",\"schoolId\":\"haut\",\"sources\":[{\"retrievedAt\":\"2026-11-20\",\"title\":\"学校办事流程公开页\",\"url\":\"https://example.com/haut/procedure-guide\"}],\"verificationLevel\":\"UNVERIFIED\",\"verifiedAt\":\"2026-11-20\",\"version\":\"2026.11.1\"}").toByteArray(Charsets.UTF_8)
    }

    private fun fakeVerified(decoded: cn.edu.haut.campus.core.packages.datapack.DecodedDataPack): VerifiedDataPack {
        val fingerprint = "0".repeat(64)
        val signature = DetachedDataPackSignature(
            algorithm = "Ed25519",
            scheme = cn.edu.haut.campus.core.packages.datapack.DIS_DATAPACK_SIGNATURE_SCHEME,
            publicKeyFingerprint = fingerprint,
            valueBase64 = "A".repeat(86) + "==",
            signedPayloadSha256 = "0".repeat(64)
        )
        return VerifiedDataPack(
            decoded,
            DataPackSignatureVerification(signature, fingerprint, "0".repeat(64))
        )
    }

    private fun procedureBytes(placeRef: String): ByteArray {
        val withStepRef = String(fixtureBytes(), Charsets.UTF_8)
            .replace("haut/lianhuajie/building/administration", placeRef)
        return withStepRef
            .replace("],\"questions\"", "],\"placeRef\":\"" + placeRef + "\",\"questions\"")
            .toByteArray(Charsets.UTF_8)
    }

    private fun facilityPackBytes(): ByteArray = """
        {"format":"dis-datapack/v1","items":[{"capabilities":["wheelchair"],"facilityType":"service-desk","id":"service-desk-example","openingHours":"工作日 09:00-17:00","placeRef":"haut/lianhuajie/building/administration","title":"学生服务台"}],"kind":"facility-catalog","maintainers":["github:example-maintainer"],"packId":"haut.facilities","reviewBy":"2027-05-20","schoolId":"haut","sources":[{"retrievedAt":"2026-11-20","title":"学校服务台公开页","url":"https://example.com/haut/service-desk"}],"verificationLevel":"UNVERIFIED","verifiedAt":"2026-11-20","version":"2026.11.1"}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun facilityPackBytesWithMultilineText(): ByteArray = """
        {"format":"dis-datapack/v1","items":[{"accessNotes":"北门进入\n工作日登记","capabilities":["wheelchair"],"facilityType":"service-desk","id":"service-desk-example","openingHours":"工作日 09:00-17:00\n周六 09:00-12:00","placeRef":"haut/lianhuajie/building/administration","summary":"Weekdays: 09:00-17:00\nSaturday: 09:00-12:00","title":"学生服务台"}],"kind":"facility-catalog","maintainers":["github:example-maintainer"],"packId":"haut.facilities","reviewBy":"2027-05-20","schoolId":"haut","sources":[{"retrievedAt":"2026-11-20","title":"学校服务台公开页","url":"https://example.com/haut/service-desk"}],"verificationLevel":"UNVERIFIED","verifiedAt":"2026-11-20","version":"2026.11.1"}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun indoorPackBytes(): ByteArray = """
        {"format":"dis-datapack/v1","items":[{"id":"node-1","itemType":"node","latitude":34,"longitude":113,"nodeKind":"entrance","placeRef":"haut/lianhuajie/building/administration","title":"入口节点"}],"kind":"indoor-graph","maintainers":["github:example-maintainer"],"packId":"haut.indoor","reviewBy":"2027-05-20","schoolId":"haut","sources":[{"retrievedAt":"2026-11-20","title":"室内图公开页","url":"https://example.com/haut/indoor"}],"verificationLevel":"UNVERIFIED","verifiedAt":"2026-11-20","version":"2026.11.1"}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun manifest(bundle: PackageBundle, id: String): PackageManifest = PackageManifest(
        id = id,
        version = PackageVersion("2026.11.1"),
        type = PackageType.SCHOOL_PROFILE,
        publisher = "campus-maintainers",
        license = PackageLicense("Apache-2.0"),
        targets = listOf(PackageTarget(school)),
        minHostApi = 1,
        minAppVersionCode = 1,
        capabilities = setOf(CapabilityKind.CAMPUS_BUILDINGS),
        permissions = emptyList(),
        files = bundle.files.associate { it.path to it.sha256 },
        verification = PackageVerification(
            level = VerificationLevel.UNVERIFIED,
            fixtureDigest = "0".repeat(64)
        )
    )

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/valid-procedure.json")).use { it.readBytes() }

    private inline fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
