package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.VerificationLevel

const val DIS_DATAPACK_FORMAT: String = "dis-datapack/v1"
const val DIS_DATAPACK_SIGNATURE_SCHEME: String = "DIS_DATAPACK_CANONICAL_JSON_SHA256_V1"

/** CT-06 dis-datapack/v1 schema 固定允许的 verificationLevel；不得随宿主权威枚举未来扩展而放宽。 */
val DIS_DATAPACK_VERIFICATION_LEVELS: Set<String> = setOf(
    "UNVERIFIED",
    "SOURCE_CONFIRMED",
    "MOCK_TESTED",
    "DEVICE_TESTED",
    "SCHOOL_VERIFIED",
    "DISTRIBUTION_READY"
)

enum class DisDatapackKind(val wireId: String) {
    PROCEDURE_GUIDE("procedure-guide"),
    OPPORTUNITY_CATALOG("opportunity-catalog"),
    FACILITY_CATALOG("facility-catalog"),
    INDOOR_GRAPH("indoor-graph"),
    PERIOD_PRESET("period-preset"),
    PHARMACY_CATALOG("pharmacy-catalog");

    companion object {
        fun fromWireId(raw: String): DisDatapackKind? = entries.firstOrNull { it.wireId == raw }
    }
}

/** v1 artifact freshness. Catalog RETIRED is lifecycle state owned by W5-02, not a datapack artifact state. */
enum class DataPackFreshness {
    CURRENT,
    REVIEW_DUE
}

data class DataPackSource(
    val url: String,
    val title: String,
    val retrievedAt: String
)

data class DataPackProvenanceOverride(
    val sources: List<DataPackSource>? = null,
    val verifiedAt: String? = null,
    val reviewBy: String? = null,
    val verificationLevel: VerificationLevel? = null
)

data class DataPackMaterial(
    val name: String,
    val required: Boolean = true,
    val validDays: Int? = null,
    val note: String? = null
)

enum class DataPackContactKind(val wireId: String) {
    OFFICE("office"),
    PHONE("phone"),
    EMAIL("email"),
    WEBSITE("website");

    companion object {
        fun fromWireId(raw: String): DataPackContactKind? = entries.firstOrNull { it.wireId == raw }
    }
}

data class DataPackContact(
    val kind: DataPackContactKind,
    val value: String,
    val label: String? = null
)

data class ProcedureStep(
    val id: String,
    val title: String,
    val instruction: String,
    val placeRef: String? = null,
    val estimatedMinutes: Int? = null
)

data class ProcedureQuestionOption(
    val value: String,
    val label: String,
    val nextStepId: String? = null
)

data class ProcedureQuestion(
    val id: String,
    val question: String,
    val options: List<ProcedureQuestionOption>
)

data class OpportunityRequirement(
    val id: String,
    val text: String,
    val computable: Boolean = false
)

enum class OpportunityCategory(val wireId: String) {
    CERTIFICATION("certification"),
    COMPETITION("competition"),
    GRANT("grant"),
    SCHOLARSHIP("scholarship"),
    PROGRAM("program");

    companion object {
        fun fromWireId(raw: String): OpportunityCategory? = entries.firstOrNull { it.wireId == raw }
    }
}

enum class IndoorGraphItemType(val wireId: String) {
    NODE("node"),
    EDGE("edge"),
    LEVEL("level");

    companion object {
        fun fromWireId(raw: String): IndoorGraphItemType? = entries.firstOrNull { it.wireId == raw }
    }
}

enum class IndoorGraphNodeKind(val wireId: String) {
    ROOM("room"),
    CORRIDOR("corridor"),
    STAIRS("stairs"),
    ELEVATOR("elevator"),
    ENTRANCE("entrance"),
    JUNCTION("junction");

    companion object {
        fun fromWireId(raw: String): IndoorGraphNodeKind? = entries.firstOrNull { it.wireId == raw }
    }
}

data class PeriodPresetEntry(
    val index: Int,
    val start: String,
    val end: String
)

enum class PharmacyStockState(val wireId: String) {
    UNKNOWN("UNKNOWN"),
    LISTED_ONLY("LISTED_ONLY"),
    REPORTED_AVAILABLE("REPORTED_AVAILABLE"),
    REPORTED_UNAVAILABLE("REPORTED_UNAVAILABLE");

    companion object {
        fun fromWireId(raw: String): PharmacyStockState? = entries.firstOrNull { it.wireId == raw }
    }
}

sealed interface DisDatapackItem {
    val id: String
    val title: String
    val summary: String?
    val provenance: DataPackProvenanceOverride?

    data class ProcedureGuide(
        override val id: String,
        override val title: String,
        override val summary: String? = null,
        override val provenance: DataPackProvenanceOverride? = null,
        val steps: List<ProcedureStep>,
        val questions: List<ProcedureQuestion> = emptyList(),
        val materials: List<DataPackMaterial> = emptyList(),
        val placeRef: String? = null,
        val officeHours: String? = null,
        val contacts: List<DataPackContact> = emptyList(),
        val dependsOn: List<String> = emptyList()
    ) : DisDatapackItem

    data class OpportunityCatalog(
        override val id: String,
        override val title: String,
        override val summary: String? = null,
        override val provenance: DataPackProvenanceOverride? = null,
        val category: OpportunityCategory,
        val organizer: String? = null,
        val deadlineAt: String? = null,
        val requirements: List<OpportunityRequirement>,
        val materials: List<DataPackMaterial>,
        val applicationUrl: String? = null
    ) : DisDatapackItem

    data class FacilityCatalog(
        override val id: String,
        override val title: String,
        override val summary: String? = null,
        override val provenance: DataPackProvenanceOverride? = null,
        val facilityType: String,
        val placeRef: String,
        val capabilities: List<String> = emptyList(),
        val openingHours: String? = null,
        val accessNotes: String? = null
    ) : DisDatapackItem

    data class IndoorGraph(
        override val id: String,
        override val title: String,
        override val summary: String? = null,
        override val provenance: DataPackProvenanceOverride? = null,
        val itemType: IndoorGraphItemType,
        val placeRef: String? = null,
        val levelRef: String? = null,
        val nodeKind: IndoorGraphNodeKind? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val fromNodeId: String? = null,
        val toNodeId: String? = null,
        val distanceMeters: Double? = null,
        val accessible: Boolean? = null
    ) : DisDatapackItem

    data class PeriodPreset(
        override val id: String,
        override val title: String,
        override val summary: String? = null,
        override val provenance: DataPackProvenanceOverride? = null,
        val periods: List<PeriodPresetEntry>
    ) : DisDatapackItem

    data class PharmacyCatalog(
        override val id: String,
        override val title: String,
        override val summary: String? = null,
        override val provenance: DataPackProvenanceOverride? = null,
        val category: String,
        val specification: String? = null,
        val usageNote: String? = null,
        val stockState: PharmacyStockState = PharmacyStockState.UNKNOWN
    ) : DisDatapackItem
}

data class DisDatapack(
    val packId: String,
    val schoolId: String,
    val kind: DisDatapackKind,
    val version: String,
    val sources: List<DataPackSource>,
    val verifiedAt: String,
    val reviewBy: String,
    val maintainers: List<String>,
    val verificationLevel: VerificationLevel,
    val items: List<DisDatapackItem>
) {
    val format: String get() = DIS_DATAPACK_FORMAT
}
