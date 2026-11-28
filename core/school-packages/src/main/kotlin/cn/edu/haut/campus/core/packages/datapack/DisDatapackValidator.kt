package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue

private val PACK_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{2,127}$")
private val SCHOOL_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{1,63}$")
private val VERSION_PATTERN = Regex("^(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})(-[0-9A-Za-z][0-9A-Za-z.-]{0,31})?$")
private val ITEM_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._/-]{0,119}$")
private val TIME_PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

private data class BaseItemFields(
    val id: String,
    val title: String,
    val summary: String?,
    val provenance: DataPackProvenanceOverride?
)

/** Strict hand validator mirroring the CT-06 dis-datapack/v1 JSON Schema shape without reading contracts/ at runtime. */
object DisDatapackValidator {
    fun validate(document: JsonValue): DisDatapack {
        val root = expectObject(
            document,
            "$",
            setOf("format", "packId", "schoolId", "kind", "version", "sources", "verifiedAt", "reviewBy", "maintainers", "verificationLevel", "items"),
            setOf("format", "packId", "schoolId", "kind", "version", "sources", "verifiedAt", "reviewBy", "maintainers", "items")
        )
        val format = expectEnumWireValue(root.fields["format"], "$.format", setOf(DIS_DATAPACK_FORMAT))
        check(format == DIS_DATAPACK_FORMAT)
        val packId = expectStringValue(root.fields["packId"], "$.packId", min = 3, max = 128, pattern = PACK_ID_PATTERN)
        val schoolId = expectStringValue(root.fields["schoolId"], "$.schoolId", min = 2, max = 64, pattern = SCHOOL_ID_PATTERN)
        if (packId.split('.').firstOrNull() != schoolId) {
            schemaFail("$.packId", "first dot-separated segment must equal the complete dot-free schoolId")
        }
        val kindRaw = expectEnumWireValue(root.fields["kind"], "$.kind", DisDatapackKind.entries.map { it.wireId }.toSet())
        val kind = DisDatapackKind.fromWireId(kindRaw) ?: schemaFail("$.kind", "unsupported kind")
        val verificationLevel = if (root.fields["verificationLevel"] == null) {
            VerificationLevel.UNVERIFIED
        } else {
            parseVerificationLevel(root.fields["verificationLevel"], "$.verificationLevel")
        }
        return DisDatapack(
            packId = packId,
            schoolId = schoolId,
            kind = kind,
            version = expectStringValue(root.fields["version"], "$.version", pattern = VERSION_PATTERN),
            sources = validateSources(root.fields["sources"], "$.sources"),
            verifiedAt = expectDateValue(root.fields["verifiedAt"], "$.verifiedAt"),
            reviewBy = expectDateValue(root.fields["reviewBy"], "$.reviewBy"),
            maintainers = validateMaintainers(root.fields["maintainers"], "$.maintainers"),
            verificationLevel = verificationLevel,
            items = mapArray(expectArrayValue(root.fields["items"], "$.items"), "$.items") { item, path -> validateItem(item, path, kind) }
        )
    }

    private fun parseVerificationLevel(value: JsonValue?, path: String): VerificationLevel {
        val raw = expectEnumWireValue(value, path, DIS_DATAPACK_VERIFICATION_LEVELS)
        return VerificationLevel.valueOf(raw)
    }


    private fun validateMaintainer(value: JsonValue?, path: String): String {
        val raw = expectStringValue(value, path, min = 2, max = 120)
        val prefixMatch = Regex("^[a-z0-9-]+:").find(raw)
        if (prefixMatch == null) schemaFail(path, "does not match required pattern ^[a-z0-9-]+:[^\\s]+$")
        val suffix = raw.substring(prefixMatch!!.value.length)
        if (suffix.isEmpty() || suffix.any(::isEcmaScriptWhitespace)) {
            schemaFail(path, "does not match required pattern ^[a-z0-9-]+:[^\\s]+$")
        }
        return raw
    }

    /** ECMAScript \s；Java/Kotlin Regex \s 不覆盖 NBSP/Zs/FEFF，不能用于跨端契约 parity。 */
    private fun isEcmaScriptWhitespace(ch: Char): Boolean {
        val code = ch.code
        return code == 0x0009 || code == 0x000A || code == 0x000B ||
            code == 0x000C || code == 0x000D || code == 0x0020 ||
            code == 0x00A0 || code == 0x1680 ||
            code in 0x2000..0x200A ||
            code == 0x2028 || code == 0x2029 || code == 0x202F ||
            code == 0x205F || code == 0x3000 || code == 0xFEFF
    }

