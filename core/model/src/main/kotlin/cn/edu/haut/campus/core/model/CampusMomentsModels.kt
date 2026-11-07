package cn.edu.haut.campus.core.model

enum class MomentsPostType(val wireName: String, val label: String, val badgeLabel: String) {
    LOST("LOST", "寻物启事", "寻物"),
    FOUND("FOUND", "失物招领", "招领");

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
    CAMPUS_CARD("校园卡/证件"),
    DIGITAL_EARBUDS("数码耳机"),
    CUP_UMBRELLA("水杯雨伞"),
    BOOK_STATIONERY("教材文具"),
    KEYS_ACCESSORY("钥匙饰品"),
    CLOTHING_BAG("衣物包袋"),
    OTHER("其他物品");

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
    val authorName: String = "河工大同学",
    val authorStudentNoMasked: String = "",
    val likesCount: Int = 0,
    val favoritesCount: Int = 0,
    val comments: List<MomentsPostComment> = emptyList(),
    val matchedClues: List<MomentsMatchedClue> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    val isResolved: Boolean
        get() = status.equals("RESOLVED", ignoreCase = true)
}

data class MomentsTextbookListing(
    val id: String,
    val title: String,
    val isbn: String = "",
    val authorPublisher: String = "高校教材",
    val courseName: String = "",
    val majorTrack: CampusMajorTrack = CampusMajorTrack.ALL_MAJORS,
    val campus: String = "莲花街校区",
    val conditionLabel: String = "九成新",
    val priceYuan: Int = 15,
    val originalPriceYuan: Int = 55,
    val deliveryMode: String = "莲花街自提",
    val contact: String,
    val sellerName: String = "河工大同学",
    val status: String = "AVAILABLE",
    val likesCount: Int = 0,
    val createdAt: String = ""
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
    val finderName: String = "热心同学",
    val foundLocation: String,
    val finderContact: String = "",
    val message: String = "",
    val createdAt: String = ""
)

