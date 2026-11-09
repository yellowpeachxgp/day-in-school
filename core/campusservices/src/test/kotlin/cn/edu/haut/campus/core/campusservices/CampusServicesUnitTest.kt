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

        // Search URL uses Huiwen OPAC strSearchType, strText & optr parameters
        val searchUrl = LibraryCatalogSnapshot.buildSearchUrl("数据结构", "keyword")
        assertTrue(searchUrl.contains("strSearchType=keyword"))
        assertTrue(searchUrl.contains("strText="))
        assertTrue(searchUrl.contains("optr="))

        val callNoSearchUrl = LibraryCatalogSnapshot.buildSearchUrl("TP311", "callno")
        assertTrue(callNoSearchUrl.contains("strSearchType=callno"))
        assertTrue(callNoSearchUrl.contains("optr=%E5%89%8D%E6%96%B9%E5%8C%B9%E9%85%8D"))

        val syncScript = OfficialWebScripts.buildLibspaceSearchSyncScript("数据结构", "all")
        assertTrue(syncScript.contains("idx.opac"))
        assertTrue(syncScript.contains("search/param"))
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
                  },
                  {
                    "bibId": "m_null_fields_book",
                    "title": "电子馆藏测试书目",
                    "author": null,
                    "publisher": null,
                    "pub_year": null,
                    "isbn": null,
                    "callno": null,
                    "docTypeDesc": null,
                    "itemCount": 0,
                    "circCount": 0,
                    "abstract": null,
                    "holdings": null
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
        assertEquals(2, pageResult.books.size)
        val book = pageResult.books.first()
        assertEquals("数据结构:C语言版", book.title)
        assertEquals("m7e3788925dc6cfeb9cd7d3a74db7fae2", book.bibId)
        assertEquals("TP311.12-43/30-2.2", book.callNo)
        assertEquals(1, book.availableCount)
        assertEquals(2, book.totalCount)
        assertEquals(2, book.inlineHoldings.size)
        assertTrue(book.inlineHoldings.first().isAvailable)
        assertEquals("架位：03架A面", book.inlineHoldings.first().shelf)
        assertFalse(book.inlineHoldings.first().lines.any { it.contains("null") })

        val nullBook = pageResult.books[1]
        assertEquals("电子馆藏测试书目", nullBook.title)
        assertEquals("", nullBook.bibliography)
        assertEquals("", nullBook.summary)
        assertEquals("可借 0 / 馆藏 0 册", nullBook.resources)
        assertTrue(nullBook.inlineHoldings.isEmpty())

        assertEquals("数据结构", HautLibraryOpacClient.normalizeSearchKeyword("《数据结构》", LibrarySearchField.ALL))
        assertEquals("978-7-115-57666-8", HautLibraryOpacClient.normalizeSearchKeyword("ISBN: 978-7-115-57666-8", LibrarySearchField.ISBN))
        assertEquals("TP311", HautLibraryOpacClient.normalizeSearchKeyword("索书号：tp311", LibrarySearchField.CALL_NO))

        val detailInfoJson = """
            {
              "code": 0,
              "data": {
                "map": {
                  "baseInfo": {
                    "map": {
                      "title": "傲慢与偏见",
                      "callno": ["I561.44/1-12"]
                    }
                  },
                  "detailInfo": {
                    "map": {
                      "出版发行项": "北京:<a style='color:#006fcc' href='{meta}/#/search'>作家出版社</a>,2024.5",
                      "学科主题": "<a href='#'>长篇小说</a>-英国-近代"
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val parsedDetail = HautLibraryOpacClient.parseBookDetailPayloads(
            fallbackBook = nullBook,
            infoJson = detailInfoJson,
            holdingsJson = null
        )
        assertEquals("傲慢与偏见", parsedDetail.title)
        assertEquals("I561.44/1-12", parsedDetail.callNo)
        assertTrue(parsedDetail.bibliography.contains("出版发行项：北京:作家出版社,2024.5"))
        assertTrue(parsedDetail.bibliography.contains("学科主题：长篇小说-英国-近代"))

        val rawHttp11 = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 20\r\n\r\n{\"code\":0,\"data\":[]}"
        val parsedHttpBody = HautLibraryOpacClient.readHttp11Response(
            java.io.ByteArrayInputStream(rawHttp11.toByteArray(StandardCharsets.UTF_8))
        )
        assertEquals("{\"code\":0,\"data\":[]}", parsedHttpBody)

        val rawChunkedHttp11 = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\na\r\n{\"code\":0,\r\na\r\n\"data\":[]}\r\n0\r\n\r\n"
        val parsedChunkedBody = HautLibraryOpacClient.readHttp11Response(
            java.io.ByteArrayInputStream(rawChunkedHttp11.toByteArray(StandardCharsets.UTF_8))
        )
        assertEquals("{\"code\":0,\"data\":[]}", parsedChunkedBody)

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
    fun xiaomiExpressClient_resolvesNonBlankOaidAndVaidInUserSignal() {
        val hex16Regex = Regex("^[0-9a-f]{16}$")
        val derivedOaid = XiaomiExpressApi.resolveOaid(null, userId = "284910293", candidate = "")
        val derivedVaid = XiaomiExpressApi.resolveVaid(null, userId = "284910293", candidate = null)
        assertTrue("Derived OAID must be 16-char hex", hex16Regex.matches(derivedOaid))
        assertTrue("Derived VAID must be 16-char hex", hex16Regex.matches(derivedVaid))
        assertTrue("OAID and VAID should be distinct", derivedOaid != derivedVaid)
        assertEquals(
            "Fallback must be deterministic for the same user",
            derivedVaid,
            XiaomiExpressApi.resolveVaid(null, userId = "284910293", candidate = "0000000000000000")
        )
        assertEquals(
            "All-zero UUID must be rejected in favor of deterministic ID",
            derivedOaid,
            XiaomiExpressApi.resolveOaid(null, userId = "284910293", candidate = "00000000-0000-0000-0000-000000000000")
        )
        assertEquals(
            "Valid explicit VAID should be preserved",
            "a1b2c3d4e5f60718",
            XiaomiExpressApi.resolveVaid(null, userId = "284910293", candidate = "a1b2c3d4e5f60718")
        )

        val wrappedJson = XiaomiExpressApi.wrapBody(
            context = null,
            plain = """{"info":{"limit":29,"phones":["13800138000"],"deletedMailNos":[],"modifiedMailNos":[]}}""",
            userId = "284910293",
            oaidIn = "",
            vaidIn = ""
        )
        val root = org.json.JSONObject(wrappedJson)
        val userSignal = root.getJSONObject("userSignal")
        assertEquals(derivedOaid, userSignal.getString("oaid"))
        assertEquals(derivedVaid, userSignal.getString("vaid"))
        assertEquals("284910293", userSignal.getString("userId"))
        assertTrue(userSignal.getString("vaid").isNotBlank())
        assertTrue(userSignal.getString("oaid").isNotBlank())
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
        assertEquals(10_000L, BookingExecutionMode.CLOUD_HOSTED.priceMicropeach)
        assertEquals(1.0, BookingExecutionMode.CLOUD_HOSTED.pricePeach, 0.0001)
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

        // Cloud URL allowlist blocks HTTP and untrusted domains
        assertNotNull(CampusCloudOidcHelper.validateAllowedCloudUrl("https://campus.yellowpeachxgp.com/api/v1/me"))
        try {
            CampusCloudOidcHelper.validateAllowedCloudUrl("http://campus.yellowpeachxgp.com/api/v1/me")
            fail("Expected SecurityException for cleartext HTTP cloud URL")
        } catch (_: SecurityException) {
        }
        try {
            CampusCloudOidcHelper.validateAllowedCloudUrl("https://evil.example.com/api/v1/me")
            fail("Expected SecurityException for untrusted cloud host")
        } catch (_: SecurityException) {
        }

        // Library seat origin & Xiaomi WebView allowlist prevent prefix-spoofing attacks
        assertTrue(OfficialServiceRoutePolicy.isLibrarySeatOrigin("https://wslib.haut.edu.cn/h5/index.html"))
        assertFalse(OfficialServiceRoutePolicy.isLibrarySeatOrigin("https://wslib.haut.edu.cn.evil.com/h5/index.html"))
        assertTrue(OfficialServiceRoutePolicy.isAllowedXiaomiWebUrl("https://account.xiaomi.com/pass/serviceLogin"))
        assertTrue(OfficialServiceRoutePolicy.isAllowedXiaomiWebUrl("https://api.assistant.miui.com/sts"))
        assertFalse(OfficialServiceRoutePolicy.isAllowedXiaomiWebUrl("http://account.xiaomi.com/pass/serviceLogin"))
        assertFalse(OfficialServiceRoutePolicy.isAllowedXiaomiWebUrl("https://account.xiaomi.com.evil.com/pass"))
    }

    @Test
    fun nullLiteralSanitizers_stripJsonNullStringsAcrossParcelAndLibraryPayloads() {
        val obj = JSONObject()
            .put("pickup_code", JSONObject.NULL)
            .put("station_name", "null")
            .put("item_title", "中通快递 · null")
            .put("carrier_name", "中通快递")
        assertEquals(null, obj.optNullableCleanString("pickup_code"))
        assertEquals(null, obj.optNullableCleanString("station_name"))
        assertEquals("中通快递", obj.optCleanString("item_title"))
        assertEquals("中通快递", obj.optCleanString("carrier_name"))
        assertEquals("默认驿站", "null".cleanNullLiteral("默认驿站"))
        assertEquals(null, "undefined".cleanNullableLiteral())
        val syncScript = OfficialWebScripts.buildLibraryTokenSyncScript("tok_1234567890", navigateToSelectRoom = false)
        assertTrue(syncScript.contains("__hautNullScrubberInstalled"))
        assertTrue(syncScript.contains("07:30-22:00"))
    }

    @Test
    fun wslibSpringBootMessageCodes_translateAndClassifyOpeningAndDuplicateErrors() {
        val raw10079 = "No message found under code 'seat.10079' for locale ''."
        val raw10057 = "No message found under code 'seat.10057' for locale ''."
        val raw10062 = "No message found under code 'seat.10062' for locale ''."

        assertEquals(
            "未到07:00开放时间或该时段内已有生效预约",
            HautLibrarySeatAuthClient.translateWslibDiagnosticMessage(raw10079)
        )
        assertEquals(
            "只能预约1天之内的时间(含当天)",
            HautLibrarySeatAuthClient.translateWslibDiagnosticMessage(raw10057)
        )
        assertEquals(
            "本座位已锁定 请稍后再试!",
            HautLibrarySeatAuthClient.translateWslibDiagnosticMessage(raw10062)
        )

        assertTrue(HautLibrarySeatAuthClient.isWslibNotYetOpenMessage(raw10079))
        assertTrue(HautLibrarySeatAuthClient.isWslibNotYetOpenMessage(raw10057))
        assertTrue(HautLibrarySeatAuthClient.isWslibNotYetOpenMessage("只能预约1天之内的时间(含当天)"))
        assertFalse(HautLibrarySeatAuthClient.isWslibNotYetOpenMessage("本座位已锁定 请稍后再试!"))

        assertTrue(HautLibrarySeatAuthClient.isWslibAlreadyBookedMessage(raw10079))
        assertTrue(HautLibrarySeatAuthClient.isWslibAlreadyBookedMessage("每个学生一个时间段只能预约一个座位"))
        assertFalse(HautLibrarySeatAuthClient.isWslibAlreadyBookedMessage("此时间段该座位已有预约"))
        assertTrue(HautLibrarySeatAuthClient.isWslibSeatOccupiedByOthersMessage("此时间段该座位已有预约", 404))
        assertTrue(HautLibrarySeatAuthClient.isWslibSeatLockedMessage("本座位已锁定 请稍后再试!", 1122))

        val targetCand5A = LibrarySeatItem(id = 618L, seatName = "05桌 A座", seatStatus = 2)
        val validRecord = LibrarySeatBookingRecord(
            bookingId = 9140001L,
            seatId = 618L,
            seatName = "四层西书库北区 05桌 A座",
            regionName = "四层西书库北区",
            buildingLayerRegionId = 13L,
            startDate = "2026-11-08 07:30:00",
            endDate = "2026-11-08 22:00:00",
            status = LibrarySeatBookingStatus.FULFILLED,
            createTime = "2026-11-08 07:00:01",
            signInTime = null,
            signOutTime = null,
            shotLeaveTime = null,
            longShortLeave = null,
            description = null
        )
        assertTrue(
            HautLibrarySeatAuthClient.isBookingRecordMatchingTarget(
                record = validRecord,
                targetDate = "2026-11-08",
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座", "06桌 F座", "08桌 A座"),
                candidatePoolIds = setOf(618L, 659L, 621L),
                specificSeat = targetCand5A,
                expectedBookingId = 9140001L
            )
        )

        // Wrong seat (01桌 B座 seatId=610) must never match
        val wrongSeatRecord = validRecord.copy(
            bookingId = 9140049L,
            seatId = 610L,
            seatName = "四层西书库北区 01桌 B座"
        )
        assertFalse(
            HautLibrarySeatAuthClient.isBookingRecordMatchingTarget(
                record = wrongSeatRecord,
                targetDate = "2026-11-08",
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座", "06桌 F座", "08桌 A座"),
                candidatePoolIds = setOf(618L, 659L, 621L)
            )
        )

        // Wrong date (2026-11-07) must never match
        val wrongDateRecord = validRecord.copy(
            startDate = "2026-11-07 07:30:00",
            endDate = "2026-11-07 22:00:00"
        )
        assertFalse(
            HautLibrarySeatAuthClient.isBookingRecordMatchingTarget(
                record = wrongDateRecord,
                targetDate = "2026-11-08",
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座", "06桌 F座", "08桌 A座")
            )
        )

        // Same date and same seat, but different time slot (14:00-18:00 vs 07:30-22:00) must never match
        val wrongTimeSlotRecord = validRecord.copy(
            startDate = "2026-11-08 14:00:00",
            endDate = "2026-11-08 18:00:00"
        )
        assertFalse(
            HautLibrarySeatAuthClient.isBookingRecordMatchingTarget(
                record = wrongTimeSlotRecord,
                targetDate = "2026-11-08",
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座"),
                expectedStartFull = "2026-11-08 07:30:00",
                expectedEndFull = "2026-11-08 22:00:00"
            )
        )

        // Missing region info with same-named seat (05桌 A座) or different region prefix (三层) must never match
        val missingRegionRecord = validRecord.copy(
            seatName = "05桌 A座",
            regionName = "",
            buildingLayerRegionId = null
        )
        assertFalse(
            HautLibrarySeatAuthClient.isBookingRecordMatchingTarget(
                record = missingRegionRecord,
                targetDate = "2026-11-08",
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座")
            )
        )
        val otherFloorSeatRecord = validRecord.copy(
            seatName = "三层西书库北区 05桌 A座",
            regionName = "",
            buildingLayerRegionId = null
        )
        assertFalse(
            HautLibrarySeatAuthClient.isBookingRecordMatchingTarget(
                record = otherFloorSeatRecord,
                targetDate = "2026-11-08",
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座")
            )
        )

        // Historical candidate filter must constrain region first and reject same-named seat from another floor
        assertTrue(
            HautLibrarySeatAuthClient.isHistoricalRecordMatchingCandidate(
                record = validRecord,
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座"),
                expectedRegionId = 13L
            )
        )
        assertFalse(
            HautLibrarySeatAuthClient.isHistoricalRecordMatchingCandidate(
                record = validRecord.copy(
                    seatId = 351L,
                    seatName = "三层西书库北区 05桌 A座",
                    regionName = "三层西书库北区",
                    buildingLayerRegionId = 9L
                ),
                preferredAreaName = "四层西书库北区",
                candidateSeatNames = listOf("05桌 A座"),
                expectedRegionId = 13L
            )
        )

        // Slot window resolution prioritizes explicit user slotWindow (14:00-18:00) over school open hours (07:30-22:00)
        assertEquals(
            "2026-11-08 14:00:00" to "2026-11-08 18:00:00",
            HautLibrarySeatAuthClient.resolveTargetSlotWindowBounds(
                targetDate = "2026-11-08",
                slotWindow = "14:00-18:00",
                fallbackStartFull = "2026-11-08 07:30:00",
                fallbackEndFull = "2026-11-08 22:00:00"
            )
        )

        // Scoped cloud auth metadata cache isolates by server URL, JWT account sub, and studentNo
        CampusCloudOidcHelper.clearScopedAuthMetadataForTest()
        val jwtUserA = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLWEifQ.sigA"
        val jwtUserB = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLWIifQ.sigB"
        CampusCloudOidcHelper.recordScopedAuthMetadata(
            cloudBaseUrl = "https://campus.yellowpeachxgp.com",
            accessToken = jwtUserA,
            schoolId = "haut",
            studentNo = "231040100111",
            authVersion = 2,
            bindingId = "bind-a-111",
            consentId = "con-a-1"
        )
        CampusCloudOidcHelper.recordScopedAuthMetadata(
            cloudBaseUrl = "https://campus.yellowpeachxgp.com",
            accessToken = jwtUserA,
            schoolId = "haut",
            studentNo = "231040100222",
            authVersion = 2,
            bindingId = "bind-a-222",
            consentId = "con-a-1"
        )
        assertEquals(
            "bind-a-111",
            CampusCloudOidcHelper.resolveScopedAuthMetadata(
                "https://campus.yellowpeachxgp.com",
                jwtUserA,
                "haut",
                "231040100111"
            )?.bindingId
        )
        assertEquals(
            "bind-a-222",
            CampusCloudOidcHelper.resolveScopedAuthMetadata(
                "https://campus.yellowpeachxgp.com",
                jwtUserA,
                "haut",
                "231040100222"
            )?.bindingId
        )
        // Wildcard lookup when multiple bindings exist under the same user+school must not guess bindingId
        assertEquals(
            null,
            CampusCloudOidcHelper.resolveScopedAuthMetadata(
                "https://campus.yellowpeachxgp.com",
                jwtUserA,
                "haut",
                null
            )?.bindingId
        )
        // Different user on the same server is completely isolated
        assertEquals(
            null,
            CampusCloudOidcHelper.resolveScopedAuthMetadata(
                "https://campus.yellowpeachxgp.com",
                jwtUserB,
                "haut",
                "231040100111"
            )
        )
    }

    @Test
    fun `persistedAuthConfirmation survives app restart for reauthorized v2 binding and plan`() {
        CampusCloudOidcHelper.clearScopedAuthMetadataForTest()
        val payloadA = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"user_restart_v2"}""".toByteArray())
        val jwtUser = "eyJhbGciOiJIUzI1NiJ9.$payloadA.sig"
        val baseUrl = "https://campus.yellowpeachxgp.com"

        CampusCloudOidcHelper.recordScopedAuthMetadata(
            cloudBaseUrl = baseUrl,
            accessToken = jwtUser,
            schoolId = "haut",
            studentNo = "231040100111",
            authVersion = 2,
            bindingId = "bind_reauth_v2",
            consentId = "consent_reauth_v2",
            confirmedByClientAction = true
        )
        CampusCloudOidcHelper.recordScopedPlanProvenance(
            cloudBaseUrl = baseUrl,
            accessToken = jwtUser,
            planId = "plan_reauth_v2",
            studentNo = "231040100111",
            authVersion = 2,
            bindingId = "bind_reauth_v2",
            consentId = "consent_reauth_v2"
        )

        // Simulate app restart: clear in-memory caches and reload from persisted store
        CampusCloudOidcHelper.simulateAppRestartKeepPersistedAuthForTest()

        val restoredMeta = CampusCloudOidcHelper.resolveScopedAuthMetadata(
            cloudBaseUrl = baseUrl,
            accessToken = jwtUser,
            schoolId = "haut",
            studentNo = "231040100111"
        )
        assertTrue(restoredMeta != null && restoredMeta.confirmedByClientAction)
        assertEquals(2, restoredMeta?.authVersion)
        assertEquals("bind_reauth_v2", restoredMeta?.bindingId)
        assertEquals("consent_reauth_v2", restoredMeta?.consentId)

        val restoredPlanProv = CampusCloudOidcHelper.resolveScopedPlanProvenance(
            cloudBaseUrl = baseUrl,
            accessToken = jwtUser,
            planId = "plan_reauth_v2"
        )
        assertEquals(2, restoredPlanProv?.authVersion)
        assertEquals("bind_reauth_v2", restoredPlanProv?.bindingId)
        assertEquals("consent_reauth_v2", restoredPlanProv?.consentId)

        // Legacy plan without its own provenance must NOT inherit binding-level confirmation
        val unconfirmedLegacyPlanProv = CampusCloudOidcHelper.resolveScopedPlanProvenance(
            cloudBaseUrl = baseUrl,
            accessToken = jwtUser,
            planId = "plan_legacy_no_prov"
        )
        assertEquals(null, unconfirmedLegacyPlanProv)
    }

    @Test
    fun `threeAxisResult separates delegationStoppedSchoolConfirmed from active confirmed and fails closed on persistence error`() {
        val stoppedConfirmed = AutomatedSeatBookingResult(
            confirmed = false,
            bookingId = "99887766",
            seatTitle = "05桌 A座",
            evidenceSha256 = "sha_evidence_99887766",
            diagnosticMessage = "委托已停止，但学校预约已成功",
            outcomeState = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
            userIntentState = "REVOKED",
            externalExecutionState = "SCHOOL_CONFIRMED",
            schoolCancellationState = "MANUAL_ACTION_REQUIRED",
            billingState = "HELD_PENDING_REVIEW"
        )
        assertEquals(false, stoppedConfirmed.confirmed)
        assertEquals(true, stoppedConfirmed.isSchoolConfirmed)
        assertEquals(true, stoppedConfirmed.isDelegationStopped)
        assertEquals(false, stoppedConfirmed.isResultUnknown)
        assertEquals("HELD_PENDING_REVIEW", stoppedConfirmed.billingState)
        assertEquals("MANUAL_ACTION_REQUIRED", stoppedConfirmed.schoolCancellationState)

        // Fail-closed persistence check: if disk write fails, in-memory confirmation must be rolled back
        val tmpDir = java.nio.file.Files.createTempDirectory("auth_store_fail_closed").toFile()
        val blockerFile = java.io.File(tmpDir, "not_a_directory")
        blockerFile.writeText("x")
        CampusCloudOidcHelper.configurePersistentAuthStoreFileForTest(
            java.io.File(blockerFile, "impossible_store.json")
        )
        val payloadB = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"user_fail_closed"}""".toByteArray())
        val jwtFailUser = "eyJhbGciOiJIUzI1NiJ9.$payloadB.sig"
        var threwOnPersistFail = false
        try {
            CampusCloudOidcHelper.recordScopedAuthMetadata(
                cloudBaseUrl = "https://campus.yellowpeachxgp.com",
                accessToken = jwtFailUser,
                schoolId = "haut",
                studentNo = "231040100999",
                authVersion = 2,
                bindingId = "bind_fail",
                consentId = "consent_fail",
                confirmedByClientAction = true
            )
        } catch (_: IllegalStateException) {
            threwOnPersistFail = true
        } finally {
            CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                java.nio.file.Files.createTempDirectory("auth_store_restored").toFile()
            )
        }
        assertTrue(threwOnPersistFail)
        assertEquals(
            null,
            CampusCloudOidcHelper.resolveScopedAuthMetadata(
                cloudBaseUrl = "https://campus.yellowpeachxgp.com",
                accessToken = jwtFailUser,
                schoolId = "haut",
                studentNo = "231040100999"
            )
        )
    }

    @Test
    fun `executeNow normalization downgrades incomplete confirmations to RESULT_UNKNOWN and cancel check rejects delegation-only CANCELLED`() {
        // 1. Incomplete execute-now payload (state=CONFIRMED, external_execution_state=SCHOOL_CONFIRMED, but missing school_reservation / booking_id / evidence)
        val incompleteActiveRaw = """
            {
              "ok": true,
              "data": {
                "job": {
                  "id": "job_incomplete_01",
                  "state": "CONFIRMED",
                  "display_status": "SCHOOL_CONFIRMED",
                  "user_intent_state": "ACTIVE",
                  "external_execution_state": "SCHOOL_CONFIRMED"
                }
              }
            }
        """.trimIndent()
        val incompleteRes = CampusCloudOidcHelper.parseExecuteNowBookingResult("job_incomplete_01", incompleteActiveRaw)
        assertFalse("Incomplete confirmation must never be confirmed", incompleteRes.confirmed)
        assertFalse("Incomplete confirmation must never be isSchoolConfirmed", incompleteRes.isSchoolConfirmed)
        assertTrue("Incomplete confirmation must be isResultUnknown", incompleteRes.isResultUnknown)
        assertEquals("RESULT_UNKNOWN", incompleteRes.outcomeState)
        assertEquals("RESULT_UNKNOWN", incompleteRes.externalExecutionState)
        assertEquals("HELD_PENDING_REVIEW", incompleteRes.billingState)
        assertEquals("", incompleteRes.bookingId)

        // 2. Incomplete post-revocation payload (DELEGATION_STOPPED_SCHOOL_CONFIRMED without real booking_id/evidence)
        val incompleteStoppedRaw = """
            {
              "ok": true,
              "data": {
                "job": {
                  "id": "job_incomplete_02",
                  "state": "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                  "user_intent_state": "REVOKED",
                  "external_execution_state": "SCHOOL_CONFIRMED"
                },
                "school_reservation": {
                  "external_booking_id": "",
                  "seat_alias": "05桌 A座",
                  "region_alias": "五层中区"
                }
              }
            }
        """.trimIndent()
        val incompleteStoppedRes = CampusCloudOidcHelper.parseExecuteNowBookingResult("job_incomplete_02", incompleteStoppedRaw)
        assertFalse(incompleteStoppedRes.isSchoolConfirmed)
        assertTrue(incompleteStoppedRes.isResultUnknown)
        assertTrue(incompleteStoppedRes.isDelegationStopped)
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", incompleteStoppedRes.outcomeState)
        assertEquals("RESULT_UNKNOWN", incompleteStoppedRes.externalExecutionState)

        // 3. Complete execute-now confirmation succeeds cleanly
        val completeRaw = """
            {
              "ok": true,
              "data": {
                "job": {
                  "id": "job_complete_03",
                  "state": "CONFIRMED",
                  "user_intent_state": "ACTIVE",
                  "external_execution_state": "SCHOOL_CONFIRMED"
                },
                "school_reservation": {
                  "external_booking_id": "88776655",
                  "seat_alias": "05桌 A座",
                  "region_alias": "五层中区",
                  "evidence_sha256": "deadbeef1234567890"
                }
              }
            }
        """.trimIndent()
        val completeRes = CampusCloudOidcHelper.parseExecuteNowBookingResult("job_complete_03", completeRaw)
        assertTrue(completeRes.confirmed)
        assertTrue(completeRes.isSchoolConfirmed)
        assertFalse(completeRes.isResultUnknown)
        assertEquals("CONFIRMED", completeRes.outcomeState)
        assertEquals("88776655", completeRes.bookingId)

        // 4. Seat cancellation must NEVER treat delegation-only state="CANCELLED" as school seat cancelled!
        val delegationOnlyCancelRaw = """
            {
              "ok": true,
              "data": {
                "job": {
                  "id": "job_delegation_stopped",
                  "state": "CANCELLED",
                  "school_cancellation_state": "NOT_REQUESTED"
                },
                "live_cancel": null
              }
            }
        """.trimIndent()
        assertFalse(CampusCloudOidcHelper.isCloudSeatCancelConfirmed(delegationOnlyCancelRaw))

        val unknownCancelRaw = """
            {
              "ok": true,
              "data": {
                "job": {
                  "id": "job_cancel_unknown",
                  "state": "CANCEL_RESULT_UNKNOWN",
                  "school_cancellation_state": "CANCEL_RESULT_UNKNOWN"
                },
                "live_cancel": {
                  "cancelled": false,
                  "outcome": "CANCEL_RESULT_UNKNOWN",
                  "school_cancellation_state": "CANCEL_RESULT_UNKNOWN"
                }
              }
            }
        """.trimIndent()
        assertFalse(CampusCloudOidcHelper.isCloudSeatCancelConfirmed(unknownCancelRaw))

        val verifiedSchoolCancelRaw = """
            {
              "ok": true,
              "data": {
                "job": {
                  "id": "job_cancel_verified",
                  "state": "SCHOOL_CANCELLED",
                  "school_cancellation_state": "SCHOOL_CANCELLED"
                },
                "live_cancel": {
                  "cancelled": true,
                  "outcome": "SCHOOL_CANCELLED",
                  "school_cancellation_state": "SCHOOL_CANCELLED"
                }
              }
            }
        """.trimIndent()
        assertTrue(CampusCloudOidcHelper.isCloudSeatCancelConfirmed(verifiedSchoolCancelRaw))
    }

    @Test
    fun `moments snapshot parser preserves server owner role across devices and scrubs legacy private-prefix cached comments`() {
        val legacyCachedJson = """
            {
              "updated_at": "2026-11-10T00:00:00Z",
              "posts": [
                {
                  "id": "mp_cross_device_owner",
                  "type": "LOST",
                  "title": "遗失黑色充电宝",
                  "category": "数码",
                  "campus": "莲花街校区",
                  "location": "图书馆五楼",
                  "description": "Anker 磁吸款",
                  "contact": "wx_owner_01",
                  "contact_visibility": "ON_REQUEST",
                  "is_owner": true,
                  "can_manage": true,
                  "is_legacy_unowned": false,
                  "viewer_role": "OWNER",
                  "comments": [
                    {
                      "id": "mc_legacy_priv",
                      "author_name": "旧客户端",
                      "content": "【私密联系发起人】我的手机号 13800001111",
                      "contact_hint": "13800001111"
                    },
                    {
                      "id": "mc_public_ok",
                      "author_name": "热心同学",
                      "content": "已送到五楼服务台",
                      "contact_hint": ""
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val snap = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(legacyCachedJson)
        assertNotNull(snap)
        val post = snap!!.posts.single()
        assertTrue(post.isOwner)
        assertTrue(post.canManage)
        assertFalse(post.isLegacyUnowned)
        assertEquals("OWNER", post.viewerRole)
        assertEquals(1, post.comments.size)
        assertEquals("已送到五楼服务台", post.comments.single().content)

        val reencoded = CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(snap)
        assertFalse(reencoded.contains("【私密联系发起人】"))
        assertFalse(reencoded.contains("13800001111"))
    }

    @Test
    fun `seat cancellation readback requires explicit CANCELLED or SIGNED_OUT terminal for exact bookingId and rejects UNKNOWN DEFAULTED intermediate or other IDs`() {
        val emptyCur = """{"code":200,"data":null}"""
        val emptyInProg = """{"code":200,"total":0,"rows":[]}"""

        // 1. UNKNOWN (0 / "" / 99), DEFAULTED (7), intermediate ("取消处理中"), failure ("取消失败"), contradictions MUST ALL throw
        val rejectedRows = listOf(
            """{"id":"99887766","bookingStatus":0,"bookingStatusName":"未知状态"}""",
            """{"id":"99887766","bookingStatus":"","bookingStatusName":"已取消"}""",
            """{"id":"99887766","bookingStatus":7,"bookingStatusName":"已违约"}""",
            """{"id":"99887766","bookingStatus":5,"bookingStatusName":"取消处理中"}""",
            """{"id":"99887766","bookingStatus":5,"bookingStatusName":"取消失败"}""",
            """{"id":"99887766","bookingStatus":6,"bookingStatusName":"签退处理中"}""",
            """{"id":"99887766","bookingStatus":5,"bookingStatusName":"已签退"}""",
            """{"id":"99887766","bookingStatus":1,"bookingStatusName":"已取消"}""",
            """{"id":"99887766","bookingStatus":99,"bookingStatusName":""}"""
        )
        for (badRow in rejectedRows) {
            val hist = """{"code":200,"total":1,"rows":[$badRow]}"""
            val res = runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadback(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg,
                    histBody = hist,
                    lastMsg = "测试"
                )
            }
            assertTrue("Must reject non-terminal/contradictory row: $badRow", res.isFailure)
        }

        // 2. Another booking ID cancelled (11112222) while target 99887766 is absent MUST throw
        val otherBookingHist = """{"code":200,"total":1,"rows":[{"id":"11112222","bookingStatus":5,"bookingStatusName":"已取消"}]}"""
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadback(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg,
                    histBody = otherBookingHist
                )
            }.isFailure
        )

        // 3. Explicit CANCELLED (5) and SIGNED_OUT (6) for exact 99887766 succeed
        val confirmedCancelledHist = """{"code":200,"total":1,"rows":[{"id":"99887766","bookingStatus":5,"bookingStatusName":"已取消"}]}"""
        val msgCancel = HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadback(
            resolvedId = "99887766",
            curBody = emptyCur,
            inProgBody = """{"code":200,"total":1,"rows":[{"id":"99887766","bookingStatus":5,"bookingStatusName":"已取消"}]}""",
            histBody = confirmedCancelledHist
        )
        assertTrue(msgCancel.contains("99887766"))

        val confirmedSignedOutHist = """{"code":200,"total":1,"rows":[{"id":"99887766","bookingStatus":6,"bookingStatusName":"已签退"}]}"""
        val msgSignOut = HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadback(
            resolvedId = "99887766",
            curBody = emptyCur,
            inProgBody = emptyInProg,
            histBody = confirmedSignedOutHist
        )
        assertTrue(msgSignOut.contains("99887766"))
    }

    @Test
    fun `viewer reset sanitization re-masks unlocked ON_REQUEST contacts while keeping truly public contacts`() {
        val accountASnapJson = """
            {
              "updated_at": "2026-11-10T01:00:00Z",
              "posts": [
                {
                  "id": "mp_protected_unlocked_for_a",
                  "type": "LOST",
                  "title": "受保护联系方式失物帖",
                  "category": "证件",
                  "campus": "莲花街校区",
                  "location": "图书馆三楼",
                  "description": "卡套内有饭卡",
                  "contact": "微信: secret_approved_for_A",
                  "contact_visibility": "ON_REQUEST",
                  "has_protected_contact": false,
                  "is_owner": false,
                  "can_manage": false,
                  "viewer_role": "AUTHENTICATED",
                  "comments": []
                },
                {
                  "id": "mp_public_normal",
                  "type": "FOUND",
                  "title": "公开联系方式招领帖",
                  "category": "水杯雨伞",
                  "campus": "莲花街校区",
                  "location": "一餐",
                  "description": "黑色保温杯",
                  "contact": "QQ: 88889999",
                  "contact_visibility": "PUBLIC",
                  "has_protected_contact": false,
                  "is_owner": true,
                  "can_manage": true,
                  "viewer_role": "OWNER",
                  "comments": []
                }
              ]
            }
        """.trimIndent()
        val parsedForA = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(accountASnapJson)!!
        assertTrue(parsedForA.posts[0].canViewerReadRealContact)
        assertEquals("微信: secret_approved_for_A", parsedForA.posts[0].contact)

        val scrubbed = CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(parsedForA)
        val protectedAfterReset = scrubbed.posts.first { it.id == "mp_protected_unlocked_for_a" }
        val publicAfterReset = scrubbed.posts.first { it.id == "mp_public_normal" }

        assertEquals("需发起联系/认领申请后查看", protectedAfterReset.contact)
        assertTrue(protectedAfterReset.hasProtectedContact)
        assertFalse(protectedAfterReset.canViewerReadRealContact)

        assertEquals("QQ: 88889999", publicAfterReset.contact)
        assertTrue(publicAfterReset.canViewerReadRealContact)
        assertFalse(publicAfterReset.isOwner)
        assertFalse(publicAfterReset.canManage)
        assertEquals("GUEST", publicAfterReset.viewerRole)

        val persistedJson = CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(scrubbed)
        assertFalse(persistedJson.contains("secret_approved_for_A"))
    }

    @Test
    fun `paginated seat cancellation readback handles total over 20 on page 1, later pages, missing target, pagination failure, and duplicate or contradictory rows`() {
        val emptyCur = """{"code":200,"data":null}"""
        val emptyInProg = """{"code":200,"total":0,"rows":[]}"""

        fun buildRow(id: Long, status: Int = 6, statusName: String = "已签退"): String =
            """{"id":"$id","bookingStatus":$status,"bookingStatusName":"$statusName"}"""

        // 1. >20 historical records (total=65, pageSize=20) with target 99887766 on page 1 -> succeeds in 1 page request!
        val page1WithTargetAnd19Others = (
            listOf(buildRow(99887766L, status = 5, statusName = "已取消")) +
                (1L..19L).map { buildRow(80000000L + it) }
            ).joinToString(",")
        var pageFetchCallsCase1 = 0
        val resPage1Hit = HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
            resolvedId = "99887766",
            curBody = emptyCur,
            inProgBody = emptyInProg,
            pageSize = 20,
            maxPages = 5
        ) { pageNum, _ ->
            pageFetchCallsCase1++
            assertEquals(1, pageNum)
            """{"code":200,"total":65,"rows":[$page1WithTargetAnd19Others]}"""
        }
        assertTrue(resPage1Hit.contains("99887766"))
        assertEquals(1, pageFetchCallsCase1)

        // 2. >20 historical records (total=45, pageSize=20) with target 99887766 on page 3 -> does NOT confirm on page 1/2, confirms on page 3!
        val page1TwentyOthers = (1L..20L).joinToString(",") { buildRow(70000000L + it) }
        val page2TwentyOthers = (21L..40L).joinToString(",") { buildRow(70000000L + it) }
        val page3FiveWithTarget = (
            (41L..44L).map { buildRow(70000000L + it) } +
                listOf(buildRow(99887766L, status = 6, statusName = "已签退"))
            ).joinToString(",")
        val fetchedPagesCase2 = mutableListOf<Int>()
        val resPage3Hit = HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
            resolvedId = "99887766",
            curBody = emptyCur,
            inProgBody = emptyInProg,
            pageSize = 20,
            maxPages = 5
        ) { pageNum, _ ->
            fetchedPagesCase2 += pageNum
            when (pageNum) {
                1 -> """{"code":200,"total":45,"rows":[$page1TwentyOthers]}"""
                2 -> """{"code":200,"total":45,"rows":[$page2TwentyOthers]}"""
                3 -> """{"code":200,"total":45,"rows":[$page3FiveWithTarget]}"""
                else -> error("Unexpected page $pageNum")
            }
        }
        assertTrue(resPage3Hit.contains("99887766"))
        assertEquals(listOf(1, 2, 3), fetchedPagesCase2)

        // 3. Target does NOT exist across all pages (total=35 across 2 pages, or total=200 exceeding bounded maxPages=5) -> MUST fail!
        val page2FifteenOthers = (21L..35L).joinToString(",") { buildRow(70000000L + it) }
        val missingTargetRes = runCatching {
            HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                resolvedId = "99887766",
                curBody = emptyCur,
                inProgBody = emptyInProg,
                pageSize = 20,
                maxPages = 5
            ) { pageNum, _ ->
                when (pageNum) {
                    1 -> """{"code":200,"total":35,"rows":[$page1TwentyOthers]}"""
                    2 -> """{"code":200,"total":35,"rows":[$page2FifteenOthers]}"""
                    else -> error("Should stop at page 2")
                }
            }
        }
        assertTrue("Target absent across all 35 rows must fail", missingTargetRes.isFailure)

        val missingBeyondBoundedPagesRes = runCatching {
            HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                resolvedId = "99887766",
                curBody = emptyCur,
                inProgBody = emptyInProg,
                pageSize = 20,
                maxPages = 5
            ) { pageNum, _ ->
                val rows = (1L..20L).joinToString(",") { buildRow(60000000L + pageNum * 100L + it) }
                """{"code":200,"total":200,"rows":[$rows]}"""
            }
        }
        assertTrue("Target not found within bounded maxPages=5 must fail closed", missingBeyondBoundedPagesRes.isFailure)

        // 4. Pagination failures on page 2 (network exception, HTTP/code 500, empty rows before total, changing total) -> MUST ALL fail!
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg
                ) { pageNum, _ ->
                    if (pageNum == 1) """{"code":200,"total":45,"rows":[$page1TwentyOthers]}"""
                    else throw java.io.IOException("Socket timeout on page 2")
                }
            }.isFailure
        )
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg
                ) { pageNum, _ ->
                    if (pageNum == 1) """{"code":200,"total":45,"rows":[$page1TwentyOthers]}"""
                    else """{"code":500,"msg":"系统繁忙"}"""
                }
            }.isFailure
        )
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg
                ) { pageNum, _ ->
                    if (pageNum == 1) """{"code":200,"total":45,"rows":[$page1TwentyOthers]}"""
                    else """{"code":200,"total":45,"rows":[]}""" // premature empty page!
                }
            }.isFailure
        )

        // 5. Duplicate or contradictory records -> MUST ALL fail!
        // 5a: Same page has target first as CANCELLED(5) and later as FULFILLED(1) (guards against early-exit on first row!)
        val samePageContradictoryRows = (
            listOf(buildRow(99887766L, status = 5, statusName = "已取消")) +
                (1L..18L).map { buildRow(50000000L + it) } +
                listOf(buildRow(99887766L, status = 1, statusName = "待履约签到"))
            ).joinToString(",")
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg
                ) { _, _ -> """{"code":200,"total":60,"rows":[$samePageContradictoryRows]}""" }
            }.isFailure
        )

        // 5b: Same page has duplicate identical target ID rows
        val samePageDuplicateRows = listOf(
            buildRow(99887766L, status = 5, statusName = "已取消"),
            buildRow(99887766L, status = 5, statusName = "已取消")
        ).joinToString(",")
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg
                ) { _, _ -> """{"code":200,"total":2,"rows":[$samePageDuplicateRows]}""" }
            }.isFailure
        )

        // 5c: Cross-endpoint contradiction (inProgress says 5=已取消, history page 2 says 6=已签退 or 1=待履约签到)
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = """{"code":200,"total":1,"rows":[${buildRow(99887766L, 5, "已取消")}]}"""
                ) { pageNum, _ ->
                    if (pageNum == 1) """{"code":200,"total":25,"rows":[$page1TwentyOthers]}"""
                    else """{"code":200,"total":25,"rows":[${buildRow(70000091L)},${buildRow(70000092L)},${buildRow(70000093L)},${buildRow(70000094L)},${buildRow(99887766L, 6, "已签退")}]}"""
                }
            }.isFailure
        )

        // 5d: Cross-page duplicate row ID during pagination
        assertTrue(
            runCatching {
                HautLibrarySeatAuthClient.verifyAuthoritativeSeatCancellationReadbackPaginated(
                    resolvedId = "99887766",
                    curBody = emptyCur,
                    inProgBody = emptyInProg
                ) { pageNum, _ ->
                    if (pageNum == 1) """{"code":200,"total":22,"rows":[$page1TwentyOthers]}"""
                    else """{"code":200,"total":22,"rows":[${buildRow(70000020L)},${buildRow(99887766L, 5, "已取消")}]}"""
                }
            }.isFailure
        )
    }
}
