package cn.edu.haut.campus.core.model

enum class MomentsPostType(val wireName: String, val label: String, val badgeLabel: String) {
    LOST("LOST", "寻物", "寻物"),
    FOUND("FOUND", "招领", "招领");

    companion object {
        fun fromWireName(raw: String?): MomentsPostType {
            return if (raw?.trim().equals("FOUND", ignoreCase = true) || raw?.trim() == "失物招领" || raw?.trim() == "招领") {
                FOUND
            } else {
                LOST
            }
        }
    }
}

enum class MomentsItemCategory(val label: String) {
    CAMPUS_CARD("证件"),
    DIGITAL_EARBUDS("数码"),
    CUP_UMBRELLA("水杯雨伞"),
    BOOK_STATIONERY("文具书籍"),
    KEYS_ACCESSORY("钥匙配饰"),
    CLOTHING_BAG("衣物包袋"),
    OTHER("其他");

    companion object {
        fun fromLabel(raw: String?): MomentsItemCategory {
            val cleaned = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.label == cleaned || it.name.equals(cleaned, ignoreCase = true)
            } ?: OTHER
        }
    }
}

data class MomentsMatchedClue(
    val id: String,
    val type: MomentsPostType,
    val title: String,
    val location: String,
    val contact: String,
    val matchScore: Int
)

data class MomentsPostComment(
    val id: String,
    val authorName: String,
    val content: String,
    val contactHint: String = "",
    val createdAt: String = ""
)

const val MASKED_ON_REQUEST_MOMENTS_CONTACT = "需发起联系/认领申请后查看"

data class MomentsPostItem(
    val id: String,
    val type: MomentsPostType,
    val title: String,
    val category: String,
    val campus: String = "莲花街校区",
    val location: String,
    val description: String,
    val contact: String,
    val rewardNote: String = "",
    val status: String = "OPEN",
    val authorName: String = "",
    val authorStudentNoMasked: String = "",
    val likesCount: Int = 0,
    val favoritesCount: Int = 0,
    val comments: List<MomentsPostComment> = emptyList(),
    val matchedClues: List<MomentsMatchedClue> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val contactVisibility: String = "PUBLIC",
    val hasProtectedContact: Boolean = false,
    val isOwner: Boolean = false,
    val canManage: Boolean = false,
    val isLegacyUnowned: Boolean = false,
    val canClaimLegacy: Boolean = false,
    val viewerRole: String = "GUEST"
) {
    val isResolved: Boolean
        get() = status.equals("RESOLVED", ignoreCase = true)

    val isContactRedactedOrProtectedPlaceholder: Boolean
        get() = hasProtectedContact ||
            contact.isBlank() ||
            contact == MASKED_ON_REQUEST_MOMENTS_CONTACT ||
            contact == "需申请查看" ||
            (contactVisibility.equals("ON_REQUEST", ignoreCase = true) &&
                (contact.contains("需发起") || contact.contains("需申请")))

    val canViewerReadRealContact: Boolean
        get() = contact.isNotBlank() && !isContactRedactedOrProtectedPlaceholder
}

data class MomentsTextbookListing(
    val id: String,
    val title: String,
    val isbn: String = "",
    val authorPublisher: String = "",
    val courseName: String = "",
    val majorTrack: CampusMajorTrack = CampusMajorTrack.ALL_MAJORS,
    val campus: String = "莲花街校区",
    val conditionLabel: String = "九成新",
    val priceYuan: Int = 15,
    val originalPriceYuan: Int = 55,
    val deliveryMode: String = "自提",
    val contact: String,
    val sellerName: String = "",
    val status: String = "AVAILABLE",
    val likesCount: Int = 0,
    val createdAt: String = "",
    val isOwner: Boolean = false,
    val canManage: Boolean = false
) {
    val isSold: Boolean
        get() = status.equals("SOLD", ignoreCase = true)
}

data class MomentsIsbnCatalogEntry(
    val isbn: String,
    val title: String,
    val authorPublisher: String,
    val courseName: String,
    val majorTrack: CampusMajorTrack = CampusMajorTrack.ALL_MAJORS,
    val originalPriceYuan: Int = 50
)

data class AntiLostTagReport(
    val id: String,
    val finderName: String = "",
    val foundLocation: String,
    val finderContact: String = "",
    val message: String = "",
    val createdAt: String = ""
)

