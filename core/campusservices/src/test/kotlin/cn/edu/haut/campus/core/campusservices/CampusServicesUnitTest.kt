package cn.edu.haut.campus.core.campusservices

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

class CampusServicesUnitTest {

    @Test
    fun officialServiceRoutePolicy_enforcesHttpsAndDomainWhitelist() {
        assertTrue(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("https://libspace.haut.edu.cn/mspace/index"))
        assertTrue(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("https://wslib.haut.edu.cn/h5/index.html"))
        assertTrue(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("https://mapp.haut.edu.cn/cas/login"))
        assertTrue(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("https://jwglxt.haut.edu.cn/jwglxt"))

        // Reject HTTP, file, javascript, content, and external domains
        assertFalse(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("http://libspace.haut.edu.cn/mspace/index"))
        assertFalse(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("file:///android_asset/secret.txt"))
        assertFalse(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("javascript:alert(1)"))
        assertFalse(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("content://cn.edu.haut.campus/file"))
        assertFalse(OfficialServiceRoutePolicy.isAllowedMainFrameUrl("https://evil-haut.edu.cn.attacker.com/login"))

        // Cover URL sanitization strips query/fragment and blocks untrusted hosts
        assertEquals(
            "https://img.hwlsp.cn/book/cover123.jpg",
            OfficialServiceRoutePolicy.sanitizeCoverUrl("https://img.hwlsp.cn/book/cover123.jpg?token=secret#frag")
        )
        assertEquals(
            "",
            OfficialServiceRoutePolicy.sanitizeCoverUrl("https://evil.example.com/cover.jpg")
        )
    }

    @Test
    fun wanxiaoProtocolParser_parsesRoomAndDevicePayloadsCorrectly() {
        val roomJson = """
            {
              "code_": "0",
              "result_": "true",
              "body": "{\"result\":\"0\",\"existflag\":\"2\",\"roomlist\":[{\"roomfullname\":\"莲花街3号楼-412\",\"roomverify\":\"3-412-V\"}]}"
            }
        """.trimIndent()
        val roomBody = WanxiaoProtocolParser.parseEnvelopeBody(roomJson)
        val boundRooms = WanxiaoProtocolParser.parseBoundRooms(roomBody)
        assertEquals(1, boundRooms.size)
        assertEquals("莲花街3号楼-412", boundRooms.first().roomName)
        assertEquals("3-412-V", boundRooms.first().roomVerify)

        val billJson = """
            {
              "code_": "0",
              "result_": "true",
              "body": {
                "result": "0",
                "modlist": [
                  {
                    "devicename": "照明电表",
                    "bussnesstype": "0",
                    "odd": "58.40度",
                    "todayuse": "3.20度",
                    "sumbuy": "1240.50度",
                    "status": "正常"
                  },
                  {
                    "devicename": "冷水表",
                    "bussnesstype": "1",
                    "odd": "12.00吨",
                    "todayuse": "0.35吨",
                    "sumbuy": "85.60吨",
                    "status": "正常"
                  }
                ]
              }
            }
        """.trimIndent()
        val billBody = WanxiaoProtocolParser.parseEnvelopeBody(billJson)
        val devices = WanxiaoProtocolParser.parseDevices(billBody)
        assertEquals(2, devices.size)

        val elec = devices.first { it.category == UtilityMeterCategory.ELECTRICITY }
        assertEquals("照明电表", elec.name)
        assertEquals("58.40度", elec.balanceRaw)
        val parsedToday = WanxiaoProtocolParser.parseElectricTodayUseKwh(elec.todayUseRaw)
        assertNotNull(parsedToday)
        assertEquals(3.20, parsedToday!!.first, 0.001)
        assertEquals("kWh", parsedToday.second)
    }

