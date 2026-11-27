package cn.edu.haut.campus.feature.settings.moments.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiAction
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiState
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackBanner
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackTone
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSearchField
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.AntiLostTagItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.MomentsItemCategory
import cn.edu.haut.campus.core.model.MomentsPostItem
import cn.edu.haut.campus.core.model.MomentsPostType
import cn.edu.haut.campus.core.model.MomentsTextbookListing
import cn.edu.haut.campus.core.model.PresetCampusMomentsCatalog
import cn.edu.haut.campus.feature.settings.moments.state.ContactComposerAudience
import cn.edu.haut.campus.feature.settings.moments.state.MomentsContactComposerStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampusMomentsScreenContent(
    state: CampusMomentsUiState,
    actions: CampusMomentsUiAction,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = state.prefs
    val courses = state.courses
    val studentProfile = state.studentProfile
    val cloudBinding = state.cloudBinding
    val snapshot = prefs.campusMomentsSnapshot

    var selectedTab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var isRefreshing by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    var selectedPostType by rememberSaveable { mutableStateOf<MomentsPostType?>(null) }
    var selectedPostCampus by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPostCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyOpenPosts by rememberSaveable { mutableStateOf(false) }
    var postSearchQuery by rememberSaveable { mutableStateOf("") }
    var activePostDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPublishPostDialog by rememberSaveable { mutableStateOf(false) }

    val inferredTrack = remember(studentProfile?.major) {
        CampusMajorTrack.inferFromMajorText(studentProfile?.major)
    }
    var selectedBookTrack by rememberSaveable { mutableStateOf<CampusMajorTrack?>(null) }
    var selectedBookCampus by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyAvailableBooks by rememberSaveable { mutableStateOf(true) }
    var bookSearchQuery by rememberSaveable { mutableStateOf("") }
    var showPublishBookDialog by rememberSaveable { mutableStateOf(false) }

    var showCreateTagDialog by rememberSaveable { mutableStateOf(false) }
    var showReportFoundDialog by rememberSaveable { mutableStateOf(false) }
    var reportTargetTagCode by rememberSaveable { mutableStateOf("") }

    var mySubTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(actions) {
        isRefreshing = true
        runCatching { actions.refreshCampusMomentsSnapshot() }
        isRefreshing = false
    }

    val activeDetailPost = remember(snapshot.posts, activePostDetailId) {
        snapshot.posts.firstOrNull { it.id == activePostDetailId }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "互助广场",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (isRefreshing) "刷新中" else "刷新",
                    selected = false,
                    compact = true,
                    onClick = {
                        if (!isRefreshing) {
                            scope.launch {
                                isRefreshing = true
                                actions.refreshCampusMomentsSnapshot()
                                    .onSuccess {
                                        feedbackMessage = "已刷新"
                                    }
                                    .onFailure {
                                        feedbackMessage = "刷新失败"
                                    }
                                isRefreshing = false
                            }
                        }
                    }
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            XPeachSegmentedTabs(
                items = listOf("失物招领", "二手教材", "防丢码", "我的"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            XPeachFeedbackBanner(
                message = feedbackMessage,
                tone = XPeachFeedbackTone.INFO,
                onDismiss = { feedbackMessage = null }
            )

            when (selectedTab) {
                0 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "失物招领",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = { showPublishPostDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("发布", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        XPeachSearchField(
                            value = postSearchQuery,
                            onValueChange = { postSearchQuery = it },
                            placeholder = "搜索物品或地点"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachFilterPill(
                                label = "全部",
                                selected = selectedPostType == null,
                                compact = true,
                                onClick = { selectedPostType = null }
                            )
                            XPeachFilterPill(
                                label = "招领",
                                selected = selectedPostType == MomentsPostType.FOUND,
                                compact = true,
                                onClick = { selectedPostType = MomentsPostType.FOUND }
                            )
                            XPeachFilterPill(
                                label = "寻物",
                                selected = selectedPostType == MomentsPostType.LOST,
                                compact = true,
                                onClick = { selectedPostType = MomentsPostType.LOST }
                            )
                            XPeachFilterPill(
                                label = "未找回",
                                selected = onlyOpenPosts,
                                compact = true,
                                onClick = { onlyOpenPosts = !onlyOpenPosts }
                            )
                            for (campus in PresetCampusMomentsCatalog.CAMPUS_OPTIONS) {
                                XPeachFilterPill(
                                    label = campus.removeSuffix("校区"),
                                    selected = selectedPostCampus == campus,
                                    compact = true,
                                    onClick = {
                                        selectedPostCampus = if (selectedPostCampus == campus) null else campus
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachFilterPill(
                                label = "全部分类",
                                selected = selectedPostCategory == null,
                                compact = true,
                                onClick = { selectedPostCategory = null }
                            )
                            for (cat in MomentsItemCategory.entries) {
                                XPeachFilterPill(
                                    label = cat.label,
                                    selected = selectedPostCategory == cat.label,
                                    compact = true,
                                    onClick = { selectedPostCategory = cat.label }
                                )
                            }
                        }
                    }

                    val filteredPosts = remember(
                        snapshot.posts,
                        selectedPostType,
                        selectedPostCampus,
                        selectedPostCategory,
                        onlyOpenPosts,
                        postSearchQuery
                    ) {
                        val q = postSearchQuery.trim().lowercase()
                        snapshot.posts.filter { post ->
                            val typeOk = selectedPostType == null || post.type == selectedPostType
                            val campusOk = selectedPostCampus == null || post.campus == selectedPostCampus
                            val catOk = selectedPostCategory == null || post.category == selectedPostCategory
                            val statusOk = !onlyOpenPosts || !post.isResolved
                            val queryOk = q.isEmpty() ||
                                post.title.lowercase().contains(q) ||
                                post.location.lowercase().contains(q) ||
                                post.description.lowercase().contains(q) ||
                                post.category.lowercase().contains(q)
                            typeOk && campusOk && catOk && statusOk && queryOk
                        }
                    }

                    if (filteredPosts.isEmpty()) {
                        XPeachEmptyState(
                            title = "暂无记录",
                            subtitle = "点击发布寻物或招领信息",
                            icon = Icons.Rounded.FindInPage,
                            actionLabel = "发布",
                            onAction = { showPublishPostDialog = true }
                        )
                    } else {
                        for (post in filteredPosts) {
                            val matchedClues = remember(post, snapshot.posts) {
                                PresetCampusMomentsCatalog.computeLocalMatches(post, snapshot.posts)
                            }
                            MomentsPostCard(
                                post = post,
                                matchedCluesCount = matchedClues.size,
                                isFavorited = post.id in snapshot.favoritePostIds,
                                isMine = post.isOwner || post.canManage,
                                onOpenDetail = { activePostDetailId = post.id },
                                onLike = { scope.launch { actions.toggleMomentsPostLike(post.id) } },
                                onToggleFavorite = { scope.launch { actions.toggleMomentsPostFavorite(post.id) } },
                                onCopyContact = {
                                    if (post.canViewerReadRealContact) {
                                        copyTextToClipboard(context, "联系方式", post.contact)
                                        feedbackMessage = "已复制"
                                    } else {
                                        feedbackMessage = "该联系方式受保护，请先登录并发起联系申请"
                                    }
                                }
                            )
                        }
                    }
                }

                1 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "二手教材",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = { showPublishBookDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("发布", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        XPeachSearchField(
                            value = bookSearchQuery,
                            onValueChange = { bookSearchQuery = it },
                            placeholder = "搜索书名、课程或 ISBN"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachFilterPill(
                                label = "全部专业",
                                selected = selectedBookTrack == null,
                                compact = true,
                                onClick = { selectedBookTrack = null }
                            )
                            if (inferredTrack != null && inferredTrack != CampusMajorTrack.ALL_MAJORS) {
                                XPeachFilterPill(
                                    label = inferredTrack.shortLabel,
                                    selected = selectedBookTrack == inferredTrack,
                                    compact = true,
                                    onClick = { selectedBookTrack = inferredTrack }
                                )
                            }
                            for (track in CampusMajorTrack.entries.filter { it != CampusMajorTrack.ALL_MAJORS && it != inferredTrack }) {
                                XPeachFilterPill(
                                    label = track.shortLabel,
                                    selected = selectedBookTrack == track,
                                    compact = true,
                                    onClick = { selectedBookTrack = track }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachFilterPill(
                                label = "仅在售",
                                selected = onlyAvailableBooks,
                                compact = true,
                                onClick = { onlyAvailableBooks = !onlyAvailableBooks }
                            )
                            XPeachFilterPill(
                                label = "全部校区",
                                selected = selectedBookCampus == null,
                                compact = true,
                                onClick = { selectedBookCampus = null }
                            )
                            for (campus in PresetCampusMomentsCatalog.CAMPUS_OPTIONS) {
                                XPeachFilterPill(
                                    label = campus.removeSuffix("校区"),
                                    selected = selectedBookCampus == campus,
                                    compact = true,
                                    onClick = { selectedBookCampus = campus }
                                )
                            }
                        }
                    }

                    val filteredBooks = remember(
                        snapshot.bookListings,
                        selectedBookTrack,
                        selectedBookCampus,
                        onlyAvailableBooks,
                        bookSearchQuery
                    ) {
                        val q = bookSearchQuery.trim().lowercase()
                        snapshot.bookListings.filter { book ->
                            val trackOk = selectedBookTrack == null ||
                                book.majorTrack == CampusMajorTrack.ALL_MAJORS ||
                                book.majorTrack == selectedBookTrack
                            val campusOk = selectedBookCampus == null || book.campus == selectedBookCampus
                            val availOk = !onlyAvailableBooks || !book.isSold
                            val queryOk = q.isEmpty() ||
                                book.title.lowercase().contains(q) ||
                                book.courseName.lowercase().contains(q) ||
                                book.isbn.lowercase().contains(q) ||
                                book.authorPublisher.lowercase().contains(q)
                            trackOk && campusOk && availOk && queryOk
                        }
                    }

                    if (filteredBooks.isEmpty()) {
                        XPeachEmptyState(
                            title = "暂无教材",
                            subtitle = "点击发布闲置教材",
                            icon = Icons.Rounded.MenuBook,
                            actionLabel = "发布",
                            onAction = { showPublishBookDialog = true }
                        )
                    } else {
                        for (book in filteredBooks) {
                            MomentsTextbookCard(
                                book = book,
                                isFavorited = book.id in snapshot.favoriteBookIds,
                                isMine = book.isOwner || book.canManage,
                                onToggleFavorite = { scope.launch { actions.toggleMomentsBookFavorite(book.id) } },
                                onToggleSold = {
                                    scope.launch {
                                        actions.toggleMomentsBookSold(book.id).onFailure { err ->
                                            feedbackMessage = err.message ?: "更新失败"
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        actions.deleteMomentsBookListing(book.id).onFailure { err ->
                                            feedbackMessage = err.message ?: "删除失败"
                                        }
                                    }
                                },
                                onCopyContact = {
                                    copyTextToClipboard(context, "联系方式", book.contact)
                                    feedbackMessage = "已复制"
                                }
                            )
                        }
                    }
                }

                2 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showCreateTagDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("生成防丢码")
                            }
                            FilledTonalButton(
                                onClick = {
                                    reportTargetTagCode = ""
                                    showReportFoundDialog = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("上报拾得")
                            }
                        }
                    }

                    val ownedTags = remember(snapshot.antiLostTags, cloudBinding?.cloudUserId) {
                        if (cloudBinding == null) emptyList()
                        else snapshot.antiLostTags.filter { it.isOwner || it.canManage }
                    }
                    if (ownedTags.isEmpty()) {
                        XPeachEmptyState(
                            title = "暂无防丢码",
                            subtitle = if (cloudBinding != null) "可为随身物品生成专属防丢码" else "登录云端账号后可生成并管理个人专属防丢码",
                            icon = Icons.Rounded.QrCode2,
                            actionLabel = "生成防丢码",
                            onAction = { showCreateTagDialog = true }
                        )
                    }

                    for (tag in ownedTags) {
                        val claimUrl = remember(tag.tagCode, prefs.networkProxyConfig.customCloudApiBaseUrl) {
                            tag.claimWebUrl(prefs.networkProxyConfig.customCloudApiBaseUrl)
                        }
                        AntiLostTagCard(
                            tag = tag,
                            claimUrl = claimUrl,
                            onCopyLink = {
                                copyTextToClipboard(context, "认领链接", claimUrl)
                                feedbackMessage = "已复制"
                            },
                            onOpenWebPage = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(claimUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            },
                            onToggleLostStatus = {
                                scope.launch {
                                    val nextStatus = if (tag.status == "LOST") "NORMAL" else "LOST"
                                    actions.upsertAntiLostTag(
                                        tagCode = tag.tagCode,
                                        itemName = tag.itemName,
                                        category = tag.category,
                                        campus = tag.campus,
                                        ownerContact = tag.ownerContact,
                                        returnNote = tag.returnNote,
                                        status = nextStatus
                                    )
                                }
                            },
                            onReportFound = {
                                reportTargetTagCode = tag.tagCode
                                showReportFoundDialog = true
                            }
                        )
                    }
                }

                3 -> {
                    XPeachSegmentedTabs(
                        items = listOf("发布", "收藏", "通知"),
                        selectedIndex = mySubTab,
                        onSelect = { mySubTab = it }
                    )

                    when (mySubTab) {
                        0 -> {
                            val myPosts = snapshot.posts.filter { it.isOwner || it.canManage }
                            val myBooks = snapshot.bookListings.filter { it.isOwner || it.canManage }
                            if (myPosts.isEmpty() && myBooks.isEmpty()) {
                                XPeachEmptyState(
                                    title = "暂无发布记录",
                                    subtitle = "发布的内容将显示在此处",
                                    icon = Icons.Rounded.FindInPage
                                )
                            }
                            for (post in myPosts) {
                                MomentsPostCard(
                                    post = post,
                                    matchedCluesCount = PresetCampusMomentsCatalog.computeLocalMatches(post, snapshot.posts).size,
                                    isFavorited = post.id in snapshot.favoritePostIds,
                                    isMine = true,
                                    onOpenDetail = { activePostDetailId = post.id },
                                    onLike = { scope.launch { actions.toggleMomentsPostLike(post.id) } },
                                    onToggleFavorite = { scope.launch { actions.toggleMomentsPostFavorite(post.id) } },
                                    onCopyContact = {
                                        if (post.canViewerReadRealContact) {
                                            copyTextToClipboard(context, "联系方式", post.contact)
                                            feedbackMessage = "已复制"
                                        } else {
                                            feedbackMessage = "该联系方式受保护，请先登录并发起联系申请"
                                        }
                                    }
                                )
                            }
                            for (book in myBooks) {
                                MomentsTextbookCard(
                                    book = book,
                                    isFavorited = book.id in snapshot.favoriteBookIds,
                                    isMine = true,
                                    onToggleFavorite = { scope.launch { actions.toggleMomentsBookFavorite(book.id) } },
                                    onToggleSold = {
                                        scope.launch {
                                            actions.toggleMomentsBookSold(book.id).onFailure { err ->
                                                feedbackMessage = err.message ?: "更新失败"
                                            }
                                        }
                                    },
                                    onDelete = {
                                        scope.launch {
                                            actions.deleteMomentsBookListing(book.id).onFailure { err ->
                                                feedbackMessage = err.message ?: "删除失败"
                                            }
                                        }
                                    },
                                    onCopyContact = {
                                        copyTextToClipboard(context, "联系方式", book.contact)
                                        feedbackMessage = "已复制"
                                    }
                                )
                            }
                        }

                        1 -> {
                            val favPosts = snapshot.posts.filter { it.id in snapshot.favoritePostIds }
                            val favBooks = snapshot.bookListings.filter { it.id in snapshot.favoriteBookIds }
                            if (favPosts.isEmpty() && favBooks.isEmpty()) {
                                XPeachEmptyState(
                                    title = "暂无收藏",
                                    subtitle = "点击收藏图标即可保存",
                                    icon = Icons.Rounded.BookmarkBorder
                                )
                            }
                            for (post in favPosts) {
                                MomentsPostCard(
                                    post = post,
                                    matchedCluesCount = PresetCampusMomentsCatalog.computeLocalMatches(post, snapshot.posts).size,
                                    isFavorited = true,
                                    isMine = post.isOwner || post.canManage,
                                    onOpenDetail = { activePostDetailId = post.id },
                                    onLike = { scope.launch { actions.toggleMomentsPostLike(post.id) } },
                                    onToggleFavorite = { scope.launch { actions.toggleMomentsPostFavorite(post.id) } },
                                    onCopyContact = {
                                        if (post.canViewerReadRealContact) {
                                            copyTextToClipboard(context, "联系方式", post.contact)
                                            feedbackMessage = "已复制"
                                        } else {
                                            feedbackMessage = "该联系方式受保护，请先登录并发起联系申请"
                                        }
                                    }
                                )
                            }
                            for (book in favBooks) {
                                MomentsTextbookCard(
                                    book = book,
                                    isFavorited = true,
                                    isMine = book.isOwner || book.canManage,
                                    onToggleFavorite = { scope.launch { actions.toggleMomentsBookFavorite(book.id) } },
                                    onToggleSold = {
                                        scope.launch {
                                            actions.toggleMomentsBookSold(book.id).onFailure { err ->
                                                feedbackMessage = err.message ?: "更新失败"
                                            }
                                        }
                                    },
                                    onDelete = {
                                        scope.launch {
                                            actions.deleteMomentsBookListing(book.id).onFailure { err ->
                                                feedbackMessage = err.message ?: "删除失败"
                                            }
                                        }
                                    },
                                    onCopyContact = {
                                        copyTextToClipboard(context, "联系方式", book.contact)
                                        feedbackMessage = "已复制"
                                    }
                                )
                            }
                        }

                        2 -> {
                            val allTagReports = snapshot.antiLostTags
                                .filter { it.isOwner || it.canManage }
                                .flatMap { tag -> tag.reports.map { tag to it } }
                            val postsWithPrivateThreads = snapshot.posts.filter {
                                ((it.isOwner || it.canManage) && it.pendingContactRequestsCount > 0) ||
                                    (!it.isOwner && !it.canManage && it.hasMyContactRequest)
                            }
                            val postsWithComments = snapshot.posts.filter {
                                (it.isOwner || it.canManage) && it.comments.isNotEmpty()
                            }
                            if (allTagReports.isEmpty() && postsWithPrivateThreads.isEmpty() && postsWithComments.isEmpty()) {
                                XPeachEmptyState(
                                    title = "暂无通知",
                                    subtitle = "收到私信申请、线索留言或拾得上报时在此显示",
                                    icon = Icons.Rounded.QrCode2
                                )
                            }
                            for (post in postsWithPrivateThreads) {
                                val postIsMine = post.isOwner || post.canManage
                                XPeachCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activePostDetailId = post.id }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = post.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        XPeachStatusBadge(
                                            text = if (postIsMine) {
                                                "待处理私信 ${post.pendingContactRequestsCount}"
                                            } else if (post.myContactRequestStatus == "APPROVED") {
                                                "私信已获批"
                                            } else {
                                                "私信会话"
                                            },
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = XPeachTheme.readablePrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (postIsMine) {
                                            "点击查看申请人说明、进行多轮私信沟通或授权共享联系方式"
                                        } else {
                                            "点击查看发起人回复或继续发送私信说明"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            for ((tag, rep) in allTagReports) {
                                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = tag.itemName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        XPeachStatusBadge(
                                            text = tag.tagCode,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = XPeachTheme.readablePrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "位置：${rep.foundLocation}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (rep.message.isNotBlank()) {
                                        Text(
                                            text = rep.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (rep.finderContact.isNotBlank()) {
                                        Text(
                                            text = "联系：${rep.finderContact}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            for (post in postsWithComments) {
                                XPeachCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activePostDetailId = post.id }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = post.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        XPeachStatusDotText(
                                            text = "${post.comments.size} 条留言",
                                            dotColor = XPeachTheme.readablePrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val latestComment = post.comments.last()
                                    Text(
                                        text = latestComment.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (activeDetailPost != null) {
        val matchedClues = remember(activeDetailPost, snapshot.posts) {
            PresetCampusMomentsCatalog.computeLocalMatches(activeDetailPost, snapshot.posts)
        }
        val activeViewerCloudUserId = cloudBinding?.cloudUserId.orEmpty()
        var contactRequestsResult by remember(activeDetailPost.id, activeViewerCloudUserId) {
            mutableStateOf(cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper.MomentsContactRequestsListResult())
        }
        var contactRequestsTrigger by remember { mutableIntStateOf(0) }
        LaunchedEffect(activeDetailPost.id, contactRequestsTrigger, activeViewerCloudUserId) {
            contactRequestsResult = if (activeViewerCloudUserId.isNotBlank()) {
                actions.fetchMomentsContactRequestsResult(activeDetailPost.id)
            } else {
                cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper.MomentsContactRequestsListResult()
            }
        }
        val canManageActivePost =
            activeViewerCloudUserId.isNotBlank() && (
                activeDetailPost.isOwner ||
                    activeDetailPost.canManage ||
                    contactRequestsResult.isOwner ||
                    contactRequestsResult.canManage
            )
        val isBlockedInLatestRequests = contactRequestsResult.requests.any { it.status == "BLOCKED" } && !canManageActivePost
        val effectiveDetailPost = if (isBlockedInLatestRequests && activeDetailPost.contactVisibility.equals("ON_REQUEST", ignoreCase = true)) {
            activeDetailPost.copy(
                contact = cn.edu.haut.campus.core.model.MASKED_ON_REQUEST_MOMENTS_CONTACT,
                hasProtectedContact = true
            )
        } else {
            activeDetailPost
        }
        MomentsPostDetailDialog(
            post = effectiveDetailPost,
            matchedClues = matchedClues,
            contactRequests = contactRequestsResult.requests,
            isMine = canManageActivePost,
            viewerCloudUserId = activeViewerCloudUserId,
            onDismiss = { activePostDetailId = null },
            onCopyContact = { contactText ->
                if (contactText.isNotBlank() && contactText != cn.edu.haut.campus.core.model.MASKED_ON_REQUEST_MOMENTS_CONTACT) {
                    copyTextToClipboard(context, "联系方式", contactText)
                    feedbackMessage = "已复制"
                } else {
                    feedbackMessage = "该联系方式受保护，请先登录并发起联系申请"
                }
            },
            onJumpToCluePost = { clueId ->
                activePostDetailId = clueId
            },
            onSubmitContactRequest = { message, contactInfo, clientMessageId, onResult ->
                // 捕获不可变提交身份，发送前按当前云端账号复核：切号/解绑则放弃本次发送
                val submitPostId = activeDetailPost.id
                val submitViewerId = activeViewerCloudUserId
                scope.launch {
                    val liveViewerId = actions.currentCloudUserId()
                    val result = if (submitViewerId.isNotBlank() && submitViewerId == liveViewerId) {
                        actions.submitMomentsContactRequest(
                            postId = submitPostId,
                            message = message,
                            contactInfo = contactInfo,
                            clientMessageId = clientMessageId
                        )
                    } else {
                        Result.failure(
                            IllegalStateException("云端账号已切换或未登录，本次私密申请已保留草稿未发送")
                        )
                    }
                    result.onSuccess {
                        feedbackMessage = "已向发起人提交私密联系申请（仅发起人与您本人可见）"
                        contactRequestsTrigger++
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "私密联系申请发送失败，请检查云端登录与网络连接"
                    }
                    onResult(result.isSuccess)
                }
            },
            onHandleContactRequestAction = { requestId, action, replyMessage, clientMessageId, onResult ->
                val submitPostId = activeDetailPost.id
                val submitViewerId = activeViewerCloudUserId
                scope.launch {
                    val liveViewerId = actions.currentCloudUserId()
                    val result = if (submitViewerId.isNotBlank() && submitViewerId == liveViewerId) {
                        actions.handleMomentsContactRequestAction(
                            postId = submitPostId,
                            requestId = requestId,
                            action = action,
                            replyMessage = replyMessage,
                            clientMessageId = clientMessageId
                        )
                    } else {
                        Result.failure(
                            IllegalStateException("云端账号已切换或未登录，本次操作未提交")
                        )
                    }
                    result.onSuccess {
                        feedbackMessage = when (action) {
                            "APPROVE" -> "已同意申请并向对方公开您的联系方式"
                            "REPLY" -> "私信回复已发送"
                            "REJECT" -> "已拒绝该联系申请"
                            "BLOCK" -> "已拉黑该申请人并撤销其全部权限"
                            else -> "已处理联系申请"
                        }
                        contactRequestsTrigger++
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "操作失败，请检查网络连接"
                    }
                    onResult(result.isSuccess)
                }
            },
            onClaimLegacyOwnership = { claimToken ->
                scope.launch {
                    actions.claimLegacyMomentsPostOwnership(
                        postId = activeDetailPost.id,
                        claimToken = claimToken
                    ).onSuccess {
                        feedbackMessage = "已通过高熵所有权凭证核验并绑定当前云端账号管理权"
                        contactRequestsTrigger++
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "旧帖所有权凭证核验未通过"
                    }
                }
            },
            onSubmitComment = { content, contactHint, clientMessageId, onResult ->
                // 公开留言使用独立的公开草稿字段：私密联系方式结构上不可能进入该调用
                scope.launch {
                    val result = actions.addMomentsComment(
                        postId = activeDetailPost.id,
                        content = content,
                        contactHint = contactHint,
                        clientMessageId = clientMessageId
                    )
                    result.onSuccess {
                        feedbackMessage = "已发表公开线索留言"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "公开留言未能提交至云端，请检查网络连接后重试"
                    }
                    onResult(result.isSuccess)
                }
            },
            onReportPost = {
                scope.launch {
                    actions.reportMomentsPost(activeDetailPost.id)
                        .onSuccess {
                            feedbackMessage = "举报已提交至云端，管理员将核查该失物招领信息"
                            activePostDetailId = null
                        }
                        .onFailure { err ->
                            feedbackMessage = err.message ?: "举报提交失败，请检查网络连接后重试"
                        }
                }
            },
            onToggleResolved = {
                scope.launch {
                    actions.toggleMomentsPostResolved(activeDetailPost.id)
                        .onSuccess {
                            feedbackMessage = "已更新帖子状态"
                        }
                        .onFailure { err ->
                            feedbackMessage = err.message ?: "状态更新失败，请确认已登录发起人云端账号"
                        }
                }
            },
            onDelete = {
                scope.launch {
                    val delId = activeDetailPost.id
                    actions.deleteMomentsPost(delId)
                        .onSuccess {
                            activePostDetailId = null
                            feedbackMessage = "已删除该帖子"
                        }
                        .onFailure { err ->
                            feedbackMessage = err.message ?: "删除失败，请确认已登录发起人云端账号"
                        }
                }
            }
        )
    }

    if (showPublishPostDialog) {
        PublishMomentsPostDialog(
            isCloudSignedIn = cloudBinding != null,
            onDismiss = { showPublishPostDialog = false },
            onSubmit = { type, title, category, campus, location, desc, contact, reward, contactVisibility ->
                scope.launch {
                    actions.publishMomentsPost(
                        type = type,
                        title = title,
                        category = category,
                        campus = campus,
                        location = location,
                        description = desc,
                        contact = contact,
                        rewardNote = reward,
                        contactVisibility = contactVisibility
                    ).onSuccess {
                        showPublishPostDialog = false
                        feedbackMessage = "已发布"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "发布失败"
                    }
                }
            }
        )
    }

    if (showPublishBookDialog) {
        val courseNames = remember(courses) {
            courses.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
        }
        PublishMomentsBookDialog(
            courseNames = courseNames,
            isbnCatalog = snapshot.isbnCatalog,
            defaultTrack = inferredTrack ?: CampusMajorTrack.ALL_MAJORS,
            onDismiss = { showPublishBookDialog = false },
            onSubmit = { title, isbn, publisher, courseName, track, campus, condition, price, origPrice, delivery, contact ->
                scope.launch {
                    actions.publishMomentsBookListing(
                        title = title,
                        isbn = isbn,
                        authorPublisher = publisher,
                        courseName = courseName,
                        majorTrack = track,
                        campus = campus,
                        conditionLabel = condition,
                        priceYuan = price,
                        originalPriceYuan = origPrice,
                        deliveryMode = delivery,
                        contact = contact
                    ).onSuccess {
                        showPublishBookDialog = false
                        feedbackMessage = "已发布"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "发布失败"
                    }
                }
            }
        )
    }

    if (showCreateTagDialog) {
        CreateAntiLostTagDialog(
            onDismiss = { showCreateTagDialog = false },
            onSubmit = { itemName, category, campus, contact, note ->
                scope.launch {
                    actions.upsertAntiLostTag(
                        itemName = itemName,
                        category = category,
                        campus = campus,
                        ownerContact = contact,
                        returnNote = note
                    ).onSuccess {
                        showCreateTagDialog = false
                        feedbackMessage = "已生成"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "生成失败"
                    }
                }
            }
        )
    }

    if (showReportFoundDialog) {
        ReportAntiLostFoundDialog(
            initialTagCode = reportTargetTagCode,
            onDismiss = { showReportFoundDialog = false },
            onSubmit = { code, finderName, location, contact, message ->
                scope.launch {
                    actions.reportAntiLostTagFound(
                        tagCode = code,
                        finderName = finderName,
                        foundLocation = location,
                        finderContact = contact,
                        message = message
                    ).onSuccess {
                        showReportFoundDialog = false
                        feedbackMessage = "已提交"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "提交失败"
                    }
                }
            }
        )
    }
}
