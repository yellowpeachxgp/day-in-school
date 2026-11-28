package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.StudyRoomAvailabilityMeaning
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCampusGraphMigrationTest {

    private val snapshot = LegacyCampusGraphMigration.migrateHautLegacySnapshot()

    @Test
    fun migrationSnapshotCoversAllLegacyDirectoryEntitiesExactlyOnce() {
        assertEquals(2, snapshot.campuses().size)
        assertEquals(39, snapshot.buildings().size)
        assertEquals(64, snapshot.entrances().size)
        assertEquals(42, snapshot.floors().size)
        assertEquals(77, snapshot.rooms().size)
        assertEquals(7, snapshot.facilities().size)
        assertEquals(1, snapshot.services().size)
        assertEquals(232, snapshot.entities.size)
        assertEquals(snapshot.entities.size, snapshot.entities.map { it.ref.id }.distinct().size)
        assertTrue(snapshot.procedures().isEmpty())
        assertTrue(snapshot.indoorNodes().isEmpty())
    }

    @Test
    fun lianhuajieBuildingEntrancesAndAliasesCharacterizationRemainUnchanged() {
        val building = snapshot.buildingByLegacyId("b_t1", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
        assertNotNull(building)
        building!!
        assertEquals("31号楼（电气信息楼）", building.name)
        assertEquals("31号楼", building.shortName)
        assertEquals(34.830310, building.location!!.latitude, 0.0)
        assertEquals(113.540590, building.location!!.longitude, 0.0)
        assertEquals("official-gis-centroid", building.attributes["coordinatePrecision"])
        assertEquals("2026-11-09", building.provenance.verifiedAt.toString())
        assertEquals(6, building.legacyAliases.size)
        assertTrue(building.legacyAliases.any { it.value == "^31[1-6]\\d{2}$" && it.pattern })

        val entrances = snapshot.entrances(building.ref)
        assertEquals(listOf("南侧正门", "东连廊入口", "西侧楼梯口"), entrances.map { it.label })
        assertTrue(entrances.all { it.attributes["coordinatePrecision"] == "building-center" })
    }

    @Test
    fun studyRoomsPreserveBuildingFloorAndNonRealtimeAvailabilitySemantics() {
        val lhjRoom = snapshot.rooms(campusId = LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
            .single { it.roomNumber == "3101" }
        val building = snapshot.entity(lhjRoom.buildingRef) as BuildingNode
        val floor = snapshot.entity(lhjRoom.floorRef!!) as FloorNode
        assertEquals("b_t3", building.legacyBuildingId)
        assertEquals(1, floor.levelIndex)
        assertEquals("1F", floor.floorLabel)
        assertEquals("true", lhjRoom.attributes["studyCandidate"])
        assertEquals(
            StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME.label,
            lhjRoom.attributes["availabilityMeaning"]
        )

        val songshanRoom = snapshot.rooms(campusId = LegacyCampusGraphMigration.SONGSHANLU_CAMPUS_ID)
            .single { it.roomNumber == "嵩山1教101" }
        val songshanBuilding = snapshot.entity(songshanRoom.buildingRef) as BuildingNode
        assertEquals("b_ss_t1", songshanBuilding.legacyBuildingId)
        assertEquals(1, (snapshot.entity(songshanRoom.floorRef!!) as FloorNode).levelIndex)
        assertEquals("campus-block-approximate", songshanBuilding.attributes["coordinatePrecision"])

        val expectedRoomCount = CampusStudyRoomCatalog.ALL_LIANHUAJIE_CANDIDATE_ROOMS.size +
            CampusStudyRoomCatalog.ALL_SONGSHANLU_CANDIDATE_ROOMS.size
        assertEquals(expectedRoomCount + 1, snapshot.rooms().size) // +1 是计算机服务社 W111 场地房间
    }

    @Test
    fun diningMigrationPreservesOperatingCanteensAndDishCatalog() {
        val dining = snapshot.facilities(CampusGraphFacilityKind.DINING)
        assertEquals(CampusDiningRecommender.OPERATING_CANTEENS.size, dining.size)
        assertFalse(dining.any { it.attributes["nearbyBuildingIds"]?.contains("b_canteen2") == true })

        val first = dining.single { it.buildingRef == snapshot.buildingByLegacyId("b_canteen1")!!.ref }
        assertEquals("第一餐厅", first.name)
        assertEquals("东北生活区 · 近3/4教与A/B/C/D公寓", first.attributes["areaLabel"])
        val dish = first.catalogItems.single { it.id == "c1_huimian" }
        assertEquals("二楼河南羊肉烩面", dish.name)
        assertEquals("11", dish.attributes["priceYuan"])
        assertEquals("二楼面食档", dish.attributes["stallLocation"])

        val songshan = dining.single { it.ref.campusId == LegacyCampusGraphMigration.SONGSHANLU_CAMPUS_ID }
        val songshanBuilding = snapshot.entity(songshan.buildingRef!!) as BuildingNode
        assertEquals(songshanBuilding.location!!.latitude, songshan.location!!.latitude, 0.0)
        assertEquals(songshanBuilding.location!!.longitude, songshan.location!!.longitude, 0.0)
    }

    @Test
    fun pharmacyMigrationPreservesEveryMedicineAndPendingVerificationEvidence() {
        val service = snapshot.services(CampusGraphServiceKind.PHARMACY).single()
        assertEquals("校医院药房", service.name)
        assertEquals(CampusHospitalPharmacyCatalog.ITEMS.size, service.catalogItems.size)
        assertTrue(service.provenance.note.orEmpty().contains("不代表实时库存"))
        assertTrue(service.attributes["outpatientReimbursementReference"].orEmpty().contains("实际报销金额以校医院收费窗口"))

        val first = service.catalogItems.first()
        val legacyFirst = CampusHospitalPharmacyCatalog.ITEMS.first()
        assertEquals(legacyFirst.id, first.id)
        assertEquals(legacyFirst.name, first.name)
        assertEquals(legacyFirst.approvalNumber, first.attributes["approvalNumber"])
        assertEquals(legacyFirst.disclosedPriceYuan, first.attributes["disclosedPriceYuan"])
        assertEquals(legacyFirst.verificationStatus, first.attributes["verificationStatus"])

        val discrepancy = service.catalogItems.single { it.id == "med_p1_9" }
        assertEquals("PENDING_OFFICIAL_VERIFICATION", discrepancy.attributes["verificationStatus"])
        assertTrue(discrepancy.attributes["verificationNote"].orEmpty().contains("来源差异"))
    }

    @Test
    fun clubVenueMigrationKeepsF01W111StructuredWithoutFabricatingCoordinates() {
        val clubFacility = snapshot.facilities(CampusGraphFacilityKind.CLUB_VENUE)
            .single { it.attributes["clubId"] == "club_partner_computer_service" }
        assertEquals("莲花街校区 学生宿舍F01 W111", clubFacility.attributes["activityVenue"])
        assertEquals("学生宿舍F01", clubFacility.attributes["buildingName"])
        assertEquals("W111", clubFacility.attributes["roomNumber"])
        assertNull(clubFacility.location)

        val room = snapshot.entity(clubFacility.roomRef!!) as RoomNode
        assertEquals("W111", room.roomNumber)
        assertNull(room.location)
        assertEquals("unknown", room.attributes["coordinatePrecision"])

        val building = snapshot.entity(room.buildingRef) as BuildingNode
        assertEquals("学生宿舍F01", building.name)
        assertNull(building.location)
        assertEquals("b_dorm_ef", building.attributes["containingAreaLegacyBuildingId"])
    }

    @Test
    fun pharmacyCatalogSearchKeepsLegacyQueryResultOrder() {
        for (query in listOf("布洛芬", "0.9%氯化钠", "")) {
            val legacyIds = CampusHospitalPharmacyCatalog.search(query).map { it.id }
            val graphIds = snapshot.searchCatalogItems(
                query = query,
                serviceKind = CampusGraphServiceKind.PHARMACY
            ).map { it.item.id }
            assertEquals("query=" + query, legacyIds, graphIds)
        }
    }

    @Test
    fun roomResolutionKeepsDirectMatchesAndCampusGuardrails() {
        val direct = snapshot.resolveRoomText("3号教学楼3101", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, direct.status)
        assertEquals("b_t3", direct.building?.legacyBuildingId)
        assertEquals("3101", direct.room?.roomNumber)

        val songshan = snapshot.resolveRoomText("嵩山1教101", LegacyCampusGraphMigration.SONGSHANLU_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, songshan.status)
        assertEquals("b_ss_t1", songshan.building?.legacyBuildingId)
        assertEquals("嵩山1教101", songshan.room?.roomNumber)

        val inlineSongshan = snapshot.resolveRoomText("嵩山1教101")
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, inlineSongshan.status)
        assertEquals("b_ss_t1", inlineSongshan.building?.legacyBuildingId)

        val unresolvedAcrossCampus = snapshot.resolveRoomText("3206", LegacyCampusGraphMigration.SONGSHANLU_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, unresolvedAcrossCampus.status)
        assertNull(unresolvedAcrossCampus.building)
        assertFalse(unresolvedAcrossCampus.candidateBuildings.any { it.ref.campusId == LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID })

        val pureNumericWithoutCampus = snapshot.resolveRoomText("3206")
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, pureNumericWithoutCampus.status)
        assertNull(pureNumericWithoutCampus.building)
        assertEquals("numeric-room-needs-campus-context", pureNumericWithoutCampus.reason)
    }

    @Test
    fun unverifiedLegacyAliasesStayOutOfExactAliasesAndResolveAsFuzzy() {
        val building = snapshot.buildingByLegacyId("b_t2", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)!!
        assertFalse(building.aliases.any { it == "A座" })
        assertTrue(building.legacyAliases.any { it.value == "A座" && !it.verified })

        val fuzzy = snapshot.resolveRoomText("A座204", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, fuzzy.status)
        assertEquals("b_t2", fuzzy.building?.legacyBuildingId)
        assertEquals("A座", fuzzy.matchedAlias)
        assertTrue(fuzzy.confidence < 0.85f)
        assertEquals("alias-needs-confirmation", fuzzy.reason)

        val verifiedAlias = snapshot.resolveRoomText("教学楼A204", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, verifiedAlias.status)
        assertEquals("b_t2", verifiedAlias.building?.legacyBuildingId)
    }

    @Test
    fun f01W111ResolutionDoesNotPromoteUnverifiedVenueOrFallIntoEFZoneAsExact() {
        val zone = snapshot.buildingByLegacyId("b_dorm_ef", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)!!
        assertFalse(zone.aliases.any { it.contains("F01") })
        assertTrue(zone.legacyAliases.any { it.value == "学生宿舍F01" && !it.verified })

        val zoneAlias = snapshot.resolveRoomText("宿舍F01 W111", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, zoneAlias.status)
        assertEquals("b_dorm_ef", zoneAlias.building?.legacyBuildingId)
        assertEquals("宿舍F01", zoneAlias.matchedAlias)
        assertTrue(zoneAlias.confidence < 1.0f)

        val fullVenue = snapshot.resolveRoomText("莲花街校区 学生宿舍F01 W111")
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, fullVenue.status)
        assertEquals("学生宿舍F01", fullVenue.building?.name)
        assertFalse(fullVenue.building?.legacyBuildingId == "b_dorm_ef")
        assertEquals("W111", fullVenue.room?.roomNumber)
        assertEquals("W111", fullVenue.roomNumberHint)
        assertNull(fullVenue.building?.location)
        assertNull(fullVenue.room?.location)
        assertTrue(fullVenue.confidence < 1.0f)
        assertEquals("direct-building-needs-verification", fullVenue.reason)

        val roomOnly = snapshot.resolveRoomText("W111", LegacyCampusGraphMigration.LIANHUAJIE_CAMPUS_ID)
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, roomOnly.status)
        assertEquals("room-needs-verification", roomOnly.reason)
        assertTrue(roomOnly.confidence < 1.0f)
    }
}
