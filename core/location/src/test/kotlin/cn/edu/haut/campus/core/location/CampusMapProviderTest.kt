package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityState
import cn.edu.haut.campus.core.model.SchoolId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusMapProviderTest {

    @Test
    fun registry_resolvesHautLianhuajieAndReturnsUnsupportedForOtherSchools() {
        val registry = CampusMapProviderRegistry.Default
        val haut = registry.resolve(
            SchoolDirectory.HAUT_SCHOOL_ID,
            SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID
        )
        assertNotNull(haut)
        assertEquals("haut-lianhuajie-gis", haut!!.providerId)

        assertNull(registry.resolve(SchoolDirectory.HAUT_SCHOOL_ID, CampusId("unknown-campus")))
        assertNull(registry.resolve(SchoolId("other-school")))

        val unsupported = registry.capabilityStatus(SchoolId("other-school"))
        assertFalse(unsupported.providerSupports)
        assertEquals(CapabilityState.UNSUPPORTED, unsupported.state)

        val originBuilding = Building(
            id = "b_t1",
            name = "1号教学楼（莲花街校区）",
            shortName = "1教",
            latitude = 34.82975,
            longitude = 113.54500,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门主入口"),
            description = "1号教学楼",
            verifiedAt = LocalDate.of(2026, 10, 1)
        )
        val destinationBuilding = Building(
            id = "b_lib",
            name = "图书馆（莲花街校区）",
            shortName = "图书馆",
            latitude = 34.83080,
            longitude = 113.54685,
            category = CampusCategory.LIBRARY,
            entrances = listOf("东门主入口"),
            description = "图书馆",
            verifiedAt = LocalDate.of(2026, 10, 1)
        )
        val plan = haut.buildWalkingRoute(
            userLocation = null,
            fallbackOriginBuilding = originBuilding,
            destinationBuilding = destinationBuilding,
            allBuildings = listOf(originBuilding, destinationBuilding),
            rawClassroom = "1104"
        )
        assertTrue(plan.routePoints.size >= 2)
        assertTrue(plan.estimatedWalkDistanceMeters > 0)
    }

    @Test
    fun officialGisClient_infersFloorIndexAndMatchesBuildingsAccurately() {
        assertEquals(0, HautOfficialGisClient.inferFloorIndexFromRoomQuery("3101"))
        assertEquals(3, HautOfficialGisClient.inferFloorIndexFromRoomQuery("莲花街 3号楼3402"))
        assertEquals(1, HautOfficialGisClient.inferFloorIndexFromRoomQuery("31205"))
        assertEquals(2, HautOfficialGisClient.inferFloorIndexFromRoomQuery("5号楼 3层 5308"))
        assertEquals("3104", HautOfficialGisClient.extractCleanRoomCode("莲花街校区 3号楼3104"))
        assertEquals("31205", HautOfficialGisClient.extractCleanRoomCode("31号楼31205"))

        val b3 = Building(
            id = "b_t3",
            name = "3号楼（文科楼）",
            shortName = "3号楼",
            latitude = 34.83013,
            longitude = 113.54798,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门"),
            description = "文科楼",
            verifiedAt = LocalDate.of(2026, 11, 5)
        )
        val resolved = HautOfficialGisClient.resolveFeatureIdForBuilding(b3, null)
        assertNotNull(resolved)
        assertEquals("12866", resolved!!.first)
        assertEquals(5, resolved.second)
        assertEquals(GisIndoorRoomCategory.CLASSROOM, HautOfficialGisClient.classifyRoomCategory("classroom", "3101"))
        assertEquals(GisIndoorRoomCategory.LABORATORY, HautOfficialGisClient.classifyRoomCategory("laboratory", "机房5204"))
    }

    @Test
    fun tileCoordinator_separatesStreetPlaceholderFrom25dTargetAndHandlesDiskAndRetry() {
        kotlinx.coroutines.runBlocking {
            val coordinator = CampusTileCacheCoordinator<String>(maxMemoryEntries = 32, failureCooldownMs = 10_000L)
            // Pick a valid z=17 tile strictly inside Lianhuajie bounds (lat=34.831, lon=113.545 -> x=106878, y=51996)
            val z = 17
            val tx = 106878
            val ty = 51996
            assertTrue(coordinator.isWithinHaut25dRasterBounds(z, tx, ty))

            val diskStore = mutableMapOf<String, String>()
            val requestedUrls = mutableListOf<String>()

            // 1) Standard -> 2.5D: viewing STREET_STANDARD caches street_17_tx_ty, then switching to OFFICIAL_25D_2026
            //    MUST return street as placeholder AND still trigger official 2.5D fetch!
            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.STREET_STANDARD,
                z = z,
                x = tx,
                y = ty,
                nowMs = 1_000L,
                readDiskTile = { diskStore[it] },
                fetchRemoteTile = { key, url ->
                    requestedUrls += url
                    "STREET_TILE".also { diskStore[key] = it }
                },
                onLoaded = {}
            )
            assertEquals("STREET_TILE", coordinator.lookupForRender(GisMapStyleMode.STREET_STANDARD, z, tx, ty, 1_100L).tile)

            val lookup25dWhileOnlyStreetCached = coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx, ty, 1_200L)
            assertEquals("STREET_TILE", lookup25dWhileOnlyStreetCached.tile)
            assertTrue(lookup25dWhileOnlyStreetCached.isPlaceholder)
            assertTrue("Having street placeholder MUST NOT suppress 2.5D target load!", lookup25dWhileOnlyStreetCached.shouldTriggerTargetLoad)

            var reloadedCount = 0
            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.OFFICIAL_25D_2026,
                z = z,
                x = tx,
                y = ty,
                nowMs = 1_200L,
                readDiskTile = { diskStore[it] },
                fetchRemoteTile = { key, url ->
                    requestedUrls += url
                    assertTrue(url.contains("gis.haut.edu.cn/cmgis-server/upload/raster/3"))
                    "OFFICIAL_25D_TILE".also { diskStore[key] = it }
                },
                onLoaded = { reloadedCount++ }
            )
            assertEquals(1, reloadedCount)
            val lookupAfter25dLoaded = coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx, ty, 1_300L)
            assertEquals("OFFICIAL_25D_TILE", lookupAfter25dLoaded.tile)
            assertFalse(lookupAfter25dLoaded.isPlaceholder)
            assertFalse(lookupAfter25dLoaded.shouldTriggerTargetLoad)

            // 2) 2.5D -> Standard: switching back to STREET_STANDARD serves STREET_TILE, never OFFICIAL_25D_TILE!
            val lookupBackToStreet = coordinator.lookupForRender(GisMapStyleMode.STREET_STANDARD, z, tx, ty, 1_400L)
            assertEquals("STREET_TILE", lookupBackToStreet.tile)
            assertFalse(lookupBackToStreet.shouldTriggerTargetLoad)

            // 3) Disk hot cache: clearing memory cache while diskStore retains haut25d loads from disk with zero network requests!
            coordinator.clearAll()
            val netBeforeDiskHit = requestedUrls.size
            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.OFFICIAL_25D_2026,
                z = z,
                x = tx,
                y = ty,
                nowMs = 2_000L,
                readDiskTile = { diskStore[it] },
                fetchRemoteTile = { _, url ->
                    requestedUrls += url
                    "SHOULD_NOT_FETCH"
                },
                onLoaded = {}
            )
            assertEquals(netBeforeDiskHit, requestedUrls.size)
            assertEquals("OFFICIAL_25D_TILE", coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx, ty, 2_100L).tile)

            // 4) Failure & retry: when official 2.5D fails on tile (tx+1, ty), falls back to street placeholder, cools down, and retries cleanly after cooldown/reset!
            val tx2 = tx + 1
            var officialFail = true
            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.OFFICIAL_25D_2026,
                z = z,
                x = tx2,
                y = ty,
                nowMs = 5_000L,
                readDiskTile = { diskStore[it] },
                fetchRemoteTile = { key, url ->
                    if (url.contains("gis.haut.edu.cn")) {
                        if (officialFail) null else "RECOVERED_25D".also { diskStore[key] = it }
                    } else {
                        "FALLBACK_STREET".also { diskStore[key] = it }
                    }
                },
                onLoaded = {}
            )
            val duringCooldown = coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx2, ty, 6_000L)
            assertEquals("FALLBACK_STREET", duringCooldown.tile)
            assertTrue(duringCooldown.isPlaceholder)
            assertFalse("Must respect failure cooldown to avoid per-frame retry storm", duringCooldown.shouldTriggerTargetLoad)

            // After mode re-toggle (clearFailureCooldowns) or cooldown expiry, retry succeeds and upgrades to RECOVERED_25D!
            officialFail = false
            coordinator.clearFailureCooldowns()
            assertTrue(coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx2, ty, 6_500L).shouldTriggerTargetLoad)
            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.OFFICIAL_25D_2026,
                z = z,
                x = tx2,
                y = ty,
                nowMs = 6_500L,
                readDiskTile = { diskStore[it] },
                fetchRemoteTile = { key, url ->
                    if (url.contains("gis.haut.edu.cn")) "RECOVERED_25D".also { diskStore[key] = it } else null
                },
                onLoaded = {}
            )
            assertEquals("RECOVERED_25D", coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx2, ty, 6_600L).tile)
        }
    }

    @Test
    fun staticViewportCooldownExpiryAutomaticallyWakesUpAndRetriesWithoutUserAction() {
        kotlinx.coroutines.runBlocking {
            val coordinator = CampusTileCacheCoordinator<String>(maxMemoryEntries = 32, failureCooldownMs = 10_000L)
            val z = 17
            val tx = 106878
            val ty = 51996
            var virtualNowMs = 5_000L
            var networkOnline = false
            var tileLoadGeneration = 0
            var cooldownScheduleEpoch = 0
            var renderedTile: String? = null
            var renderedPlaceholder = false
            val delayCalls = mutableListOf<Long>()

            suspend fun renderStaticViewportPass() {
                val lookup = coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx, ty, virtualNowMs)
                renderedTile = lookup.tile
                renderedPlaceholder = lookup.isPlaceholder
                if (lookup.shouldTriggerTargetLoad) {
                    coordinator.loadTileIfNeeded(
                        styleMode = GisMapStyleMode.OFFICIAL_25D_2026,
                        z = z,
                        x = tx,
                        y = ty,
                        nowMs = virtualNowMs,
                        readDiskTile = { null },
                        fetchRemoteTile = { _, url ->
                            if (url.contains("gis.haut.edu.cn")) {
                                if (networkOnline) "RECOVERED_25D_WITHOUT_PAN" else null
                            } else {
                                "STREET_PLACEHOLDER"
                            }
                        },
                        onLoaded = { tileLoadGeneration += 1 },
                        onCooldownScheduled = { cooldownScheduleEpoch += 1 }
                    )
                    val refreshed = coordinator.lookupForRender(GisMapStyleMode.OFFICIAL_25D_2026, z, tx, ty, virtualNowMs)
                    renderedTile = refreshed.tile
                    renderedPlaceholder = refreshed.isPlaceholder
                }
            }

            // 1) Initial static viewport render while offline -> loads STREET_PLACEHOLDER and schedules cooldown (until 15_000ms)
            renderStaticViewportPass()
            assertEquals("STREET_PLACEHOLDER", renderedTile)
            assertTrue(renderedPlaceholder)
            assertEquals("Cooldown wakeup epoch must increment once (no per-frame storm)", 1, cooldownScheduleEpoch)
            assertEquals(15_000L, coordinator.nextCooldownExpiryEpochMs(virtualNowMs))

            // 2) Network recovers at t=8_000ms while viewport stays 100% static (NO pan, NO style toggle, NO clearFailureCooldowns!)
            virtualNowMs = 8_000L
            networkOnline = true

            // Lifecycle wakeup suspends for exact remaining cooldown (15_000 - 8_000 = 7_000ms), then wakes up and triggers redraw!
            val wokeUp = coordinator.awaitNextCooldownBatchExpiry(
                nowMsProvider = { virtualNowMs },
                delayMillis = { waitMs ->
                    delayCalls += waitMs
                    virtualNowMs += waitMs
                }
            )
            assertTrue(wokeUp)
            assertEquals(listOf(7_000L), delayCalls)
            tileLoadGeneration += 1
            renderStaticViewportPass()

            assertEquals("RECOVERED_25D_WITHOUT_PAN", renderedTile)
            assertFalse(renderedPlaceholder)
            assertEquals(null, coordinator.nextCooldownExpiryEpochMs(virtualNowMs))
        }
    }

    @Test
    fun slowFailureTimelineAndMultiBatchAndBoundedRetryWakeUpCleanly() {
        kotlinx.coroutines.runBlocking {
            // Item #4 exact timeline reproduction:
            // failureCooldownMs = 45_000 -> street cooldown = 15_000.
            // Request starts at t=1_000; 2 street URLs each fail taking 9_000ms -> finishes at t=19_000!
            // Unpatched code recorded expiry = 1_000 + 15_000 = 16_000 (< 19_000), then awaitNextCooldownBatchExpiry(19_000) evicted 16_000 and returned FALSE!
            val coordinator = CampusTileCacheCoordinator<String>(maxMemoryEntries = 32, failureCooldownMs = 45_000L)
            val z = 16
            val tx = 53439
            val ty = 25998
            var virtualNowMs = 1_000L
            var networkOnline = false
            var onLoadedCount = 0
            var cooldownSignals = 0

            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.STREET_STANDARD,
                z = z,
                x = tx,
                y = ty,
                nowMs = virtualNowMs,
                readDiskTile = { null },
                fetchRemoteTile = { _, _ ->
                    virtualNowMs += 9_000L
                    if (networkOnline) "STREET_RECOVERED_AFTER_SLOW_FAIL" else null
                },
                onLoaded = { onLoadedCount += 1 },
                onCooldownScheduled = { cooldownSignals += 1 }
            )
            assertEquals(19_000L, virtualNowMs)
            assertEquals(0, onLoadedCount)
            assertEquals(1, cooldownSignals)

            // Network recovers after slow failure while viewport stays completely static:
            networkOnline = true
            val wokeUpAfterSlowFail = coordinator.awaitNextCooldownBatchExpiry(
                nowMsProvider = { virtualNowMs },
                delayMillis = { waitMs ->
                    assertTrue("Delay must be positive to prevent busy loop", waitMs > 0L)
                    virtualNowMs += waitMs
                }
            )
            assertTrue(
                "Slow failure completing at t=19_000 MUST still produce a wakeup retry signal!",
                wokeUpAfterSlowFail
            )

            val retryLookup = coordinator.lookupForRender(GisMapStyleMode.STREET_STANDARD, z, tx, ty, virtualNowMs)
            assertTrue(retryLookup.shouldTriggerTargetLoad)
            coordinator.loadTileIfNeeded(
                styleMode = GisMapStyleMode.STREET_STANDARD,
                z = z,
                x = tx,
                y = ty,
                nowMs = virtualNowMs,
                readDiskTile = { null },
                fetchRemoteTile = { _, _ -> "STREET_RECOVERED_AFTER_SLOW_FAIL" },
                onLoaded = { onLoadedCount += 1 }
            )
            assertEquals(1, onLoadedCount)
            assertEquals(
                "STREET_RECOVERED_AFTER_SLOW_FAIL",
                coordinator.lookupForRender(GisMapStyleMode.STREET_STANDARD, z, tx, ty, virtualNowMs).tile
            )

            // 2) Multi-batch cooldown wakeup: Tile A (street, 5s cooldown) + Tile B (2.5D, 15s cooldown) wake in 2 ordered batches!
            val multiCoord = CampusTileCacheCoordinator<String>(
                maxMemoryEntries = 32,
                failureCooldownMs = 15_000L,
                maxAutoRetriesPerTile = 3,
                minRetryDebounceMs = 200L,
                defaultClockMs = { virtualNowMs }
            )
            virtualNowMs = 10_000L
            // Tile A (outside 2.5D bounds -> street, cooldown = 15_000/3 = 5_000 -> until 15_000)
            multiCoord.loadTileIfNeeded(
                styleMode = GisMapStyleMode.STREET_STANDARD,
                z = 14,
                x = 100,
                y = 100,
                nowMs = virtualNowMs,
                nowMsProvider = { virtualNowMs },
                readDiskTile = { null },
                fetchRemoteTile = { _, _ -> null },
                onLoaded = {}
            )
            // Tile B (inside 2.5D bounds -> 2.5D, cooldown = 15_000 -> until 25_000)
            multiCoord.loadTileIfNeeded(
                styleMode = GisMapStyleMode.OFFICIAL_25D_2026,
                z = 17,
                x = 106878,
                y = 51996,
                nowMs = virtualNowMs,
                nowMsProvider = { virtualNowMs },
                readDiskTile = { null },
                fetchRemoteTile = { _, _ -> null },
                onLoaded = {}
            )
            val batchDelays = mutableListOf<Long>()
            assertTrue(
                multiCoord.awaitNextCooldownBatchExpiry(
                    nowMsProvider = { virtualNowMs },
                    delayMillis = { w -> batchDelays += w; virtualNowMs += w }
                )
            )
            assertEquals(15_000L, virtualNowMs)
            assertTrue(
                multiCoord.awaitNextCooldownBatchExpiry(
                    nowMsProvider = { virtualNowMs },
                    delayMillis = { w -> batchDelays += w; virtualNowMs += w }
                )
            )
            assertEquals(25_000L, virtualNowMs)
            assertEquals(listOf(5_000L, 10_000L), batchDelays)

            // 3) Bounded retries (maxAutoRetriesPerTile = 2) prevent infinite busy loop while offline, and onForegroundResume() recovers!
            val boundedCoord = CampusTileCacheCoordinator<String>(
                maxMemoryEntries = 32,
                failureCooldownMs = 6_000L,
                maxAutoRetriesPerTile = 2,
                minRetryDebounceMs = 200L,
                defaultClockMs = { virtualNowMs }
            )
            virtualNowMs = 50_000L
            var loadPassesMade = 0
            var urlFetchCallsMade = 0
            while (true) {
                val lk = boundedCoord.lookupForRender(GisMapStyleMode.STREET_STANDARD, 14, 200, 200, virtualNowMs)
                if (lk.shouldTriggerTargetLoad) {
                    loadPassesMade += 1
                    boundedCoord.loadTileIfNeeded(
                        styleMode = GisMapStyleMode.STREET_STANDARD,
                        z = 14,
                        x = 200,
                        y = 200,
                        nowMs = virtualNowMs,
                        nowMsProvider = { virtualNowMs },
                        readDiskTile = { null },
                        fetchRemoteTile = { _, _ ->
                            urlFetchCallsMade += 1
                            null
                        },
                        onLoaded = {}
                    )
                }
                val woke = boundedCoord.awaitNextCooldownBatchExpiry(
                    nowMsProvider = { virtualNowMs },
                    delayMillis = { w -> virtualNowMs += w }
                )
                if (!woke) break
                assertTrue("Must terminate within bounded retries", loadPassesMade <= 5)
            }
            // Initial pass (1) + 2 auto retries = 3 bounded load passes total (6 URL calls across primary+fallback), then stops cleanly!
            assertEquals(3, loadPassesMade)
            assertEquals(6, urlFetchCallsMade)
            assertFalse(
                boundedCoord.lookupForRender(GisMapStyleMode.STREET_STANDARD, 14, 200, 200, virtualNowMs + 60_000L).shouldTriggerTargetLoad
            )

            // Foreground resume resets exhaustion so returning from background immediately allows fresh load!
            assertTrue(boundedCoord.onForegroundResume(virtualNowMs + 60_000L))
            assertTrue(
                boundedCoord.lookupForRender(GisMapStyleMode.STREET_STANDARD, 14, 200, 200, virtualNowMs + 60_000L).shouldTriggerTargetLoad
            )
        }
    }
}