    private fun validateMaintainers(value: JsonValue?, path: String): List<String> = expectUniqueStrings(
        mapArray(expectArrayValue(value, path, minItems = 1), path) { item, itemPath ->
            validateMaintainer(item, itemPath)
        },
        path
    )

    private fun validateItem(value: JsonValue, path: String, kind: DisDatapackKind): DisDatapackItem = when (kind) {
        DisDatapackKind.PROCEDURE_GUIDE -> validateProcedureGuideItem(value, path)
        DisDatapackKind.OPPORTUNITY_CATALOG -> validateOpportunityCatalogItem(value, path)
        DisDatapackKind.FACILITY_CATALOG -> validateFacilityCatalogItem(value, path)
        DisDatapackKind.INDOOR_GRAPH -> validateIndoorGraphItem(value, path)
        DisDatapackKind.PERIOD_PRESET -> validatePeriodPresetItem(value, path)
        DisDatapackKind.PHARMACY_CATALOG -> validatePharmacyCatalogItem(value, path)
    }

    private fun validateSource(value: JsonValue, path: String): DataPackSource {
        val obj = expectObject(value, path, setOf("url", "title", "retrievedAt"), setOf("url", "title", "retrievedAt"))
        return DataPackSource(
            url = expectHttpsUriValue(obj.fields["url"], childPath(path, "url")),
            title = expectStringValue(obj.fields["title"], childPath(path, "title"), min = 1, max = 200),
            retrievedAt = expectDateValue(obj.fields["retrievedAt"], childPath(path, "retrievedAt"))
        )
    }

    private fun validateSources(value: JsonValue?, path: String): List<DataPackSource> =
        mapArray(expectArrayValue(value, path, minItems = 1), path) { item, itemPath -> validateSource(item, itemPath) }

    private fun validateProvenance(value: JsonValue?, path: String): DataPackProvenanceOverride? {
        if (value == null) return null
        val obj = expectObject(value, path, setOf("sources", "verifiedAt", "reviewBy", "verificationLevel"), emptySet())
        return DataPackProvenanceOverride(
            sources = obj.fields["sources"]?.let { validateSources(it, childPath(path, "sources")) },
            verifiedAt = obj.fields["verifiedAt"]?.let { expectDateValue(it, childPath(path, "verifiedAt")) },
            reviewBy = obj.fields["reviewBy"]?.let { expectDateValue(it, childPath(path, "reviewBy")) },
            verificationLevel = obj.fields["verificationLevel"]?.let { parseVerificationLevel(it, childPath(path, "verificationLevel")) }
        )
    }

    private fun validateBaseItem(obj: JsonValue.Obj, path: String): BaseItemFields = BaseItemFields(
        id = expectStringValue(obj.fields["id"], childPath(path, "id"), min = 1, max = 120, pattern = ITEM_ID_PATTERN),
        title = expectStringValue(obj.fields["title"], childPath(path, "title"), min = 1, max = 160),
        summary = obj.fields["summary"]?.let { expectStringValue(it, childPath(path, "summary"), max = 500) },
        provenance = validateProvenance(obj.fields["provenance"], childPath(path, "provenance"))
    )

    private fun validateMaterial(value: JsonValue, path: String): DataPackMaterial {
        val obj = expectObject(value, path, setOf("name", "required", "validDays", "note"), setOf("name"))
        return DataPackMaterial(
            name = expectStringValue(obj.fields["name"], childPath(path, "name"), min = 1, max = 200),
            required = obj.fields["required"]?.let { expectBooleanValue(it, childPath(path, "required")) } ?: true,
            validDays = obj.fields["validDays"]?.let { expectNullableIntegerValue(it, childPath(path, "validDays"), minimum = 1, maximum = 3650) },
            note = obj.fields["note"]?.let { expectNullableStringValue(it, childPath(path, "note"), max = 500) }
        )
    }

    private fun validateMaterials(value: JsonValue?, path: String): List<DataPackMaterial> {
        if (value == null) return emptyList()
        return mapArray(expectArrayValue(value, path), path) { item, itemPath -> validateMaterial(item, itemPath) }
    }

