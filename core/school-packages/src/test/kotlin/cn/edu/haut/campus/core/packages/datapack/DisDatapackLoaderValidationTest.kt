package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.util.Base64

class DisDatapackLoaderValidationTest {

    @Test
    fun sharedFixtureDecodesToTypedPackAndExplicitFreshnessStates() {
        val decoded = DisDatapackLoader.decode(fixtureBytes())
        assertEquals("haut.procedures", decoded.pack.packId)
        assertEquals(DisDatapackKind.PROCEDURE_GUIDE, decoded.pack.kind)
        assertEquals(VerificationLevel.UNVERIFIED, decoded.pack.verificationLevel)
        assertEquals("8a10ddfd1cc74d5b3f50b8208becef0ad8014b7702878bc16e7ee7446224bf47", decoded.artifactSha256)
        val item = decoded.pack.items.single() as DisDatapackItem.ProcedureGuide
        assertEquals("student-certificate", item.id)
        assertEquals(true, item.materials.single().required)
        assertEquals(DataPackFreshness.CURRENT, DisDatapackLoader.freshness(decoded.pack, LocalDate.parse("2027-05-20")))
        assertEquals(DataPackFreshness.REVIEW_DUE, DisDatapackLoader.freshness(decoded.pack, LocalDate.parse("2027-05-21")))
    }


    @Test
    fun mergedCt06SchemaKeepsMirroredRootShapeAndFixedEnumSets() {
        val schema = Json.parse(readContractSchema())
        assertEquals(
            setOf("format", "packId", "schoolId", "kind", "version", "sources", "verifiedAt", "reviewBy", "maintainers", "items"),
            schema.arr("required")!!.map { it.stringOrNull() }.toSet()
        )
        val properties = schema.obj("properties")!!.asObjOrNull!!
        val kindValues = properties.fields["kind"]!!.asObjOrNull!!.fields["enum"]!!.asArrOrNull!!.items.map { it.stringOrNull() }
        assertEquals(DisDatapackKind.entries.map { it.wireId }.toSet(), kindValues.toSet())
        val levelValues = properties.fields["verificationLevel"]!!.asObjOrNull!!.fields["enum"]!!.asArrOrNull!!.items.map { it.stringOrNull() }
        assertEquals(DIS_DATAPACK_VERIFICATION_LEVELS, levelValues.toSet())
        val description = schema.str("description")!!
        assertTrue(description.contains("bytes to equal RFC8785(parse(bytes))"))
        assertTrue(description.contains("dis-datapack/v1, LF, lowercase SHA-256"))
        val defs = schema.obj("\$defs")!!.asObjOrNull!!
        for (name in listOf("procedureGuideItem", "opportunityCatalogItem", "facilityCatalogItem", "indoorGraphItem", "periodPresetItem", "pharmacyCatalogItem")) {
            assertTrue(defs.fields.containsKey(name))
        }
    }


    @Test
    fun ct06ContractExampleArtifactPassesExactByteSchemaDecoding() {
        val examples = readContractFile("contracts/examples/cloud-api-examples.http")
        val match = Regex("\\\"artifact_base64\\\":\\s*\\\"([A-Za-z0-9+/=]+)\\\"").find(examples)
        assertTrue(match != null)
        val artifact = Base64.getDecoder().decode(match!!.groupValues[1])
        assertEquals("2af33a7f0e4574c501a6cb0513e68e191fd9757e2efb325d584d2d9daffcbca5", Digests.sha256Hex(artifact))
        val decoded = DisDatapackLoader.decode(artifact)
        assertEquals("haut.procedures", decoded.pack.packId)
        assertEquals("2026.11.1", decoded.pack.version)
        assertEquals(VerificationLevel.UNVERIFIED, decoded.pack.verificationLevel)
    }

