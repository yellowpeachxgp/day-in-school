package cn.edu.haut.campus.feature.settings.moments.state

import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.model.AntiLostTagItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.CampusMomentsSnapshot
import cn.edu.haut.campus.core.model.MomentsPostItem
import cn.edu.haut.campus.core.model.MomentsPostType
import cn.edu.haut.campus.core.model.MomentsTextbookListing

/** Moments 用户动作面；粒度与拆分前 Repository 调用一致，确保纯搬移。 */
interface CampusMomentsUiAction {
    suspend fun refreshCampusMomentsSnapshot(): Result<CampusMomentsSnapshot>
    suspend fun publishMomentsPost(
        type: MomentsPostType,
        title: String,
        category: String,
        campus: String,
        location: String,
        description: String,
        contact: String,
        rewardNote: String = "",
        contactVisibility: String = "PUBLIC"
    ): Result<MomentsPostItem>
    suspend fun addMomentsComment(
        postId: String,
        content: String,
        contactHint: String = "",
        clientMessageId: String? = null
    ): Result<MomentsPostItem>
    suspend fun submitMomentsContactRequest(
        postId: String,
        message: String,
        contactInfo: String,
        clientMessageId: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto>
    suspend fun fetchMomentsContactRequestsResult(
        postId: String
    ): CampusCloudOidcHelper.MomentsContactRequestsListResult
    suspend fun claimLegacyMomentsPostOwnership(
        postId: String,
        claimToken: String
    ): Result<MomentsPostItem>
    suspend fun handleMomentsContactRequestAction(
        postId: String,
        requestId: String,
        action: String,
        replyMessage: String? = null,
        reason: String? = null,
        clientMessageId: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto>
    suspend fun reportMomentsPost(
        postId: String,
        reason: String = "用户举报不良或虚假失物招领信息"
    ): Result<Boolean>
    suspend fun toggleMomentsPostLike(postId: String)
    suspend fun toggleMomentsPostFavorite(postId: String)
    suspend fun toggleMomentsPostResolved(postId: String): Result<Boolean>
    suspend fun deleteMomentsPost(postId: String): Result<Boolean>
    suspend fun publishMomentsBookListing(
        title: String,
        isbn: String,
        authorPublisher: String,
        courseName: String,
        majorTrack: CampusMajorTrack,
        campus: String,
        conditionLabel: String,
        priceYuan: Int,
        originalPriceYuan: Int,
        deliveryMode: String,
        contact: String
    ): Result<MomentsTextbookListing>
    suspend fun toggleMomentsBookFavorite(bookId: String)
    suspend fun toggleMomentsBookSold(bookId: String): Result<Boolean>
    suspend fun deleteMomentsBookListing(bookId: String): Result<Boolean>
    suspend fun upsertAntiLostTag(
        tagCode: String = "",
        itemName: String,
        category: String,
        campus: String,
        ownerContact: String,
        returnNote: String,
        status: String = "NORMAL"
    ): Result<AntiLostTagItem>
    suspend fun reportAntiLostTagFound(
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String
    ): Result<Boolean>
}