    private fun validateContact(value: JsonValue, path: String): DataPackContact {
        val obj = expectObject(value, path, setOf("kind", "value", "label"), setOf("kind", "value"))
        val kindRaw = expectEnumWireValue(obj.fields["kind"], childPath(path, "kind"), DataPackContactKind.entries.map { it.wireId }.toSet())
        return DataPackContact(
            kind = DataPackContactKind.fromWireId(kindRaw) ?: schemaFail(childPath(path, "kind"), "unsupported contact kind"),
            value = expectStringValue(obj.fields["value"], childPath(path, "value"), min = 1, max = 240),
            label = obj.fields["label"]?.let { expectNullableStringValue(it, childPath(path, "label"), max = 120) }
        )
    }

    private fun validateContacts(value: JsonValue?, path: String): List<DataPackContact> {
        if (value == null) return emptyList()
        return mapArray(expectArrayValue(value, path), path) { item, itemPath -> validateContact(item, itemPath) }
    }

    private fun validateProcedureStep(value: JsonValue, path: String): ProcedureStep {
        val obj = expectObject(value, path, setOf("id", "title", "instruction", "placeRef", "estimatedMinutes"), setOf("id", "title", "instruction"))
        return ProcedureStep(
            id = expectStringValue(obj.fields["id"], childPath(path, "id"), min = 1, max = 80),
            title = expectStringValue(obj.fields["title"], childPath(path, "title"), min = 1, max = 160),
            instruction = expectStringValue(obj.fields["instruction"], childPath(path, "instruction"), min = 1, max = 2000),
            placeRef = obj.fields["placeRef"]?.let { expectNullableStringValue(it, childPath(path, "placeRef"), max = 240) },
            estimatedMinutes = obj.fields["estimatedMinutes"]?.let { expectNullableIntegerValue(it, childPath(path, "estimatedMinutes"), minimum = 0, maximum = 10080) }
        )
    }

    private fun validateProcedureQuestion(value: JsonValue, path: String): ProcedureQuestion {
        val obj = expectObject(value, path, setOf("id", "question", "options"), setOf("id", "question", "options"))
        val optionsPath = childPath(path, "options")
        val options = mapArray(expectArrayValue(obj.fields["options"], optionsPath, minItems = 2), optionsPath) { option, optionPath ->
            val optionObject = expectObject(option, optionPath, setOf("value", "label", "nextStepId"), setOf("value", "label"))
            ProcedureQuestionOption(
                value = expectStringValue(optionObject.fields["value"], childPath(optionPath, "value"), min = 1, max = 80),
                label = expectStringValue(optionObject.fields["label"], childPath(optionPath, "label"), min = 1, max = 160),
                nextStepId = optionObject.fields["nextStepId"]?.let { expectNullableStringValue(it, childPath(optionPath, "nextStepId"), max = 80) }
            )
        }
        return ProcedureQuestion(
            id = expectStringValue(obj.fields["id"], childPath(path, "id"), min = 1, max = 80),
            question = expectStringValue(obj.fields["question"], childPath(path, "question"), min = 1, max = 500),
            options = options
        )
    }

    private fun validateProcedureGuideItem(value: JsonValue, path: String): DisDatapackItem.ProcedureGuide {
        val obj = expectObject(
            value,
            path,
            setOf("id", "title", "summary", "provenance", "steps", "questions", "materials", "placeRef", "officeHours", "contacts", "dependsOn"),
            setOf("id", "title", "steps")
        )
        val base = validateBaseItem(obj, path)
        val depends = obj.fields["dependsOn"]?.let { raw ->
            expectUniqueStrings(
                mapArray(expectArrayValue(raw, childPath(path, "dependsOn")), childPath(path, "dependsOn")) { item, itemPath ->
                    expectStringValue(item, itemPath, max = 120)
                },
                childPath(path, "dependsOn")
            )
        } ?: emptyList()
        return DisDatapackItem.ProcedureGuide(
            id = base.id,
            title = base.title,
            summary = base.summary,
            provenance = base.provenance,
            steps = mapArray(expectArrayValue(obj.fields["steps"], childPath(path, "steps"), minItems = 1), childPath(path, "steps")) { item, itemPath -> validateProcedureStep(item, itemPath) },
            questions = obj.fields["questions"]?.let { raw -> mapArray(expectArrayValue(raw, childPath(path, "questions")), childPath(path, "questions")) { item, itemPath -> validateProcedureQuestion(item, itemPath) } } ?: emptyList(),
            materials = validateMaterials(obj.fields["materials"], childPath(path, "materials")),
            placeRef = obj.fields["placeRef"]?.let { expectNullableStringValue(it, childPath(path, "placeRef"), max = 240) },
            officeHours = obj.fields["officeHours"]?.let { expectNullableStringValue(it, childPath(path, "officeHours"), max = 240) },
            contacts = validateContacts(obj.fields["contacts"], childPath(path, "contacts")),
            dependsOn = depends
        )
    }