data class AntiLostTagItem(
    val id: String,
    val tagCode: String,
    val itemName: String,
    val category: String = "随身物品",
    val campus: String = "莲花街校区",
    val ownerName: String = "",
    val ownerContact: String = "",
    val returnNote: String = "",
    val status: String = "NORMAL",
    val reports: List<AntiLostTagReport> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val isOwner: Boolean = false,
    val canManage: Boolean = false
) {
    val isLostOrReported: Boolean
        get() = status.equals("LOST", ignoreCase = true) || status.equals("FOUND_REPORTED", ignoreCase = true)

    fun claimWebUrl(cloudBaseUrl: String = NetworkProxyConfig.DEFAULT_CLOUD_API_BASE_URL): String {
        val base = NetworkProxyConfig.normalizeCloudApiBaseUrl(cloudBaseUrl).trimEnd('/')
        return "$base/claim/${tagCode.trim().uppercase()}"
    }

    fun generateVisualMatrix(gridSize: Int = 21): Array<BooleanArray> {
        val n = gridSize.coerceIn(15, 29)
        val matrix = Array(n) { BooleanArray(n) }

        fun drawFinderPattern(topR: Int, leftC: Int) {
            for (dr in 0..6) {
                for (dc in 0..6) {
                    val r = topR + dr
                    val c = leftC + dc
                    if (r !in 0 until n || c !in 0 until n) continue
                    val onOuterRing = dr == 0 || dr == 6 || dc == 0 || dc == 6
                    val onInnerCore = dr in 2..4 && dc in 2..4
                    matrix[r][c] = onOuterRing || onInnerCore
                }
            }
        }

        drawFinderPattern(0, 0)
        drawFinderPattern(0, n - 7)
        drawFinderPattern(n - 7, 0)

        for (i in 8 until n - 8) {
            matrix[6][i] = (i % 2 == 0)
            matrix[i][6] = (i % 2 == 0)
        }

        val seedText = "${tagCode.uppercase()}|$itemName|$ownerContact"
        var hashAcc = 2166136261L
        for (ch in seedText) {
            hashAcc = (hashAcc xor ch.code.toLong()) * 16777619L
        }

        for (r in 0 until n) {
            for (c in 0 until n) {
                val inTopLeft = r <= 7 && c <= 7
                val inTopRight = r <= 7 && c >= n - 8
                val inBottomLeft = r >= n - 8 && c <= 7
                val onTiming = r == 6 || c == 6
                if (inTopLeft || inTopRight || inBottomLeft || onTiming) continue
                val cellSeed = hashAcc xor ((r * 31L + c * 131L + seedText[(r + c) % seedText.length].code) * 0x9E3779B97F4A7C15UL.toLong())
                matrix[r][c] = ((cellSeed ushr ((r + c) and 15)) and 1L) == 1L
            }
        }
        return matrix
    }
}

data class CampusMomentsSnapshot(
    val updatedAt: String = "",
    val posts: List<MomentsPostItem> = emptyList(),
    val bookListings: List<MomentsTextbookListing> = emptyList(),
    val antiLostTags: List<AntiLostTagItem> = emptyList(),
    val isbnCatalog: List<MomentsIsbnCatalogEntry> = emptyList(),
    val favoritePostIds: Set<String> = emptySet(),
    val favoriteBookIds: Set<String> = emptySet(),
    val myPostIds: Set<String> = emptySet(),
    val myBookIds: Set<String> = emptySet(),
    val myTagCodes: Set<String> = emptySet()
)

object PresetCampusMomentsCatalog {
    val CAMPUS_OPTIONS = listOf("莲花街校区", "嵩山路校区", "中原路校区")
    val QUICK_LOCATIONS = listOf(
        "图书馆",
        "一餐厅",
        "二餐厅",
        "三餐厅",
        "1号楼",
        "2号楼",
        "8号楼",
        "北苑驿站",
        "操场"
    )
    val BOOK_CONDITIONS = listOf("全新", "九成新", "含笔记", "八成新")
    val BOOK_DELIVERY_MODES = listOf("校内自提", "宿舍交接", "三校区可议")

    val defaultPosts: List<MomentsPostItem> = emptyList()
    val defaultBookListings: List<MomentsTextbookListing> = emptyList()
    val defaultAntiLostTags: List<AntiLostTagItem> = emptyList()
    val defaultIsbnCatalog: List<MomentsIsbnCatalogEntry> = emptyList()

    fun computeLocalMatches(target: MomentsPostItem, allPosts: List<MomentsPostItem>): List<MomentsMatchedClue> {
        if (target.matchedClues.isNotEmpty()) return target.matchedClues
        val opposite = if (target.type == MomentsPostType.LOST) MomentsPostType.FOUND else MomentsPostType.LOST
        val tokens = "${target.title} ${target.description} ${target.location}"
            .lowercase()
            .split(Regex("[\\s,，。！!？?、·/]+"))
            .filter { it.length >= 2 }

        return allPosts
            .filter { it.id != target.id && it.type == opposite && !it.isResolved }
            .mapNotNull { candidate ->
                var score = 0
                if (candidate.category == target.category) score += 45
                if (candidate.campus == target.campus) score += 25
                val hay = "${candidate.title} ${candidate.description} ${candidate.location}".lowercase()
                for (tk in tokens) {
                    if (hay.contains(tk)) score += 12
                }
                if (score >= 45) {
                    MomentsMatchedClue(
                        id = candidate.id,
                        type = candidate.type,
                        title = candidate.title,
                        location = candidate.location,
                        contact = candidate.contact,
                        matchScore = score.coerceAtMost(98)
                    )
                } else {
                    null
                }
            }
            .sortedByDescending { it.matchScore }
            .take(3)
    }
}