    @Test
    fun validatorCoversAllSixKindsAndSchemaDefaults() {
        val root = rootObject()
        val procedure = validateWith(root, "procedure-guide", root.arr("items")!!.single())
        assertEquals(1, (procedure.items.single() as DisDatapackItem.ProcedureGuide).steps.size)

        val opportunity = validateWith(root, "opportunity-catalog", parse("""
            {"id":"certification-example","title":"示例证书","category":"certification","requirements":[{"id":"eligible","text":"在校学生"}],"materials":[]}
        """))
        assertEquals(false, (opportunity.items.single() as DisDatapackItem.OpportunityCatalog).requirements.single().computable)

        val facility = validateWith(root, "facility-catalog", parse("""
            {"id":"service-desk-example","title":"服务台","facilityType":"service-desk","placeRef":"haut/lianhuajie/building/administration"}
        """))
        assertEquals("service-desk", (facility.items.single() as DisDatapackItem.FacilityCatalog).facilityType)

        val indoor = validateWith(root, "indoor-graph", parse("""
            {"id":"node-1","title":"入口节点","itemType":"node","nodeKind":"entrance","latitude":34.0,"longitude":113.0}
        """))
        assertEquals(34.0, (indoor.items.single() as DisDatapackItem.IndoorGraph).latitude!!, 0.0)

        val period = validateWith(root, "period-preset", parse("""
            {"id":"default-periods","title":"默认作息","periods":[{"index":1,"start":"08:00","end":"08:45"}]}
        """))
        assertEquals(1, (period.items.single() as DisDatapackItem.PeriodPreset).periods.size)

        val pharmacy = validateWith(root, "pharmacy-catalog", parse("""
            {"id":"medicine-example","title":"示例药品","category":"otc"}
        """))
        assertEquals(PharmacyStockState.UNKNOWN, (pharmacy.items.single() as DisDatapackItem.PharmacyCatalog).stockState)
    }