    private fun validateRequirement(value: JsonValue, path: String): OpportunityRequirement {
        val obj = expectObject(value, path, setOf("id", "text", "computable"), setOf("id", "text"))
        return OpportunityRequirement(
            id = expectStringValue(obj.fields["id"], childPath(path, "id"), min = 1, max = 80),
            text = expectStringValue(obj.fields["text"], childPath(path, "text"), min = 1, max = 500),
            computable = obj.fields["computable"]?.let { expectBooleanValue(it, childPath(path, "computable")) } ?: false
        )
    }

    private fun validateOpportunityCatalogItem(value: JsonValue, path: String): DisDatapackItem.OpportunityCatalog {
        val obj = expectObject(
            value,
            path,
            setOf("id", "title", "summary", "provenance", "category", "organizer", "deadlineAt", "requirements", "materials", "applicationUrl"),
            setOf("id", "title", "category", "requirements", "materials")
        )
        val base = validateBaseItem(obj, path)
        val categoryRaw = expectEnumWireValue(obj.fields["category"], childPath(path, "category"), OpportunityCategory.entries.map { it.wireId }.toSet())
        return DisDatapackItem.OpportunityCatalog(
            id = base.id,
            title = base.title,
            summary = base.summary,
            provenance = base.provenance,
            category = OpportunityCategory.fromWireId(categoryRaw) ?: schemaFail(childPath(path, "category"), "unsupported opportunity category"),
            organizer = obj.fields["organizer"]?.let { expectNullableStringValue(it, childPath(path, "organizer"), max = 200) },
            deadlineAt = obj.fields["deadlineAt"]?.let { if (it is JsonValue.Null) null else expectDateTimeValue(it, childPath(path, "deadlineAt")) },
            requirements = mapArray(expectArrayValue(obj.fields["requirements"], childPath(path, "requirements"), minItems = 1), childPath(path, "requirements")) { item, itemPath -> validateRequirement(item, itemPath) },
            materials = validateMaterials(obj.fields["materials"], childPath(path, "materials")),
            applicationUrl = obj.fields["applicationUrl"]?.let { if (it is JsonValue.Null) null else expectHttpsUriValue(it, childPath(path, "applicationUrl")) }
        )
    }

    private fun validateFacilityCatalogItem(value: JsonValue, path: String): DisDatapackItem.FacilityCatalog {
        val obj = expectObject(
            value,
            path,
            setOf("id", "title", "summary", "provenance", "facilityType", "placeRef", "capabilities", "openingHours", "accessNotes"),
            setOf("id", "title", "facilityType", "placeRef")
        )
        val base = validateBaseItem(obj, path)
        val capabilities = obj.fields["capabilities"]?.let { raw ->
            expectUniqueStrings(
                mapArray(expectArrayValue(raw, childPath(path, "capabilities")), childPath(path, "capabilities")) { item, itemPath ->
                    expectStringValue(item, itemPath, max = 80)
                },
                childPath(path, "capabilities")
            )
        } ?: emptyList()
        return DisDatapackItem.FacilityCatalog(
            id = base.id,
            title = base.title,
            summary = base.summary,
            provenance = base.provenance,
            facilityType = expectStringValue(obj.fields["facilityType"], childPath(path, "facilityType"), min = 1, max = 80),
            placeRef = expectStringValue(obj.fields["placeRef"], childPath(path, "placeRef"), min = 3, max = 240),
            capabilities = capabilities,
            openingHours = obj.fields["openingHours"]?.let { expectNullableStringValue(it, childPath(path, "openingHours"), max = 240) },
            accessNotes = obj.fields["accessNotes"]?.let { expectNullableStringValue(it, childPath(path, "accessNotes"), max = 500) }
        )
    }