    @Test
    fun wanxiaoFormPostTransport_blocksWhenTransportUnverifiedAndEncodesPostBody() {
        val unverifiedTransport = WanxiaoFormPostTransport(
            verificationState = UtilityTransportVerificationState.TRANSPORT_UNVERIFIED
        )
        try {
            kotlinx.coroutines.runBlocking {
                unverifiedTransport.postForm(
                    method = "getbindroom",
                    payload = JSONObject().put("account", "202401020304"),
                    allowUnverifiedTransport = false
                )
            }
            fail("Expected UnverifiedTransportBlockedException when allowUnverifiedTransport = false")
        } catch (expected: UtilityTransportException.UnverifiedTransportBlockedException) {
            assertTrue(expected.message!!.contains("待核验"))
        }

        // Rejects endpoint URL with query string
        try {
            WanxiaoFormPostTransport.validateCleanHttpsEndpoint(
                "https://xqh5.17wanxiao.com/smartWaterAndElectricityService/SWAEServlet?account=202401020304"
            )
            fail("Expected SecurityViolationException when query parameters exist in URL")
        } catch (expected: UtilityTransportException.SecurityViolationException) {
            assertTrue(expected.message!!.contains("查询参数"))
        }

        val encodedBytes = WanxiaoFormPostTransport.encodeFormBody(
            customerCode = "43",
            method = "getbindroom",
            paramJson = "{\"cmd\":\"getbindroom\",\"account\":\"202401020304\"}"
        )
        val encodedText = String(encodedBytes, StandardCharsets.UTF_8)
        assertTrue(encodedText.startsWith("customercode=43&method=getbindroom&param="))
        assertFalse(encodedText.startsWith("?"))
    }

    @Test
    fun utilityAccountSecureStore_masksSensitiveAccountNumber() {
        assertEquals("2024****0304", UtilityAccountSecureStore.maskAccount("202401020304"))
        assertEquals("1**4", UtilityAccountSecureStore.maskAccount("1234"))
    }

