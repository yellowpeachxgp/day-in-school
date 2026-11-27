package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Moments 帖子、评论、联系请求、教材、防丢码、云端快照与本地 viewer 集合。
 * 实现从 CampusRepository 兼容门面逐字迁入；云身份与网络地址解析仍经运行时端口保持原路径。
 */
class MomentsRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val runtimePort: MomentsRuntimePort
) {
    private val preferences = environment.preferences
    private val preferencesFlow get() = runtimePort.preferencesFlow
    private val studentProfileFlow get() = runtimePort.studentProfileFlow
    private val cloudHttpTransportForTest get() = runtimePort.cloudHttpTransportForTest()

    private suspend fun resolveValidCloudAccessToken(forceRefresh: Boolean): String =
        runtimePort.resolveValidCloudAccessToken(forceRefresh)

    /**
     * 刷新校园互助广场（失物招领、二手教材、随身防丢码）云端快照并与本地草稿/发布记录合并。
     */
    suspend fun refreshCampusMomentsSnapshot(): Result<cn.edu.haut.campus.core.model.CampusMomentsSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val currentPrefs = preferencesFlow.first()
                val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    currentPrefs.networkProxyConfig.customCloudApiBaseUrl
                )
                val token = resolveValidCloudAccessToken(forceRefresh = false)
                val (remoteSnap, _) = CampusCloudOidcHelper.fetchMomentsSnapshot(
                    cloudBaseUrl = baseUrl,
                    bearerToken = token.takeIf { it.isNotBlank() }
                ) ?: (currentPrefs.campusMomentsSnapshot to "")
                val localSnap = currentPrefs.campusMomentsSnapshot
                val hasLiveRemote = token.isNotBlank() && remoteSnap.updatedAt.isNotBlank()
                val mergedPosts = if (hasLiveRemote) {
                    remoteSnap.posts
                } else {
                    remoteSnap.posts + localSnap.posts.filter { lp -> remoteSnap.posts.none { it.id == lp.id } }
                }
                val mergedBooks = (remoteSnap.bookListings + localSnap.bookListings.filter { lb -> remoteSnap.bookListings.none { it.id == lb.id } })
                val mergedTags = (remoteSnap.antiLostTags + localSnap.antiLostTags.filter { lt -> remoteSnap.antiLostTags.none { it.tagCode.equals(lt.tagCode, ignoreCase = true) } })
                val serverOwnerIds = mergedPosts.filter { it.isOwner }.map { it.id }.toSet()
                val effectiveMyPostIds = if (token.isNotBlank() && serverOwnerIds.isNotEmpty()) {
                    serverOwnerIds
                } else {
                    localSnap.myPostIds + serverOwnerIds
                }
                val rawFinalSnap = remoteSnap.copy(
                    posts = mergedPosts,
                    bookListings = mergedBooks,
                    antiLostTags = mergedTags,
                    favoritePostIds = localSnap.favoritePostIds,
                    favoriteBookIds = localSnap.favoriteBookIds,
                    myPostIds = if (token.isNotBlank()) effectiveMyPostIds else emptySet(),
                    myBookIds = localSnap.myBookIds,
                    myTagCodes = localSnap.myTagCodes
                )
                val finalSnap = if (token.isNotBlank()) {
                    rawFinalSnap
                } else {
                    CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(rawFinalSnap)
                }
                preferences.edit { prefs ->
                    prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(finalSnap)
                    if (token.isNotBlank()) {
                        prefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(effectiveMyPostIds)
                    } else {
                        prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS)
                    }
                }
                finalSnap
            }
        }

    suspend fun publishMomentsPost(
        type: cn.edu.haut.campus.core.model.MomentsPostType,
        title: String,
        category: String,
        campus: String,
        location: String,
        description: String,
        contact: String,
        rewardNote: String = "",
        contactVisibility: String = "PUBLIC"
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTitle = title.trim().take(64)
            require(cleanTitle.isNotEmpty()) { "请填写物品名称或标题" }
            val cleanContact = contact.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写联系方式" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("发布寻物或招领信息需先在「我的 → 云端账号」中登录账号，以便后续跨设备结案、删除与审批联系申请")
            }
            val profile = studentProfileFlow.first()
            val prefsState = preferencesFlow.first()
            val authorName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val studentNo = profile?.schoolId ?: prefsState.savedStudentId.orEmpty()
            val maskedNo = if (studentNo.length >= 4) "${studentNo.take(2)}***${studentNo.takeLast(2)}" else ""
            val postId = "mp_${UUID.randomUUID().toString().replace("-", "").take(10)}"
            val normalizedVisibility = if (contactVisibility.equals("ON_REQUEST", ignoreCase = true)) "ON_REQUEST" else "PUBLIC"
            val localItem = cn.edu.haut.campus.core.model.MomentsPostItem(
                id = postId,
                type = type,
                title = cleanTitle,
                category = category.trim().ifBlank { "其他物品" },
                campus = campus.trim().ifBlank { "莲花街校区" },
                location = location.trim().ifBlank { "校内" }.take(64),
                description = description.trim().take(360),
                contact = cleanContact,
                rewardNote = rewardNote.trim().take(64),
                status = "OPEN",
                authorName = authorName,
                authorStudentNoMasked = maskedNo,
                createdAt = "刚刚",
                contactVisibility = normalizedVisibility,
                isOwner = true,
                canManage = true,
                isLegacyUnowned = false,
                viewerRole = "OWNER"
            )
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val cloudSaved = CampusCloudOidcHelper.publishMomentsPostToCloud(
                post = localItem,
                cloudBaseUrl = baseUrl,
                bearerToken = token,
                contactVisibility = normalizedVisibility
            ) ?: throw IllegalStateException("网络未连接或云端认证已失效，失物招领发布未能提交")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = listOf(cloudSaved) + curSnap.posts.filterNot { it.id == cloudSaved.id }
            val nextMyIds = curSnap.myPostIds + cloudSaved.id
            val nextSnap = curSnap.copy(posts = nextPosts, myPostIds = nextMyIds)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(nextMyIds)
            }
            cloudSaved
        }
    }

    suspend fun addMomentsComment(
        postId: String,
        content: String,
        contactHint: String = "",
        clientMessageId: String? = null
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanContent = content.trim().take(240)
            require(cleanContent.isNotEmpty()) { "请输入线索或留言内容" }
            val profile = studentProfileFlow.first()
            val authorName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "热心同学"
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            val cloudUpdated = CampusCloudOidcHelper.addMomentsCommentToCloud(
                postId = postId,
                authorName = authorName,
                content = cleanContent,
                contactHint = contactHint.trim().take(48),
                clientMessageId = clientMessageId,
                cloudBaseUrl = baseUrl,
                bearerToken = token.takeIf { it.isNotBlank() },
                httpTransport = cloudHttpTransportForTest
            ) ?: throw IllegalStateException("网络未连接或服务端拒绝（如已被发起人屏蔽），留言未能提交，请联网重试")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.map { if (it.id == postId) cloudUpdated else it }
            val nextSnap = curSnap.copy(posts = nextPosts)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
            cloudUpdated
        }
    }

    suspend fun submitMomentsContactRequest(
        postId: String,
        message: String,
        contactInfo: String,
        clientMessageId: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanMsg = message.trim().take(240)
            require(cleanMsg.isNotEmpty()) { "请填写认领说明或物品特征" }
            val cleanContact = contactInfo.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写您的联系方式（仅发起人可见）" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("发送私密联系申请需先在「我的 → 云端账号」中登录账号；未登录时仅可查看公开联系方式或发表公开线索")
            }
            val profile = studentProfileFlow.first()
            val senderName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            // 幂等键绑定"用户这一次发送"的 clientMessageId（失败重试沿用，新的发送换新键）；
            // 绝不用正文/联系方式 hash 兜底——两条有意相同的留言必须都能送达
            val idempotencyKey = "mcr_${postId}_${clientMessageId?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: UUID.randomUUID().toString().replace("-", "")}"
            val dto = CampusCloudOidcHelper.submitMomentsContactRequestToCloud(
                postId = postId,
                senderName = senderName,
                message = cleanMsg,
                contactInfo = cleanContact,
                idempotencyKey = idempotencyKey,
                bearerToken = token,
                cloudBaseUrl = baseUrl,
                httpTransport = cloudHttpTransportForTest
            ) ?: throw IllegalStateException("联系申请提交失败：可能网络未连接、无法向本人发起申请、该帖已结案或发起人已屏蔽申请")
            runCatching { refreshCampusMomentsSnapshot() }
            dto
        }
    }

    suspend fun fetchMomentsContactRequestsResult(
        postId: String
    ): CampusCloudOidcHelper.MomentsContactRequestsListResult = withContext(Dispatchers.IO) {
        val token = resolveValidCloudAccessToken(forceRefresh = false)
        if (token.isBlank()) return@withContext CampusCloudOidcHelper.MomentsContactRequestsListResult()
        val prefsState = preferencesFlow.first()
        val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
            prefsState.networkProxyConfig.customCloudApiBaseUrl
        )
        CampusCloudOidcHelper.fetchMomentsContactRequestsResultFromCloud(
            postId = postId,
            bearerToken = token,
            cloudBaseUrl = baseUrl,
            httpTransport = cloudHttpTransportForTest
        )
    }

    suspend fun fetchMomentsContactRequests(
        postId: String
    ): List<CampusCloudOidcHelper.MomentsContactRequestDto> =
        fetchMomentsContactRequestsResult(postId).requests

    suspend fun claimLegacyMomentsPostOwnership(
        postId: String,
        claimToken: String
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanClaimToken = claimToken.trim()
            require(cleanClaimToken.length >= 32) { "仅支持使用发帖时签发的原始高熵所有权凭证（32位以上）认领；无凭证历史旧帖请申请管理员人工核验" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("认领历史旧帖管理权需先登录云端账号")
            }
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val claimedPost = CampusCloudOidcHelper.claimLegacyMomentsPostOnCloud(
                postId = postId,
                claimToken = cleanClaimToken,
                bearerToken = token,
                cloudBaseUrl = baseUrl
            ) ?: throw IllegalStateException("高熵所有权凭证核验未通过；为防冒领已关闭联系方式/脱敏学号自助认领，请提交管理员人工核验")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.map { if (it.id == postId) claimedPost else it }
            val nextMyIds = curSnap.myPostIds + claimedPost.id
            val nextSnap = curSnap.copy(posts = nextPosts, myPostIds = nextMyIds)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(nextMyIds)
            }
            claimedPost
        }
    }

    suspend fun handleMomentsContactRequestAction(
        postId: String,
        requestId: String,
        action: String,
        replyMessage: String? = null,
        reason: String? = null,
        clientMessageId: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            require(token.isNotBlank()) { "请先登录云端账号后再处理联系申请" }
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val dto = CampusCloudOidcHelper.handleMomentsContactRequestActionOnCloud(
                postId = postId,
                requestId = requestId,
                action = action,
                replyMessage = replyMessage,
                reason = reason,
                clientMessageId = clientMessageId,
                bearerToken = token,
                cloudBaseUrl = baseUrl,
                httpTransport = cloudHttpTransportForTest
            ) ?: throw IllegalStateException("处理联系申请失败，请检查网络连接或权限")
            runCatching { refreshCampusMomentsSnapshot() }
            dto
        }
    }

    suspend fun reportMomentsPost(
        postId: String,
        reason: String = "用户举报不良或虚假失物招领信息"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            val ok = CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = "REPORT",
                cloudBaseUrl = baseUrl,
                bearerToken = token.takeIf { it.isNotBlank() },
                reason = reason
            )
            if (!ok) {
                throw IllegalStateException("举报未能提交至云端，请检查网络连接后重试")
            }
            true
        }
    }

    suspend fun toggleMomentsPostLike(postId: String) {
        val prefsState = preferencesFlow.first()
        val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
            prefsState.networkProxyConfig.customCloudApiBaseUrl
        )
        val token = resolveValidCloudAccessToken(forceRefresh = false)
        val ok = withContext(Dispatchers.IO) {
            CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = "LIKE",
                cloudBaseUrl = baseUrl,
                bearerToken = token.takeIf { it.isNotBlank() }
            )
        }
        if (ok) {
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.map {
                if (it.id == postId) it.copy(likesCount = it.likesCount + 1) else it
            }
            val nextSnap = curSnap.copy(posts = nextPosts)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
        }
    }

    suspend fun toggleMomentsPostFavorite(postId: String) {
        val prefsState = preferencesFlow.first()
        val curFavs = prefsState.campusMomentsSnapshot.favoritePostIds
        val nextFavs = if (postId in curFavs) curFavs - postId else curFavs + postId
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS] = encodeCsvSet(nextFavs)
        }
    }

    suspend fun toggleMomentsPostResolved(postId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsState = preferencesFlow.first()
            val curSnap = prefsState.campusMomentsSnapshot
            val target = curSnap.posts.firstOrNull { it.id == postId }
                ?: throw IllegalArgumentException("未找到该帖子")
            val nextStatus = if (target.isResolved) "OPEN" else "RESOLVED"
            val nextAction = if (nextStatus == "RESOLVED") "RESOLVE" else "REOPEN"
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("结案或重开帖子需先登录云端账号验证发起人身份")
            }
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = nextAction,
                cloudBaseUrl = baseUrl,
                bearerToken = token
            )
            if (!ok) {
                throw IllegalStateException("仅帖子发起人本人可在联网状态下结案或重开")
            }
            val nextPosts = curSnap.posts.map {
                if (it.id == postId) it.copy(status = nextStatus) else it
            }
            val nextSnap = curSnap.copy(posts = nextPosts)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
            true
        }
    }

    suspend fun deleteMomentsPost(postId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsState = preferencesFlow.first()
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("删除帖子需先登录云端账号验证发起人身份")
            }
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = "DELETE",
                cloudBaseUrl = baseUrl,
                bearerToken = token
            )
            if (!ok) {
                throw IllegalStateException("删除失败：仅帖子发起人本人可在联网状态下删除")
            }
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.filterNot { it.id == postId }
            val nextMyIds = curSnap.myPostIds - postId
            val nextSnap = curSnap.copy(posts = nextPosts, myPostIds = nextMyIds)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(nextMyIds)
            }
            true
        }
    }

    suspend fun publishMomentsBookListing(
        title: String,
        isbn: String,
        authorPublisher: String,
        courseName: String,
        majorTrack: cn.edu.haut.campus.core.model.CampusMajorTrack,
        campus: String,
        conditionLabel: String,
        priceYuan: Int,
        originalPriceYuan: Int,
        deliveryMode: String,
        contact: String
    ): Result<cn.edu.haut.campus.core.model.MomentsTextbookListing> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "请填写教材名称" }
            val cleanContact = contact.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写卖家联系方式" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("发布闲置教材需先在「我的 → 云端账号」登录账号，以便后续下架或删除管理")
            }
            val profile = studentProfileFlow.first()
            val sellerName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val bookId = "mb_${UUID.randomUUID().toString().replace("-", "").take(10)}"
            val localBook = cn.edu.haut.campus.core.model.MomentsTextbookListing(
                id = bookId,
                title = cleanTitle,
                isbn = isbn.trim().take(24),
                authorPublisher = authorPublisher.trim().ifBlank { "高等教育出版社" }.take(64),
                courseName = courseName.trim().take(40),
                majorTrack = majorTrack,
                campus = campus.trim().ifBlank { "莲花街校区" },
                conditionLabel = conditionLabel.trim().ifBlank { "九成新" },
                priceYuan = priceYuan.coerceIn(1, 500),
                originalPriceYuan = originalPriceYuan.coerceIn(priceYuan, 999),
                deliveryMode = deliveryMode.trim().ifBlank { "莲花街自提" },
                contact = cleanContact,
                sellerName = sellerName,
                status = "AVAILABLE",
                createdAt = "刚刚",
                isOwner = true,
                canManage = true
            )
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val cloudSaved = CampusCloudOidcHelper.publishMomentsBookToCloud(localBook, baseUrl, token)
                ?: throw IllegalStateException("网络未连接或云端认证已失效，闲置教材发布未能提交")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextBooks = listOf(cloudSaved) + curSnap.bookListings.filterNot { it.id == cloudSaved.id }
            val nextMyBookIds = curSnap.myBookIds + cloudSaved.id
            val nextSnap = curSnap.copy(bookListings = nextBooks, myBookIds = nextMyBookIds)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS] = encodeCsvSet(nextMyBookIds)
            }
            cloudSaved
        }
    }

    suspend fun toggleMomentsBookFavorite(bookId: String) {
        val prefsState = preferencesFlow.first()
        val curFavs = prefsState.campusMomentsSnapshot.favoriteBookIds
        val nextFavs = if (bookId in curFavs) curFavs - bookId else curFavs + bookId
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS] = encodeCsvSet(nextFavs)
        }
    }

    suspend fun toggleMomentsBookSold(bookId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) throw IllegalStateException("修改教材状态需先登录云端账号")
            val prefsState = preferencesFlow.first()
            val curSnap = prefsState.campusMomentsSnapshot
            val target = curSnap.bookListings.firstOrNull { it.id == bookId }
                ?: throw IllegalArgumentException("未找到该教材挂单")
            val nextStatus = if (target.isSold) "AVAILABLE" else "SOLD"
            val nextAction = if (nextStatus == "SOLD") "MARK_SOLD" else "MARK_AVAILABLE"
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsBookOnCloud(bookId, nextAction, baseUrl, token)
            if (!ok) throw IllegalStateException("更新失败：请检查网络连接或确认您是该教材发布人")
            val nextBooks = curSnap.bookListings.map {
                if (it.id == bookId) it.copy(status = nextStatus) else it
            }
            val nextSnap = curSnap.copy(bookListings = nextBooks)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
            true
        }
    }

    suspend fun deleteMomentsBookListing(bookId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) throw IllegalStateException("删除教材挂单需先登录云端账号")
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsBookOnCloud(bookId, "DELETE", baseUrl, token)
            if (!ok) throw IllegalStateException("删除失败：请检查网络连接或确认您是该教材发布人")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextBooks = curSnap.bookListings.filterNot { it.id == bookId }
            val nextMyBookIds = curSnap.myBookIds - bookId
            val nextSnap = curSnap.copy(bookListings = nextBooks, myBookIds = nextMyBookIds)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS] = encodeCsvSet(nextMyBookIds)
            }
            true
        }
    }

    suspend fun upsertAntiLostTag(
        tagCode: String = "",
        itemName: String,
        category: String,
        campus: String,
        ownerContact: String,
        returnNote: String,
        status: String = "NORMAL"
    ): Result<cn.edu.haut.campus.core.model.AntiLostTagItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = itemName.trim().take(48)
            require(cleanName.isNotEmpty()) { "请填写随身物品名称" }
            val cleanContact = ownerContact.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写失主联系方式" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("生成或更新随身防丢码需先在「我的 → 云端账号」登录账号，以绑定归属并接收拾得通知")
            }
            val profile = studentProfileFlow.first()
            val ownerName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val effectiveCode = tagCode.trim().uppercase().takeIf {
                it.matches(Regex("^HAUT-TAG-[A-Z0-9]{4,10}$"))
            } ?: "HAUT-TAG-${UUID.randomUUID().toString().replace("-", "").take(6).uppercase()}"
            val prefsState = preferencesFlow.first()
            val curSnap = prefsState.campusMomentsSnapshot
            val existing = curSnap.antiLostTags.firstOrNull { it.tagCode.equals(effectiveCode, ignoreCase = true) }
            val localTag = cn.edu.haut.campus.core.model.AntiLostTagItem(
                id = existing?.id ?: "tag_${effectiveCode.takeLast(6)}",
                tagCode = effectiveCode,
                itemName = cleanName,
                category = category.trim().ifBlank { "水杯雨伞" },
                campus = campus.trim().ifBlank { "莲花街校区" },
                ownerName = ownerName,
                ownerContact = cleanContact,
                returnNote = returnNote.trim().ifBlank { "拾到请联系失主或放至就近服务台，感谢相助" }.take(160),
                status = status.uppercase(),
                reports = existing?.reports.orEmpty(),
                createdAt = existing?.createdAt ?: "今天",
                isOwner = true,
                canManage = true
            )
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val cloudSaved = CampusCloudOidcHelper.upsertAntiLostTagOnCloud(localTag, baseUrl, token)
                ?: throw IllegalStateException("网络未连接或该防丢码编号已被占用，未能同步注册至云端")
            val nextTags = listOf(cloudSaved) + curSnap.antiLostTags.filterNot {
                it.tagCode.equals(cloudSaved.tagCode, ignoreCase = true)
            }
            val nextMyCodes = curSnap.myTagCodes + cloudSaved.tagCode
            val nextSnap = curSnap.copy(antiLostTags = nextTags, myTagCodes = nextMyCodes)
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES] = encodeCsvSet(nextMyCodes)
            }
            cloudSaved
        }
    }

    suspend fun reportAntiLostTagFound(
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCode = tagCode.trim().uppercase()
            require(cleanCode.isNotEmpty()) { "请输入防丢码编号（如 HAUT-TAG-7E2A9B）" }
            val cleanLoc = foundLocation.trim().take(80)
            require(cleanLoc.isNotEmpty()) { "请填写拾得或放置地点" }
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val submitted = CampusCloudOidcHelper.reportAntiLostTagFoundOnCloud(
                tagCode = cleanCode,
                finderName = finderName.trim().ifBlank { "热心同学" },
                foundLocation = cleanLoc,
                finderContact = finderContact.trim().take(64),
                message = message.trim().take(200),
                cloudBaseUrl = baseUrl
            )
            if (!submitted) {
                throw IllegalStateException("拾得线索未能提交：请检查网络连接或确认防丢码编号已在云端有效登记")
            }
            true
        }
    }

}