    private fun validateIndoorGraphItem(value: JsonValue, path: String): DisDatapackItem.IndoorGraph {
        val obj = expectObject(
            value,
            path,
            setOf("id", "title", "summary", "provenance", "itemType", "placeRef", "levelRef", "nodeKind", "latitude", "longitude", "fromNodeId", "toNodeId", "distanceMeters", "accessible"),
            setOf("id", "title", "itemType")
        )
        val base = validateBaseItem(obj, path)
        val typeRaw = expectEnumWireValue(obj.fields["itemType"], childPath(path, "itemType"), IndoorGraphItemType.entries.map { it.wireId }.toSet())
        val nodeKindRaw = obj.fields["nodeKind"]?.let {
            expectNullableEnumWireValue(it, childPath(path, "nodeKind"), IndoorGraphNodeKind.entries.map { kind -> kind.wireId }.toSet())
        }
        return DisDatapackItem.IndoorGraph(
            id = base.id,
            title = base.title,
            summary = base.summary,
            provenance = base.provenance,
            itemType = IndoorGraphItemType.fromWireId(typeRaw) ?: schemaFail(childPath(path, "itemType"), "unsupported indoor item type"),
            placeRef = obj.fields["placeRef"]?.let { expectNullableStringValue(it, childPath(path, "placeRef"), max = 240) },
            levelRef = obj.fields["levelRef"]?.let { expectNullableStringValue(it, childPath(path, "levelRef"), max = 120) },
            nodeKind = nodeKindRaw?.let { IndoorGraphNodeKind.fromWireId(it) ?: schemaFail(childPath(path, "nodeKind"), "unsupported indoor node kind") },
            latitude = obj.fields["latitude"]?.let { expectNullableNumberValue(it, childPath(path, "latitude"), minimum = -90.0, maximum = 90.0) },
            longitude = obj.fields["longitude"]?.let { expectNullableNumberValue(it, childPath(path, "longitude"), minimum = -180.0, maximum = 180.0) },
            fromNodeId = obj.fields["fromNodeId"]?.let { expectNullableStringValue(it, childPath(path, "fromNodeId"), max = 120) },
            toNodeId = obj.fields["toNodeId"]?.let { expectNullableStringValue(it, childPath(path, "toNodeId"), max = 120) },
            distanceMeters = obj.fields["distanceMeters"]?.let { expectNullableNumberValue(it, childPath(path, "distanceMeters"), minimum = 0.0, maximum = 10000.0) },
            accessible = obj.fields["accessible"]?.let { expectNullableBooleanValue(it, childPath(path, "accessible")) }
        )
    }

    private fun validatePeriod(value: JsonValue, path: String): PeriodPresetEntry {
        val obj = expectObject(value, path, setOf("index", "start", "end"), setOf("index", "start", "end"))
        return PeriodPresetEntry(
            index = expectIntegerValue(obj.fields["index"], childPath(path, "index"), minimum = 1, maximum = 40),
            start = expectStringValue(obj.fields["start"], childPath(path, "start"), pattern = TIME_PATTERN),
            end = expectStringValue(obj.fields["end"], childPath(path, "end"), pattern = TIME_PATTERN)
        )
    }

    private fun validatePeriodPresetItem(value: JsonValue, path: String): DisDatapackItem.PeriodPreset {
        val obj = expectObject(value, path, setOf("id", "title", "summary", "provenance", "periods"), setOf("id", "title", "periods"))
        val base = validateBaseItem(obj, path)
        return DisDatapackItem.PeriodPreset(
            id = base.id,
            title = base.title,
            summary = base.summary,
            provenance = base.provenance,
            periods = mapArray(expectArrayValue(obj.fields["periods"], childPath(path, "periods"), minItems = 1), childPath(path, "periods")) { item, itemPath -> validatePeriod(item, itemPath) }
        )
    }

    private fun validatePharmacyCatalogItem(value: JsonValue, path: String): DisDatapackItem.PharmacyCatalog {
        val obj = expectObject(
            value,
            path,
            setOf("id", "title", "summary", "provenance", "category", "specification", "usageNote", "stockState"),
            setOf("id", "title", "category")
        )
        val base = validateBaseItem(obj, path)
        val stockRaw = obj.fields["stockState"]?.let {
            expectEnumWireValue(it, childPath(path, "stockState"), PharmacyStockState.entries.map { state -> state.wireId }.toSet())
        }
        return DisDatapackItem.PharmacyCatalog(
            id = base.id,
            title = base.title,
            summary = base.summary,
            provenance = base.provenance,
            category = expectStringValue(obj.fields["category"], childPath(path, "category"), min = 1, max = 120),
            specification = obj.fields["specification"]?.let { expectNullableStringValue(it, childPath(path, "specification"), max = 160) },
            usageNote = obj.fields["usageNote"]?.let { expectNullableStringValue(it, childPath(path, "usageNote"), max = 500) },
            stockState = stockRaw?.let { PharmacyStockState.fromWireId(it) ?: schemaFail(childPath(path, "stockState"), "unsupported stock state") } ?: PharmacyStockState.UNKNOWN
        )
    }
}