    @Test
    fun utilitySamplingPolicy_enforcesIntervalsAndRejectsCrossMidnightAndDuplicateDevices() {
        val zone = ZoneId.of("Asia/Shanghai")
        val tenAm = LocalDateTime.of(2026, 11, 1, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val tenTwentyAm = LocalDateTime.of(2026, 11, 1, 10, 20).atZone(zone).toInstant().toEpochMilli()
        val elevenFiveAm = LocalDateTime.of(2026, 11, 1, 11, 5).atZone(zone).toInstant().toEpochMilli()

        // Daytime < 60m -> false; >= 60m -> true
        assertFalse(UtilitySamplingPolicy.shouldSample(tenAm, tenTwentyAm))
        assertTrue(UtilitySamplingPolicy.shouldSample(tenAm, elevenFiveAm))

        // Cross-midnight request (started 23:59, finished 00:01 next day) -> discarded
        val beforeMidnight = LocalDateTime.of(2026, 11, 1, 23, 59, 30).atZone(zone).toInstant().toEpochMilli()
        val afterMidnight = LocalDateTime.of(2026, 11, 2, 0, 0, 30).atZone(zone).toInstant().toEpochMilli()
        val sampleRoom = OnlineUtilityRoomSnapshot(
            roomId = "room-1",
            roomName = "3-412",
            devices = listOf(
                OnlineUtilityDevice("照明电表", UtilityMeterCategory.ELECTRICITY, "50度", "3.5度", "100度", "正常")
            ),
            fetchedAtEpochMs = afterMidnight
        )
        val crossMidnightMerged = UtilitySamplingPolicy.mergeDailySamples(
            existingSamples = emptyList(),
            rooms = listOf(sampleRoom),
            startedAtEpochMs = beforeMidnight,
            finishedAtEpochMs = afterMidnight
        )
        assertTrue(crossMidnightMerged.isEmpty())

        // Duplicate device names in same room -> discarded
        val dupRoom = OnlineUtilityRoomSnapshot(
            roomId = "room-1",
            roomName = "3-412",
            devices = listOf(
                OnlineUtilityDevice("照明电表", UtilityMeterCategory.ELECTRICITY, "50度", "3.5度", "100度", "正常"),
                OnlineUtilityDevice("照明电表", UtilityMeterCategory.ELECTRICITY, "40度", "2.1度", "100度", "正常")
            ),
            fetchedAtEpochMs = tenAm
        )
        val dupMerged = UtilitySamplingPolicy.mergeDailySamples(
            existingSamples = emptyList(),
            rooms = listOf(dupRoom),
            startedAtEpochMs = tenAm,
            finishedAtEpochMs = tenAm + 500L
        )
        assertTrue(dupMerged.isEmpty())
    }

    @Test
    fun libraryCatalogSnapshot_parsesDetailAndHoldings() {
        val rawJson = """
            {
              "kind": "detail",
              "title": "深入理解计算机系统",
              "coverUrl": "https://img.hwlsp.cn/cover/csapp.jpg?tracking=1",
              "bibliography": "著者：Randal E. Bryant\n出版社：机械工业出版社",
              "description": "计算机系统经典教材",
              "holdings": [
                {
                  "location": "莲花街自然科学借阅室",
                  "lines": ["索书号：TP301/B915", "条码号：012345"],
                  "status": "可借",
                  "shelf": "3楼A区"
                },
                {
                  "location": "莲花街自然科学借阅室",
                  "lines": ["索书号：TP301/B915", "条码号：012346"],
                  "status": "借出",
                  "shelf": "3楼A区"
                }
              ]
            }
        """.trimIndent()
        val snapshot = LibraryCatalogSnapshot.parseSnapshot(rawJson, pageGeneration = 2L)
        assertTrue(snapshot is CatalogPageSnapshot.Detail)
        val detail = (snapshot as CatalogPageSnapshot.Detail).detail
        assertEquals("深入理解计算机系统", detail.title)
        assertEquals("https://img.hwlsp.cn/cover/csapp.jpg", detail.coverUrl)
        assertEquals(2, detail.totalHoldingsCount)
        assertEquals(1, detail.availableHoldingsCount)

        // Protocol-relative ddimg cover URL is upgraded to https://
        assertEquals(
            "https://img3m1.ddimg.cn/22/24/29359561-1_w_6.jpg",
            OfficialServiceRoutePolicy.sanitizeCoverUrl("//img3m1.ddimg.cn/22/24/29359561-1_w_6.jpg")
        )

        // Search URL uses Huiwen OPAC strSearchType & strText parameters
        val searchUrl = LibraryCatalogSnapshot.buildSearchUrl("数据结构", "keyword")
        assertTrue(searchUrl.contains("strSearchType=keyword"))
        assertTrue(searchUrl.contains("strText="))
    }

    @Test
    fun hautLibraryOpacClient_parsesSearchAndDetailResponsesCorrectly() {
        val sampleSearchJson = """
            {
              "code": 0,
              "msg": null,
              "data": {
                "total": 12,
                "dataList": [
                  {
                    "bibId": "m7e3788925dc6cfeb9cd7d3a74db7fae2",
                    "title": "数据结构:C语言版",
                    "author": "严蔚敏, 李冬梅编著",
                    "publisher": "人民邮电出版社",
                    "pub_year": "2022.01",
                    "isbn": "978-7-115-57666-8",
                    "callno": ["TP311.12-43/30-2.2"],
                    "docTypeDesc": "中文图书",
                    "itemCount": 2,
                    "circCount": 1,
                    "abstract": "数据结构经典教材",
                    "holdings": "[{\"location\":\"总馆 - X81 八楼西书库\",\"tempLocation\":\"总馆 - 01 嵩山路流通库\",\"callNo\":\"TP311.12-43/30-2.2\",\"barCode\":\"111856016\",\"status\":\"可借\",\"dueDate\":null,\"shelfMark\":\"03架A面\"},{\"location\":\"总馆 - X81 八楼西书库\",\"tempLocation\":\"总馆 - X81 八楼西书库\",\"callNo\":\"TP311.12-43/30-2.2\",\"barCode\":\"111856017\",\"status\":\"借出\",\"dueDate\":\"2026-11-20\",\"shelfMark\":null}]"
                  }
                ]
              }
            }
        """.trimIndent()

        val pageResult = HautLibraryOpacClient.parseSearchResponse(
            rawJson = sampleSearchJson,
            keyword = "数据结构",
            field = LibrarySearchField.ALL,
            page = 1,
            pageSize = 20
        )
        assertEquals(12, pageResult.totalCount)
        assertEquals(1, pageResult.books.size)
        val book = pageResult.books.first()
        assertEquals("数据结构:C语言版", book.title)
        assertEquals("m7e3788925dc6cfeb9cd7d3a74db7fae2", book.bibId)
        assertEquals("TP311.12-43/30-2.2", book.callNo)
        assertEquals(1, book.availableCount)
        assertEquals(2, book.totalCount)
        assertEquals(2, book.inlineHoldings.size)
        assertTrue(book.inlineHoldings.first().isAvailable)
        assertEquals("架位：03架A面", book.inlineHoldings.first().shelf)

        val extendJson = """
            {
              "code": 0,
              "data": {
                "m7e3788925dc6cfeb9cd7d3a74db7fae2": [
                  {
                    "_id": "9787115576668",
                    "imageUrl": "//img3m1.ddimg.cn/22/24/29359561-1_w_6.jpg"
                  }
                ]
              }
            }
        """.trimIndent()
        val coverMap = HautLibraryOpacClient.parseExtend3CoverMap(extendJson)
        assertEquals(
            "https://img3m1.ddimg.cn/22/24/29359561-1_w_6.jpg",
            coverMap["m7e3788925dc6cfeb9cd7d3a74db7fae2"]
        )
    }

    @Test
    fun legalNoticeManifest_filesExistAndContainUpstreamCommitAndLicense() {
        val rootDir = sequenceOf(File("."), File(".."), File("../.."))
            .map { it.canonicalFile }
            .firstOrNull { File(it, "THIRD_PARTY_NOTICES.md").exists() }
        assertNotNull("Repository root with THIRD_PARTY_NOTICES.md must exist", rootDir)

        val licenseFile = File(rootDir!!, "licenses/HAUTPocket-MIT.txt")
        val noticesFile = File(rootDir, "THIRD_PARTY_NOTICES.md")
        val provenanceFile = File(rootDir, "docs/HAUTPOCKET_MIGRATION_PROVENANCE.md")

        assertTrue(licenseFile.exists())
        assertTrue(noticesFile.exists())
        assertTrue(provenanceFile.exists())

        val licenseText = licenseFile.readText()
        assertTrue(licenseText.contains("MIT License"))
        assertTrue(licenseText.contains("HAUTPocket contributors"))

        val noticesText = noticesFile.readText()
        assertTrue(noticesText.contains("rainthink-vanilla"))
        assertTrue(noticesText.contains("aa83d6f7200ebde4a816e91191f19d9990563362"))
        assertTrue(noticesText.contains("MIT"))
    }

    @Test
    fun campusServiceProviderRegistry_isolatesHautProvidersAndRejectsUnknownSchools() {
        val registry = CampusServiceProviderRegistry.Default
        val hautId = cn.edu.haut.campus.core.domain.SchoolDirectory.HAUT_SCHOOL_ID
        val otherSchool = cn.edu.haut.campus.core.model.SchoolId("other-university")

        assertNotNull(registry.libraryCatalog(hautId))
        assertNotNull(registry.librarySeats(hautId))
        assertNotNull(registry.utilities(hautId))
        assertEquals(null, registry.libraryCatalog(otherSchool))
        assertEquals(null, registry.librarySeats(otherSchool))
        assertEquals(null, registry.utilities(otherSchool))

        val unsupportedStatus = registry.capabilityStatus(
            otherSchool,
            cn.edu.haut.campus.core.model.CapabilityKind.UTILITIES
        )
        assertFalse(unsupportedStatus.providerSupports)
        assertFalse(unsupportedStatus.isAvailable)
        assertEquals(
            cn.edu.haut.campus.core.model.CapabilityState.UNSUPPORTED,
            unsupportedStatus.state
        )

        val hautDefaultStatus = registry.capabilityStatus(
            hautId,
            cn.edu.haut.campus.core.model.CapabilityKind.LIBRARY_CATALOG
        )
        assertTrue(hautDefaultStatus.providerSupports)
        assertFalse(hautDefaultStatus.isAvailable)
        assertEquals(
            cn.edu.haut.campus.core.model.CapabilityState.NEEDS_VERIFICATION,
            hautDefaultStatus.state
        )
    }
}