    @Test
    fun schemaRejectsAdditionalPropertiesTypeErrorsDuplicatesAndPackScopeMismatch() {
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a"],"items":[],"unexpected":true}""")
        }
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"other.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"https://example.com","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a"],"items":[]}""")
        }
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"http://example.com","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a"],"items":[]}""")
        }
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"https://example.com/%zz","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a"],"items":[]}""")
        }
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"https://example.com/{}","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a"],"items":[]}""")
        }
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"https://example.com","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a","github:a"],"items":[]}""")
        }
        assertSchemaError {
            val ecmaWhitespaceMaintainer = "github:a" + '\u00A0' + "x"
            validateRootJson(rootJsonWithMaintainerJson("\"" + ecmaWhitespaceMaintainer + "\""))
        }
        assertSchemaError {
            validateRootJson("""{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"https://example.com","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":["github:a"],"items":[{"id":"x","title":"x","steps":[{"id":"s","title":"s","instruction":"i","estimatedMinutes":10081}],"unexpected":true}]}""")
        }
    }


    @Test
    fun byteIdenticalCrossEndSchemaVectorsKeepRfc3339AndUriMirrorSemanticsAligned() {
        val vectors = CrossEndDatapackVectors.document()
        val cases = vectors.arr("schema_cases")!!
        assertTrue(cases.size >= 20)
        for (vectorCase in cases) {
            val name = vectorCase.str("name")!!
            val target = vectorCase.str("target")!!
            val value = vectorCase.str("value")!!
            val expectedValid = vectorCase.obj("valid")?.boolOrNull()!!
            val base = if (target.startsWith("opportunity.")) vectorOpportunityRoot() else rootObject().asObjOrNull!!
            val candidate = applySchemaVector(base, target, value)
            var validated: DisDatapack? = null
            var failure: PackageException? = null
            try {
                validated = DisDatapackValidator.validate(candidate)
            } catch (error: PackageException) {
                failure = error
            }
            assertEquals(name + ": " + (failure?.message ?: "unexpected acceptance"), expectedValid, failure == null)
            if (expectedValid) {
                when (target) {
                    "root.verifiedAt" -> assertEquals(value, validated!!.verifiedAt)
                    "source.url" -> assertEquals(value, validated!!.sources.single().url)
                    "opportunity.deadlineAt" -> assertEquals(value, (validated!!.items.single() as DisDatapackItem.OpportunityCatalog).deadlineAt)
                    "opportunity.applicationUrl" -> assertEquals(value, (validated!!.items.single() as DisDatapackItem.OpportunityCatalog).applicationUrl)
                }
            } else {
                assertEquals(RUNTIME_DATAPACK_SCHEMA_INVALID, failure!!.runtimeValidationCode)
            }
        }
    }

    private fun readContractSchema(): String = readContractFile("contracts/schemas/dis-datapack-v1.schema.json")

    private fun readContractFile(relativePath: String): String {
        var dir = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relativePath)
            if (Files.isRegularFile(candidate)) return String(Files.readAllBytes(candidate), Charsets.UTF_8)
            dir = dir.parent
        }
        throw AssertionError(relativePath + " not found from test working directory")
    }

    private fun vectorOpportunityRoot(): JsonValue.Obj {
        val root = rootObject().asObjOrNull ?: error("root must be object")
        val item = parse("""
            {"id":"certification-example","title":"示例证书","category":"certification","requirements":[{"id":"eligible","text":"在校学生"}],"materials":[]}
        """)
        val fields = LinkedHashMap(root.fields)
        fields["kind"] = JsonValue.Str("opportunity-catalog")
        fields["items"] = JsonValue.Arr(listOf(item))
        return JsonValue.Obj(fields)
    }

    private fun applySchemaVector(root: JsonValue.Obj, target: String, value: String): JsonValue.Obj = when (target) {
        "root.verifiedAt" -> withField(root, "verifiedAt", JsonValue.Str(value))
        "source.url" -> {
            val sources = root.fields["sources"]!!.asArrOrNull!!.items
            val first = sources.single().asObjOrNull!!
            val updated = withField(first, "url", JsonValue.Str(value))
            withField(root, "sources", JsonValue.Arr(listOf(updated)))
        }
        "opportunity.deadlineAt" -> withOpportunityField(root, "deadlineAt", value)
        "opportunity.applicationUrl" -> withOpportunityField(root, "applicationUrl", value)
        else -> error("unknown shared vector target: " + target)
    }

    private fun withOpportunityField(root: JsonValue.Obj, key: String, value: String): JsonValue.Obj {
        val items = root.fields["items"]!!.asArrOrNull!!.items
        val item = items.single().asObjOrNull!!
        val updatedItem = withField(item, key, JsonValue.Str(value))
        return withField(root, "items", JsonValue.Arr(listOf(updatedItem)))
    }

    private fun withField(obj: JsonValue.Obj, key: String, value: JsonValue): JsonValue.Obj {
        val fields = LinkedHashMap(obj.fields)
        fields[key] = value
        return JsonValue.Obj(fields)
    }

    private fun validateWith(root: cn.edu.haut.campus.core.model.json.JsonValue, kind: String, item: cn.edu.haut.campus.core.model.json.JsonValue): DisDatapack {
        val obj = root.asObjOrNull ?: error("root must be object")
        val fields = LinkedHashMap(obj.fields)
        fields["kind"] = cn.edu.haut.campus.core.model.json.JsonValue.Str(kind)
        fields["items"] = cn.edu.haut.campus.core.model.json.JsonValue.Arr(listOf(item))
        return DisDatapackValidator.validate(cn.edu.haut.campus.core.model.json.JsonValue.Obj(fields))
    }

    private fun validateRootJson(text: String): DisDatapack = DisDatapackValidator.validate(Json.parse(text))

    private fun rootJsonWithMaintainerJson(maintainerJson: String): String =
        """{"format":"dis-datapack/v1","packId":"haut.procedures","schoolId":"haut","kind":"procedure-guide","version":"2026.11.1","sources":[{"url":"https://example.com","title":"x","retrievedAt":"2026-11-20"}],"verifiedAt":"2026-11-20","reviewBy":"2027-05-20","maintainers":[""" + maintainerJson + """],"items":[]}"""

    private fun rootObject() = cn.edu.haut.campus.core.model.json.Json.parse(String(fixtureBytes(), Charsets.UTF_8))

    private fun parse(text: String) = cn.edu.haut.campus.core.model.json.Json.parse(text.trimIndent())

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/valid-procedure.json")).use { it.readBytes() }

    private fun assertSchemaError(block: () -> Unit) {
        try {
            block()
        } catch (error: PackageException) {
            assertEquals(RUNTIME_DATAPACK_SCHEMA_INVALID, error.runtimeValidationCode)
            return
        }
        throw AssertionError("expected schema PackageException")
    }
}
