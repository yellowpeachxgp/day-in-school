package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

/** 层级引用一致性回归：parent 必须跟随最具体位置引用，快照不得拼出错位层级。 */
class CampusGraphHierarchyValidationTest {

    private val school = SchoolId("haut")
    private val campus = CampusId("lianhuajie")
    private val campusRef = ref(CampusGraphEntityType.CAMPUS, "lianhuajie")

    @Test
    fun constructorsRejectParentLocationMismatch() {
        val buildingNode = building("b-a")
        val floorNode = floor(buildingNode)
        val roomNode = room(buildingNode, floorNode)
        val serviceRef = ref(CampusGraphEntityType.SERVICE, "helpdesk")

        assertThrowsIllegalArgument {
            indoor(buildingNode, room = roomNode, parent = buildingNode.ref)
        }
        assertThrowsIllegalArgument {
            facility(buildingRef = buildingNode.ref, roomRef = roomNode.ref, parent = buildingNode.ref)
        }
        assertThrowsIllegalArgument {
            service(buildingRef = buildingNode.ref, parent = campusRef)
        }
        assertThrowsIllegalArgument {
            procedure(serviceRef = serviceRef, parent = campusRef)
        }

        val defaultRoomParent = indoor(buildingNode, room = roomNode)
        assertEquals(roomNode.ref, defaultRoomParent.parent)
        val validProcedure = procedure(serviceRef = serviceRef, parent = serviceRef)
        assertEquals(serviceRef, validProcedure.parent)
    }

    @Test
    fun snapshotRejectsFacilityAndIndoorReferencesFromDifferentBuildings() {
        val buildingA = building("b-a")
        val buildingB = building("b-b")
        val floorB = floor(buildingB)
        val roomB = room(buildingB, floorB)
        val mismatchedFacility = facility(
            buildingRef = buildingA.ref,
            floorRef = floorB.ref,
            parent = floorB.ref
        )
        val mismatchedIndoor = indoor(buildingA, room = roomB)

        assertThrowsIllegalArgument {
            CampusGraphSnapshot.of(listOf(buildingA, buildingB, floorB, mismatchedFacility))
        }
        assertThrowsIllegalArgument {
            CampusGraphSnapshot.of(listOf(buildingA, buildingB, floorB, roomB, mismatchedIndoor))
        }
    }

    @Test
    fun snapshotRejectsServiceParentOutsideDeclaredBuildingButAcceptsConsistentNestedService() {
        val buildingA = building("b-a")
        val buildingB = building("b-b")
        val floorA = floor(buildingA)
        val floorB = floor(buildingB)
        val mismatched = service(buildingRef = buildingA.ref, parent = floorB.ref)

        assertThrowsIllegalArgument {
            CampusGraphSnapshot.of(listOf(buildingA, buildingB, floorA, floorB, mismatched))
        }

        val consistent = service(buildingRef = buildingA.ref, parent = floorA.ref)
        val snapshot = CampusGraphSnapshot.of(listOf(buildingA, buildingB, floorA, floorB, consistent))
        assertNotNull(snapshot.entity(consistent.ref))
    }

    private fun ref(type: CampusGraphEntityType, slug: String): PlaceRef =
        CampusGraphIds.build(school, campus, type, slug)

    private fun building(slug: String): BuildingNode = BuildingNode(
        ref = ref(CampusGraphEntityType.BUILDING, slug),
        name = slug,
        shortName = slug,
        legacyBuildingId = slug,
        category = CampusCategory.TEACHING_BUILDING,
        parent = campusRef,
        provenance = provenance()
    )

    private fun floor(building: BuildingNode): FloorNode = FloorNode(
        ref = ref(CampusGraphEntityType.FLOOR, CampusGraphIds.slugOf(building.ref) + "-f1"),
        name = "1F",
        buildingRef = building.ref,
        levelIndex = 1,
        floorLabel = "1F",
        provenance = provenance()
    )

    private fun room(building: BuildingNode, floor: FloorNode): RoomNode = RoomNode(
        ref = ref(CampusGraphEntityType.ROOM, CampusGraphIds.slugOf(building.ref) + "-101"),
        name = "101",
        buildingRef = building.ref,
        floorRef = floor.ref,
        roomNumber = "101",
        provenance = provenance()
    )

    private fun indoor(
        building: BuildingNode,
        room: RoomNode? = null,
        parent: PlaceRef? = null
    ): IndoorNode = IndoorNode(
        ref = ref(CampusGraphEntityType.INDOOR_NODE, CampusGraphIds.slugOf(building.ref) + "-center"),
        name = "室内节点",
        buildingRef = building.ref,
        floorRef = room?.floorRef,
        roomRef = room?.ref,
        nodeKind = if (room == null) CampusGraphIndoorNodeKind.CORRIDOR else CampusGraphIndoorNodeKind.ROOM_CENTER,
        parent = parent ?: (room?.ref ?: building.ref),
        provenance = provenance()
    )

    private fun facility(
        buildingRef: PlaceRef?,
        floorRef: PlaceRef? = null,
        roomRef: PlaceRef? = null,
        parent: PlaceRef
    ): FacilityNode = FacilityNode(
        ref = ref(CampusGraphEntityType.FACILITY, "facility"),
        name = "设施",
        kind = CampusGraphFacilityKind.OTHER,
        buildingRef = buildingRef,
        floorRef = floorRef,
        roomRef = roomRef,
        parent = parent,
        provenance = provenance()
    )

    private fun service(buildingRef: PlaceRef?, parent: PlaceRef): ServiceNode = ServiceNode(
        ref = ref(CampusGraphEntityType.SERVICE, "service"),
        name = "服务",
        kind = CampusGraphServiceKind.OTHER,
        buildingRef = buildingRef,
        parent = parent,
        provenance = provenance()
    )

    private fun procedure(serviceRef: PlaceRef?, parent: PlaceRef): ProcedureNode = ProcedureNode(
        ref = ref(CampusGraphEntityType.PROCEDURE, "procedure"),
        name = "流程",
        serviceRef = serviceRef,
        parent = parent,
        provenance = provenance()
    )

    private fun provenance(): CampusGraphProvenance = CampusGraphProvenance(
        sourceLevel = CampusGraphSourceLevel.DATA_PACKAGE,
        provenance = CampusGraphProvenance.legacySnapshot(
            schoolId = school,
            providerId = "hierarchy-test",
            sourceSystem = "test",
            sourceRecordId = "fixture"
        ),
        sourceReference = "hierarchy fixture",
        verifiedAt = LocalDate.of(2026, 1, 1)
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
