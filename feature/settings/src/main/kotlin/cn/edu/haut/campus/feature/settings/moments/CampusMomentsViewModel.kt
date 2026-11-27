package cn.edu.haut.campus.feature.settings.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.AntiLostTagItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.CampusMomentsSnapshot
import cn.edu.haut.campus.core.model.MomentsPostItem
import cn.edu.haut.campus.core.model.MomentsPostType
import cn.edu.haut.campus.core.model.MomentsTextbookListing
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiAction
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Moments 状态 holder：聚合快照输入并委托全部写动作。 */
class CampusMomentsViewModel(
    private val repository: CampusRepository
) : ViewModel(), CampusMomentsUiAction {
    private val mutableUiState = MutableStateFlow(CampusMomentsUiState())
    val uiState: StateFlow<CampusMomentsUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { repository.preferencesFlow.collectLatest { value -> mutableUiState.update { it.copy(prefs = value) } } }
        viewModelScope.launch { repository.coursesFlow.collectLatest { value -> mutableUiState.update { it.copy(courses = value) } } }
        viewModelScope.launch { repository.studentProfileFlow.collectLatest { value -> mutableUiState.update { it.copy(studentProfile = value) } } }
        viewModelScope.launch { repository.cloudIdentityBindingFlow.collectLatest { value -> mutableUiState.update { it.copy(cloudBinding = value) } } }
    }

    override suspend fun currentCloudUserId(): String =
        repository.cloudIdentityBindingFlow.first()?.cloudUserId.orEmpty()
    override suspend fun refreshCampusMomentsSnapshot(): Result<CampusMomentsSnapshot> = repository.refreshCampusMomentsSnapshot()
    override suspend fun publishMomentsPost(
        type: MomentsPostType,
        title: String,
        category: String,
        campus: String,
        location: String,
        description: String,
        contact: String,
        rewardNote: String,
        contactVisibility: String
    ): Result<MomentsPostItem> = repository.publishMomentsPost(type, title, category, campus, location, description, contact, rewardNote, contactVisibility)
    override suspend fun addMomentsComment(postId: String, content: String, contactHint: String, clientMessageId: String?): Result<MomentsPostItem> =
        repository.addMomentsComment(postId, content, contactHint, clientMessageId)
    override suspend fun submitMomentsContactRequest(postId: String, message: String, contactInfo: String, clientMessageId: String?): Result<CampusCloudOidcHelper.MomentsContactRequestDto> =
        repository.submitMomentsContactRequest(postId, message, contactInfo, clientMessageId)
    override suspend fun fetchMomentsContactRequestsResult(postId: String): CampusCloudOidcHelper.MomentsContactRequestsListResult =
        repository.fetchMomentsContactRequestsResult(postId)
    override suspend fun claimLegacyMomentsPostOwnership(postId: String, claimToken: String): Result<MomentsPostItem> =
        repository.claimLegacyMomentsPostOwnership(postId, claimToken)
    override suspend fun handleMomentsContactRequestAction(
        postId: String,
        requestId: String,
        action: String,
        replyMessage: String?,
        reason: String?,
        clientMessageId: String?
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> =
        repository.handleMomentsContactRequestAction(postId, requestId, action, replyMessage, reason, clientMessageId)
    override suspend fun reportMomentsPost(postId: String, reason: String): Result<Boolean> = repository.reportMomentsPost(postId, reason)
    override suspend fun toggleMomentsPostLike(postId: String) = repository.toggleMomentsPostLike(postId)
    override suspend fun toggleMomentsPostFavorite(postId: String) = repository.toggleMomentsPostFavorite(postId)
    override suspend fun toggleMomentsPostResolved(postId: String): Result<Boolean> = repository.toggleMomentsPostResolved(postId)
    override suspend fun deleteMomentsPost(postId: String): Result<Boolean> = repository.deleteMomentsPost(postId)
    override suspend fun publishMomentsBookListing(
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
    ): Result<MomentsTextbookListing> = repository.publishMomentsBookListing(
        title, isbn, authorPublisher, courseName, majorTrack, campus, conditionLabel, priceYuan, originalPriceYuan, deliveryMode, contact
    )
    override suspend fun toggleMomentsBookFavorite(bookId: String) = repository.toggleMomentsBookFavorite(bookId)
    override suspend fun toggleMomentsBookSold(bookId: String): Result<Boolean> = repository.toggleMomentsBookSold(bookId)
    override suspend fun deleteMomentsBookListing(bookId: String): Result<Boolean> = repository.deleteMomentsBookListing(bookId)
    override suspend fun upsertAntiLostTag(
        tagCode: String,
        itemName: String,
        category: String,
        campus: String,
        ownerContact: String,
        returnNote: String,
        status: String
    ): Result<AntiLostTagItem> = repository.upsertAntiLostTag(tagCode, itemName, category, campus, ownerContact, returnNote, status)
    override suspend fun reportAntiLostTagFound(
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String
    ): Result<Boolean> = repository.reportAntiLostTagFound(tagCode, finderName, foundLocation, finderContact, message)

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CampusMomentsViewModel(repository) }
        }
    }
}