data class AntiLostTagItem(
    val id: String,
    val tagCode: String,
    val itemName: String,
    val category: String = "水杯雨伞",
    val campus: String = "莲花街校区",
    val ownerName: String = "河工大同学",
    val ownerContact: String = "",
    val returnNote: String = "若拾到请联系失主或放至就近服务台，感谢相助",
    val status: String = "NORMAL",
    val reports: List<AntiLostTagReport> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    val isLostOrReported: Boolean
        get() = status.equals("LOST", ignoreCase = true) || status.equals("FOUND_REPORTED", ignoreCase = true)

    fun claimWebUrl(cloudBaseUrl: String = NetworkProxyConfig.DEFAULT_CLOUD_API_BASE_URL): String {
        val base = NetworkProxyConfig.normalizeCloudApiBaseUrl(cloudBaseUrl).trimEnd('/')
        return "$base/claim/${tagCode.trim().uppercase()}"
    }

    /**
     * 生成 21x21 标准定位图案 + 数据散列矩阵，供客户端原生绘制防丢码标签卡片。
     */
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
    val posts: List<MomentsPostItem> = PresetCampusMomentsCatalog.defaultPosts,
    val bookListings: List<MomentsTextbookListing> = PresetCampusMomentsCatalog.defaultBookListings,
    val antiLostTags: List<AntiLostTagItem> = PresetCampusMomentsCatalog.defaultAntiLostTags,
    val isbnCatalog: List<MomentsIsbnCatalogEntry> = PresetCampusMomentsCatalog.defaultIsbnCatalog,
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
        "钟楼广场",
        "一餐厅",
        "二餐厅",
        "三餐厅",
        "1号教学楼",
        "2号教学楼",
        "8号教学楼",
        "北苑菜鸟驿站",
        "东操场"
    )
    val BOOK_CONDITIONS = listOf(
        "全新未拆",
        "九成新·无划线",
        "九成新·含期末重点笔记",
        "八成新·附习题解答"
    )
    val BOOK_DELIVERY_MODES = listOf(
        "莲花街北苑/钟楼自提",
        "宿舍楼下当面交接",
        "图书馆一楼大厅自提",
        "三校区均可协商"
    )

    val defaultPosts: List<MomentsPostItem> = listOf(
        MomentsPostItem(
            id = "mp_found_card_01",
            type = MomentsPostType.FOUND,
            title = "拾到校园一卡通一张（信息学院 23 级）",
            category = MomentsItemCategory.CAMPUS_CARD.label,
            campus = "莲花街校区",
            location = "图书馆三层西书库北区自习桌",
            description = "在三层西书库北区 06 桌附近捡到一张校园卡，卡面姓名为张*宇，已放在三楼服务台旁书架，也可联系我直接拿。",
            contact = "QQ: 2418903312",
            rewardNote = "凭学号尾号认领",
            status = "OPEN",
            authorName = "林同学",
            authorStudentNoMasked = "23***06",
            likesCount = 6,
            favoritesCount = 2,
            comments = listOf(
                MomentsPostComment(
                    id = "mc_01",
                    authorName = "张同学",
                    content = "同班同学，已转告失主联系你，十分感谢！",
                    createdAt = "今天 10:15"
                )
            ),
            createdAt = "今天 09:40"
        ),
        MomentsPostItem(
            id = "mp_lost_airpods_02",
            type = MomentsPostType.LOST,
            title = "寻 AirPods Pro 2 充电仓（带浅灰硅胶套）",
            category = MomentsItemCategory.DIGITAL_EARBUDS.label,
            campus = "莲花街校区",
            location = "1号教学楼 1204 教室至二餐厅沿线",
            description = "上午第 3-4 节下课后从 1 号楼走到二餐厅二楼就餐，途中遗失 AirPods Pro 2 充电仓（内无耳机），套有浅灰色保护壳。",
            contact = "微信: haut_chen23",
            rewardNote = "请喝咖啡或奶茶酬谢",
            status = "OPEN",
            authorName = "陈同学",
            authorStudentNoMasked = "23***18",
            likesCount = 9,
            favoritesCount = 4,
            createdAt = "今天 12:20"
        ),
        MomentsPostItem(
            id = "mp_found_keys_03",
            type = MomentsPostType.FOUND,
            title = "二餐厅一楼拾到宿舍钥匙串（挂小熊挂件）",
            category = MomentsItemCategory.KEYS_ACCESSORY.label,
            campus = "莲花街校区",
            location = "二餐厅一楼黄焖鸡窗口旁餐桌",
            description = "共两把钥匙加一枚蓝色门禁扣，挂有棕色小熊钥匙扣，目前暂存本人处，失主请加 QQ 联系。",
            contact = "QQ: 1908273645",
            rewardNote = "描述挂件细节即可认领",
            status = "OPEN",
            authorName = "王同学",
            authorStudentNoMasked = "24***11",
            likesCount = 5,
            favoritesCount = 1,
            createdAt = "今天 13:05"
        ),
        MomentsPostItem(
            id = "mp_lost_cup_04",
            type = MomentsPostType.LOST,
            title = "寻象印白色保温杯（杯底贴有防丢码）",
            category = MomentsItemCategory.CUP_UMBRELLA.label,
            campus = "嵩山路校区",
            location = "嵩山路校区 3 号楼阶梯教室",
            description = "480ml 白色保温杯，杯身有轻微磕碰，杯底贴有防丢码标签 HAUT-TAG-7E2A9B。",
            contact = "QQ: 310948271",
            rewardNote = "感谢好心人归还",
            status = "OPEN",
            authorName = "孙同学",
            authorStudentNoMasked = "23***28",
            likesCount = 4,
            favoritesCount = 1,
            createdAt = "昨天 19:30"
        )
    )

    val defaultBookListings: List<MomentsTextbookListing> = listOf(
        MomentsTextbookListing(
            id = "mb_math_01",
            title = "高等数学（同济第八版 上+下册）含配套习题全解",
            isbn = "9787040589818",
            authorPublisher = "同济大学数学科学学院 · 高等教育出版社",
            courseName = "高等数学A",
            majorTrack = CampusMajorTrack.ALL_MAJORS,
            campus = "莲花街校区",
            conditionLabel = "九成新·含期末重点笔记",
            priceYuan = 18,
            originalPriceYuan = 89,
            deliveryMode = "莲花街北苑/钟楼自提",
            contact = "QQ: 2310406002",
            sellerName = "黄同学",
            status = "AVAILABLE",
            likesCount = 12,
            createdAt = "今天 11:00"
        ),
        MomentsTextbookListing(
            id = "mb_ds_02",
            title = "数据结构（C语言版 第2版）严蔚敏 + 算法笔记",
            isbn = "9787115379504",
            authorPublisher = "严蔚敏 · 人民邮电出版社",
            courseName = "数据结构",
            majorTrack = CampusMajorTrack.CS_AI_SOFTWARE,
            campus = "莲花街校区",
            conditionLabel = "九成新·无划线",
            priceYuan = 15,
            originalPriceYuan = 49,
            deliveryMode = "8号楼或宿舍楼下当面交接",
            contact = "微信: cs_book_haut",
            sellerName = "李同学",
            status = "AVAILABLE",
            likesCount = 8,
            createdAt = "今天 14:20"
        ),
        MomentsTextbookListing(
            id = "mb_circuit_03",
            title = "电路（第6版）邱关源 + 历年期末复习题",
            isbn = "9787040565560",
            authorPublisher = "邱关源 / 罗先觉 · 高等教育出版社",
            courseName = "电路原理",
            majorTrack = CampusMajorTrack.EE_AUTOMATION_IOT,
            campus = "莲花街校区",
            conditionLabel = "九成新·含期末重点笔记",
            priceYuan = 16,
            originalPriceYuan = 65,
            deliveryMode = "图书馆一楼大厅自提",
            contact = "QQ: 178293041",
            sellerName = "周同学",
            status = "AVAILABLE",
            likesCount = 7,
            createdAt = "昨天 16:45"
        ),
        MomentsTextbookListing(
            id = "mb_food_04",
            title = "谷物加工工艺学 + 生物化学复习讲义",
            isbn = "9787518432103",
            authorPublisher = "中国轻工业出版社",
            courseName = "谷物加工工艺学",
            majorTrack = CampusMajorTrack.FOOD_BIO_CHEM_MAT,
            campus = "莲花街校区",
            conditionLabel = "全新未拆",
            priceYuan = 20,
            originalPriceYuan = 78,
            deliveryMode = "莲花街北苑/钟楼自提",
            contact = "QQ: 904817263",
            sellerName = "赵同学",
            status = "AVAILABLE",
            likesCount = 10,
            createdAt = "昨天 20:10"
        )
    )

    val defaultAntiLostTags: List<AntiLostTagItem> = listOf(
        AntiLostTagItem(
            id = "tag_default_01",
            tagCode = "HAUT-TAG-7E2A9B",
            itemName = "象印白色保温杯 & 校园卡套",
            category = MomentsItemCategory.CUP_UMBRELLA.label,
            campus = "莲花街校区",
            ownerName = "孙同学",
            ownerContact = "QQ: 310948271",
            returnNote = "若拾到请放至图书馆一楼服务台或通过防丢码留言告知位置，万分感谢！",
            status = "NORMAL",
            createdAt = "2026-11-07"
        )
    )

    val defaultIsbnCatalog: List<MomentsIsbnCatalogEntry> = listOf(
        MomentsIsbnCatalogEntry(
            isbn = "9787040589818",
            title = "高等数学（第八版 上册）",
            authorPublisher = "同济大学数学科学学院 · 高等教育出版社",
            courseName = "高等数学A",
            majorTrack = CampusMajorTrack.ALL_MAJORS,
            originalPriceYuan = 49
        ),
        MomentsIsbnCatalogEntry(
            isbn = "9787040599053",
            title = "工程数学 线性代数（第七版）",
            authorPublisher = "同济大学数学科学学院 · 高等教育出版社",
            courseName = "线性代数",
            majorTrack = CampusMajorTrack.ALL_MAJORS,
            originalPriceYuan = 36
        ),
        MomentsIsbnCatalogEntry(
            isbn = "9787115379504",
            title = "数据结构（C语言版 第2版）",
            authorPublisher = "严蔚敏 · 人民邮电出版社",
            courseName = "数据结构",
            majorTrack = CampusMajorTrack.CS_AI_SOFTWARE,
            originalPriceYuan = 49
        ),
        MomentsIsbnCatalogEntry(
            isbn = "9787040565560",
            title = "电路（第6版）",
            authorPublisher = "邱关源 / 罗先觉 · 高等教育出版社",
            courseName = "电路原理",
            majorTrack = CampusMajorTrack.EE_AUTOMATION_IOT,
            originalPriceYuan = 65
        ),
        MomentsIsbnCatalogEntry(
            isbn = "9787040512113",
            title = "概率论与数理统计（第五版）",
            authorPublisher = "浙大盛骤 · 高等教育出版社",
            courseName = "概率论与数理统计",
            majorTrack = CampusMajorTrack.ALL_MAJORS,
            originalPriceYuan = 45
        )
    )

    /**
     * 为寻物/招领帖计算同校区、同类别或关键词重合的疑似匹配线索。
     */
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
