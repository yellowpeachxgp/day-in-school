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

    @Test
    fun hautLibrarySeatAuthClient_parsesTokenAndUserInfoAndBuildsSyncScript() {
        val sampleJwt = "eyJhbGciOiJIUzUxMiJ9.eyJsb2dpbl91c2VyX2tleSI6ImQxMDhjZDQzIn0.sig12345"
        assertEquals(
            sampleJwt,
            HautLibrarySeatAuthClient.normalizeRawToken("Bearer $sampleJwt")
        )
        assertEquals(
            sampleJwt,
            HautLibrarySeatAuthClient.extractTokenFromUrl(
                "https://wslib.haut.edu.cn/h5/index.html?token=$sampleJwt#/seatPage/SelectRoom/SelectRoom"
            )
        )

        val authUrl = OfficialServiceRoutePolicy.buildAuthenticatedSeatBookingUrl(sampleJwt)
        assertEquals(
            "https://wslib.haut.edu.cn/h5/index.html?token=$sampleJwt#/seatPage/SelectRoom/SelectRoom",
            authUrl
        )
        assertTrue(OfficialServiceRoutePolicy.isAllowedMainFrameUrl(authUrl))

        val okUserInfoJson = """
            {"msg":"成功","code":200,"data":{"cardNo":"5508025","userName":"张三","department":"信息科学与工程学院","userNo":"231040600206","breakNum":"0"}}
        """.trimIndent()
        val parsedUser = HautLibrarySeatAuthClient.parseUserInfoResponse(okUserInfoJson)
        assertNotNull(parsedUser)
        assertEquals("231040600206", parsedUser!!.userNo)
        assertEquals("张三", parsedUser.userName)

        val expiredJson = """
            {"msg":"请求访问：/spacelink/api/h5user/my/userinfo，认证失败，无法访问系统资源","code":401}
        """.trimIndent()
        assertEquals(null, HautLibrarySeatAuthClient.parseUserInfoResponse(expiredJson))

        val syncScript = OfficialWebScripts.buildLibraryTokenSyncScript(sampleJwt, navigateToSelectRoom = true)
        assertTrue(syncScript.contains("localStorage.setItem('hzd_token', bearer)"))
        assertTrue(syncScript.contains("getApp().globalData.token = bearer"))
        assertTrue(syncScript.contains("#/seatPage/SelectRoom/SelectRoom"))

        val mySeatUrl = OfficialServiceRoutePolicy.buildAuthenticatedMySeatManageUrl(sampleJwt)
        assertEquals(
            "https://wslib.haut.edu.cn/h5/index.html?token=$sampleJwt#/pages/SeatBooking/SeatBooking",
            mySeatUrl
        )
        assertTrue(OfficialServiceRoutePolicy.isAllowedMainFrameUrl(mySeatUrl))
    }

    @Test
    fun hautLibrarySeatAuthClient_parsesBookingStatusAndActionsAndLeaveRules() {
        val waitingSignInJson = """
            {
              "msg": "操作成功",
              "code": 200,
              "data": {
                "id": 918273,
                "seatId": 10086,
                "seatName": "A086",
                "regionName": "莲花街图书馆 三楼A区",
                "buildingLayerRegionId": 15,
                "bookingStatus": "1",
                "startDate": "2099-11-02 08:00:00",
                "endDate": "2099-11-02 22:00:00"
              }
            }
        """.trimIndent()
        val waitingRecord = HautLibrarySeatAuthClient.parseCurrentBookingResponse(waitingSignInJson)
        assertNotNull(waitingRecord)
        assertEquals(918273L, waitingRecord!!.bookingId)
        assertEquals("莲花街图书馆 三楼A区", waitingRecord.regionName)
        assertEquals("A086", waitingRecord.seatName)
        assertEquals(LibrarySeatBookingStatus.FULFILLED, waitingRecord.status)
        assertFalse(waitingRecord.hasStarted())
        assertTrue(waitingRecord.canSignIn)
        assertTrue(waitingRecord.canCancel)
        assertFalse(waitingRecord.canLeave)
        assertFalse(waitingRecord.canSignOut)

        val startedUncheckedRecord = waitingRecord.copy(startDate = "2020-01-01 08:00:00")
        assertTrue(startedUncheckedRecord.hasStarted())
        assertEquals(0L, startedUncheckedRecord.remainingSecondsBeforeStart())
        assertTrue(startedUncheckedRecord.canSignOut)

        val studyingJson = """
            {
              "msg": "操作成功",
              "code": 200,
              "data": {
                "id": 918274,
                "regionName": "莲花街图书馆 四楼B区",
                "seatName": "B102",
                "bookingStatus": "2",
                "startDate": "2026-11-02 09:00:00",
                "endDate": "2026-11-02 22:00:00"
              }
            }
        """.trimIndent()
        val studyingRecord = HautLibrarySeatAuthClient.parseCurrentBookingResponse(studyingJson)
        assertNotNull(studyingRecord)
        assertEquals(LibrarySeatBookingStatus.LEARNING, studyingRecord!!.status)
        assertFalse(studyingRecord.canCancel)
        assertTrue(studyingRecord.canLeave)
        assertTrue(studyingRecord.canRenew)
        assertTrue(studyingRecord.canSignOut)

        val awayJson = """
            {
              "msg": "操作成功",
              "code": 200,
              "data": {
                "id": 918275,
                "regionName": "嵩山路图书馆 二楼自习室",
                "seatName": "C018",
                "bookingStatus": "3",
                "startDate": "2026-11-02 14:00:00",
                "endDate": "2026-11-02 21:30:00",
                "shotLeaveTime": "2026-11-02 15:10:00",
                "longShortLeave": 1
              }
            }
        """.trimIndent()
        val awayRecord = HautLibrarySeatAuthClient.parseCurrentBookingResponse(awayJson)
        assertNotNull(awayRecord)
        assertEquals(LibrarySeatBookingStatus.LEAVING, awayRecord!!.status)
        assertTrue(awayRecord.canReturnFromLeave)
        assertTrue(awayRecord.canSignOut)
        assertFalse(awayRecord.canLeave)

        val emptyCurrentJson = """
            {"msg":"操作成功","code":200,"data":{"id":null,"bookingStatus":null}}
        """.trimIndent()
        assertEquals(null, HautLibrarySeatAuthClient.parseCurrentBookingResponse(emptyCurrentJson))

        val listJson = """
            {
              "total": 2,
              "rows": [
                {
                  "id": 918270,
                  "regionName": "莲花街图书馆 三楼A区",
                  "seatName": "A086",
                  "bookingStatus": "6",
                  "startDate": "2026-11-01 08:30:00",
                  "endDate": "2026-11-01 18:00:00"
                },
                {
                  "id": 918269,
                  "regionName": "莲花街图书馆 五楼C区",
                  "seatName": "C012",
                  "bookingStatus": "5",
                  "startDate": "2026-10-31 14:00:00",
                  "endDate": "2026-10-31 20:00:00"
                }
              ],
              "code": 200,
              "msg": "查询成功"
            }
        """.trimIndent()
        val historyList = HautLibrarySeatAuthClient.parseBookingListResponse(listJson)
        assertEquals(2, historyList.size)
        assertEquals(LibrarySeatBookingStatus.SIGNED_OUT, historyList[0].status)
        assertEquals(LibrarySeatBookingStatus.CANCELLED, historyList[1].status)

        val ruleJson = """
            {
              "msg": "操作成功",
              "code": 200,
              "rule": {
                "r1007": "30",
                "r1052": "120"
              }
            }
        """.trimIndent()
        assertEquals(30 to 120, HautLibrarySeatAuthClient.parseRuleLeaveMinutes(ruleJson))

        val cancelSuccessOutcome = HautLibrarySeatAuthClient.parseActionOutcome(
            rawJson = """{"code":200,"msg":"取消预约成功"}""",
            action = LibrarySeatActionType.CANCEL_BOOKING
        )
        assertTrue(cancelSuccessOutcome.success)
        assertEquals("取消预约成功", cancelSuccessOutcome.message)

        val leaveFailOutcome = HautLibrarySeatAuthClient.parseActionOutcome(
            rawJson = """{"code":500,"msg":"当前时段暂不支持长暂离"}""",
            action = LibrarySeatActionType.LONG_LEAVE
        )
        assertFalse(leaveFailOutcome.success)
        assertEquals("当前时段暂不支持长暂离", leaveFailOutcome.message)

        val startedCancelBlockedOutcome = HautLibrarySeatAuthClient.parseActionOutcome(
            rawJson = """{"code":-500,"msg":"当前预约已开始，不能取消预约"}""",
            action = LibrarySeatActionType.CANCEL_BOOKING
        )
        assertFalse(startedCancelBlockedOutcome.success)
        assertTrue(HautLibrarySeatAuthClient.isCancelBlockedByStartedWindow(startedCancelBlockedOutcome))

        val autoSignOffSuccessOutcome = HautLibrarySeatAuthClient.resolveStartedBookingCancelFallback(
            cancelOutcome = startedCancelBlockedOutcome,
            signOffOutcome = LibrarySeatActionOutcome(success = true, message = "已成功签退并释放座位", rawCode = 200)
        )
        assertTrue(autoSignOffSuccessOutcome.success)
        assertTrue(autoSignOffSuccessOutcome.message.contains("签退释放"))

        val gateSignInPendingOutcome = HautLibrarySeatAuthClient.resolveStartedBookingCancelFallback(
            cancelOutcome = startedCancelBlockedOutcome,
            signOffOutcome = LibrarySeatActionOutcome(success = false, message = "请先签到", rawCode = 666)
        )
        assertFalse(gateSignInPendingOutcome.success)
        assertTrue(gateSignInPendingOutcome.message.contains("刷脸入馆"))
        assertTrue(gateSignInPendingOutcome.message.contains("签退释放"))
    }

    @Test
    fun dualEngineReservationPlanner_computesArbitraryTargetTAndThreeAlarms() {
        val zone = ZoneId.of("Asia/Shanghai")
        val earlyNow = LocalDateTime.of(2026, 11, 2, 20, 0).atZone(zone).toInstant().toEpochMilli()
        val targetDate = java.time.LocalDate.of(2026, 11, 3)

        listOf("07:00", "08:45", "12:10", "20:59").forEach { targetT ->
            val parsedTime = DualEngineReservationPlanner.parseTargetLocalTime(targetT)
            val calc = DualEngineReservationPlanner.computeSchedule(
                planId = "plan_test_$targetT",
                targetDate = targetDate,
                targetTime = parsedTime,
                nowEpochMillis = earlyNow
            )
            assertEquals(calc.fireAtEpochMillis - 30 * 60_000L, calc.precheckAtEpochMillis)
            assertEquals(calc.fireAtEpochMillis - 5 * 60_000L, calc.finalCheckAtEpochMillis)
            assertEquals("ARMED", calc.precheckInitialStatus)
            assertEquals("ARMED", calc.finalCheckInitialStatus)
            assertEquals("ARMED", calc.fireInitialStatus)
            assertEquals(3, calc.alarmSpecs.size)
            assertEquals(3, calc.alarmSpecs.map { it.requestCode }.distinct().size)
            assertEquals(
                listOf(TripleScheduleStage.PRECHECK, TripleScheduleStage.FINAL_CHECK, TripleScheduleStage.FIRE),
                calc.alarmSpecs.map { it.stage }
            )
        }

        // Out-of-window rejection
        try {
            DualEngineReservationPlanner.computeSchedule(
                planId = "plan_invalid",
                targetDate = targetDate,
                targetTime = DualEngineReservationPlanner.parseTargetLocalTime("06:30"),
                nowEpochMillis = earlyNow
            )
            fail("Expected out-of-window rejection for 06:30")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("窗口"))
        }

        // Late creation between T-30m and T-5m immediately marks precheckInitialStatus = PRECHECK_LATE_OK
        val target0845 = LocalDateTime.of(2026, 11, 3, 8, 45).atZone(zone).toInstant().toEpochMilli()
        val lateNow = target0845 - 20 * 60_000L
        val lateCalc = DualEngineReservationPlanner.computeSchedule(
            planId = "plan_late",
            targetDate = targetDate,
            targetTime = DualEngineReservationPlanner.parseTargetLocalTime("08:45"),
            nowEpochMillis = lateNow
        )
        assertEquals("PRECHECK_LATE_OK", lateCalc.precheckInitialStatus)
        assertEquals("ARMED", lateCalc.finalCheckInitialStatus)
    }

    @Test
    fun parcelHubEngine_blocksOtpSmsAndEnforcesProviderAuthorityMatrix() {
        // OTP / verification SMS must NEVER be extracted as pickup code
        val otpSms = "【工商银行】您的登录验证码为 384920，切勿向他人泄露。"
        assertEquals(null, ParcelHubEngine.resolvePickupCodeFromText(otpSms))

        // Real campus station SMS extracts pickup code + station name
        val stationSms = "【菜鸟驿站】您的顺丰包裹已到莲花街南苑菜鸟驿站，请凭取件码 4-12-3086 取件。"
        val extracted = ParcelHubEngine.resolvePickupCodeFromText(stationSms)
        assertNotNull(extracted)
        assertEquals("4-12-3086", extracted!!.pickupCode)
        assertEquals("SHELF_CODE", extracted.codeType)

        // Unofficial platform live sync is fail-closed in production
        try {
            ParcelHubEngine.enforceProviderAuthority(
                providerId = "parcel.taobao.cainiao",
                isBackgroundPoll = false,
                isProduction = true,
                sandboxOverride = false
            )
            fail("Expected SecurityException for parcel.taobao.cainiao in production")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("RESEARCH_SANDBOX_ONLY"))
        }

        // Manual import, SMS local rule & Xiaomi personal assistant are allowed
        ParcelHubEngine.enforceProviderAuthority("parcel.manual", isBackgroundPoll = false, isProduction = true)
        ParcelHubEngine.enforceProviderAuthority("parcel.notification-parser", isBackgroundPoll = false, isProduction = true)
        ParcelHubEngine.enforceProviderAuthority("parcel.xiaomi.personal-assistant", isBackgroundPoll = false, isProduction = true)

        // Xiaomi phone URL extraction & MiuiExpressCrypto round-trip
        val loginUrlWithPhone = "https://account.xiaomi.com/fe/service/login?_user=13812345678&sid=assistant"
        assertEquals("13812345678", XiaomiPassportClient.extractPhoneFromUrl(loginUrlWithPhone))
        assertEquals("138****5678", XiaomiPassportClient.maskPhone("13812345678"))
        val plainBody = """{"info":{"limit":29,"phones":["13812345678"]}}"""
        val encrypted = MiuiExpressCrypto.encode(plainBody)
        assertEquals(plainBody, MiuiExpressCrypto.decode(encrypted))
    }

    @Test
    fun campusCloudOidcHelper_generatesValidPkceAndIdempotencyKeys() {
        val tx = CampusCloudOidcHelper.buildLocalOrRemoteTransaction(
            installEphemeralId = "inst_test_12345678",
            cloudBaseUrl = "https://cloud.xpeach.codes"
        )
        assertEquals("S256", tx.pkce.codeChallengeMethod)
        assertTrue(tx.pkce.codeVerifier.length >= 43)
        assertTrue(tx.authorizeUrl.contains("code_challenge_method=S256"))
        assertEquals(
            "dis:reservation:ord-123:debit:v1",
            CampusCloudOidcHelper.buildDebitIdempotencyKey("ord-123")
        )
        assertEquals(
            "dis:reservation:ord-123:refund:v1",
            CampusCloudOidcHelper.buildRefundIdempotencyKey("ord-123")
        )
    }
}
