package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusGraphKernelTest {

    private val school = SchoolId("haut")
    private val campus = CampusId("lianhuajie")
    private val campusRef = CampusGraphIds.build(school, campus, CampusGraphEntityType.CAMPUS, "lianhuajie")

    @Test
    fun stableIdsUseSchoolCampusTypeSlugAndRejectEmptySlug() {
        val ref = CampusGraphIds.build(school, campus, CampusGraphEntityType.ROOM, " 嵩山1教 101 ")

        assertEquals("haut/lianhuajie/room/嵩山1教-101", ref.id)
        assertEquals(CampusGraphEntityType.ROOM, CampusGraphIds.typeOf(ref))
        assertEquals("嵩山1教-101", CampusGraphIds.slugOf(ref))

        assertThrowsIllegalArgument { CampusGraphIds.build(school, campus, CampusGraphEntityType.ROOM, "   ") }
        assertThrowsIllegalArgument { PlaceRef("haut/lianhuajie/room", school, campus) }
    }

    @Test
    fun entityValidationRejectsTypeMismatchAndControlCharacters() {
        val wrongTypeRef = CampusGraphIds.build(school, campus, CampusGraphEntityType.FLOOR, "b_t1")

        assertThrowsIllegalArgument {
            building(ref = wrongTypeRef, sourceLevel = CampusGraphSourceLevel.DATA_PACKAGE)
        }
        assertThrowsIllegalArgument {
            building(
                ref = CampusGraphIds.build(school, campus, CampusGraphEntityType.BUILDING, "b_t1"),
                sourceLevel = CampusGraphSourceLevel.DATA_PACKAGE,
                attributes = mapOf("bad key" to "value")
            )
        }
    }

    @Test
    fun mergeAppliesDataPackageThenUserOverrideThenCrowdPrecedence() {
        val ref = CampusGraphIds.build(school, campus, CampusGraphEntityType.BUILDING, "b_t1")
        val pack = building(ref, CampusGraphSourceLevel.DATA_PACKAGE, name = "数据包楼")
        val user = building(ref, CampusGraphSourceLevel.USER_OVERRIDE, name = "用户覆盖楼")
        val crowd = building(ref, CampusGraphSourceLevel.CROWD_REPORT, name = "众包楼")

        val crowdOnly = CampusGraphSnapshot.merge(
            dataPackageEntities = emptyList(),
            userOverrideEntities = emptyList(),
            crowdEntities = listOf(crowd)
        )
        assertEquals("众包楼", (crowdOnly.entity(ref) as BuildingNode).name)

        val userOverCrowd = CampusGraphSnapshot.merge(
            dataPackageEntities = emptyList(),
            userOverrideEntities = listOf(user),
            crowdEntities = listOf(crowd)
        )
        assertEquals("用户覆盖楼", (userOverCrowd.entity(ref) as BuildingNode).name)

        val packOverAll = CampusGraphSnapshot.merge(
            dataPackageEntities = listOf(pack),
            userOverrideEntities = listOf(user),
            crowdEntities = listOf(crowd)
        )
        assertEquals("数据包楼", (packOverAll.entity(ref) as BuildingNode).name)
    }

    @Test
    fun searchUsesNameAndAliasesWithDeterministicOrdering() {
        val first = building(
            CampusGraphIds.build(school, campus, CampusGraphEntityType.BUILDING, "b_t1"),
            CampusGraphSourceLevel.DATA_PACKAGE,
            name = "31号楼（电气信息楼）"
        )
        val second = building(
            CampusGraphIds.build(school, campus, CampusGraphEntityType.BUILDING, "b_t2"),
            CampusGraphSourceLevel.DATA_PACKAGE,
            name = "32号楼（土建楼）"
        )
        val snapshot = CampusGraphSnapshot.of(listOf(first, second))

        val exact = snapshot.search("31号楼（电气信息楼）")
        assertEquals(first.ref, exact.first().entity.ref)
        assertEquals(100, exact.first().score)

        val partial = snapshot.search("号楼")
        assertEquals(listOf(first.ref, second.ref), partial.map { it.entity.ref })
        assertTrue(partial.all { it.score >= 64 })
    }

    private fun building(
        ref: PlaceRef,
        sourceLevel: CampusGraphSourceLevel,
        name: String = "31号楼（电气信息楼）",
        attributes: Map<String, String> = emptyMap()
    ): BuildingNode = BuildingNode(
        ref = ref,
        name = name,
        shortName = "31号楼",
        legacyBuildingId = "b_t1",
        category = CampusCategory.TEACHING_BUILDING,
        parent = campusRef,
        aliases = listOf("31号楼"),
        provenance = provenance(sourceLevel),
        attributes = attributes
    )

    private fun provenance(sourceLevel: CampusGraphSourceLevel): CampusGraphProvenance = CampusGraphProvenance(
        sourceLevel = sourceLevel,
        provenance = CampusGraphProvenance.legacySnapshot(
            schoolId = school,
            providerId = "test-pack",
            sourceSystem = "test",
            sourceRecordId = "fixture"
        ),
        sourceReference = "test fixture"
    )

    private inline fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
