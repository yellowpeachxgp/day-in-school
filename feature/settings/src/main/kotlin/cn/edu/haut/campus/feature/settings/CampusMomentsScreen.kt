package cn.edu.haut.campus.feature.settings

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
import androidx.compose.runtime.collectAsState
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
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusMomentsScreen(
    repository: CampusRepository,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val studentProfile by repository.studentProfileFlow.collectAsState(initial = null)
    val cloudBinding by repository.cloudIdentityBindingFlow.collectAsState(initial = null)
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

    LaunchedEffect(repository) {
        isRefreshing = true
        runCatching { repository.refreshCampusMomentsSnapshot() }
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
                                repository.refreshCampusMomentsSnapshot()
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
                                onLike = { scope.launch { repository.toggleMomentsPostLike(post.id) } },
                                onToggleFavorite = { scope.launch { repository.toggleMomentsPostFavorite(post.id) } },
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
                                isMine = book.id in snapshot.myBookIds,
                                onToggleFavorite = { scope.launch { repository.toggleMomentsBookFavorite(book.id) } },
                                onToggleSold = { scope.launch { repository.toggleMomentsBookSold(book.id) } },
                                onDelete = { scope.launch { repository.deleteMomentsBookListing(book.id) } },
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

                    if (snapshot.antiLostTags.isEmpty()) {
                        XPeachEmptyState(
                            title = "暂无防丢码",
                            subtitle = "可为随身物品生成专属防丢码",
                            icon = Icons.Rounded.QrCode2,
                            actionLabel = "生成防丢码",
                            onAction = { showCreateTagDialog = true }
                        )
                    }

                    for (tag in snapshot.antiLostTags) {
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
                                    repository.upsertAntiLostTag(
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
                            val myBooks = snapshot.bookListings.filter { it.id in snapshot.myBookIds }
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
                                    onLike = { scope.launch { repository.toggleMomentsPostLike(post.id) } },
                                    onToggleFavorite = { scope.launch { repository.toggleMomentsPostFavorite(post.id) } },
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
                                    onToggleFavorite = { scope.launch { repository.toggleMomentsBookFavorite(book.id) } },
                                    onToggleSold = { scope.launch { repository.toggleMomentsBookSold(book.id) } },
                                    onDelete = { scope.launch { repository.deleteMomentsBookListing(book.id) } },
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
                                    onLike = { scope.launch { repository.toggleMomentsPostLike(post.id) } },
                                    onToggleFavorite = { scope.launch { repository.toggleMomentsPostFavorite(post.id) } },
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
                                    isMine = book.id in snapshot.myBookIds,
                                    onToggleFavorite = { scope.launch { repository.toggleMomentsBookFavorite(book.id) } },
                                    onToggleSold = { scope.launch { repository.toggleMomentsBookSold(book.id) } },
                                    onDelete = { scope.launch { repository.deleteMomentsBookListing(book.id) } },
                                    onCopyContact = {
                                        copyTextToClipboard(context, "联系方式", book.contact)
                                        feedbackMessage = "已复制"
                                    }
                                )
                            }
                        }

                        2 -> {
                            val allTagReports = snapshot.antiLostTags.flatMap { tag ->
                                tag.reports.map { tag to it }
                            }
                            val postsWithComments = snapshot.posts.filter { it.comments.isNotEmpty() }
                            if (allTagReports.isEmpty() && postsWithComments.isEmpty()) {
                                XPeachEmptyState(
                                    title = "暂无通知",
                                    subtitle = "收到线索或拾得上报时在此显示",
                                    icon = Icons.Rounded.QrCode2
                                )
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
                repository.fetchMomentsContactRequestsResult(activeDetailPost.id)
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
        MomentsPostDetailDialog(
            post = activeDetailPost,
            matchedClues = matchedClues,
            contactRequests = contactRequestsResult.requests,
            isMine = canManageActivePost,
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
            onSubmitContactRequest = { message, contactInfo ->
                scope.launch {
                    repository.submitMomentsContactRequest(
                        postId = activeDetailPost.id,
                        message = message,
                        contactInfo = contactInfo
                    ).onSuccess {
                        feedbackMessage = "已向发起人提交私密联系申请（仅发起人与您本人可见）"
                        contactRequestsTrigger++
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "私密联系申请发送失败，请检查云端登录与网络连接"
                    }
                }
            },
            onHandleContactRequestAction = { requestId, action ->
                scope.launch {
                    repository.handleMomentsContactRequestAction(
                        postId = activeDetailPost.id,
                        requestId = requestId,
                        action = action
                    ).onSuccess {
                        feedbackMessage = when (action) {
                            "APPROVE" -> "已同意申请并向对方公开您的联系方式"
                            "REJECT" -> "已拒绝该联系申请"
                            "BLOCK" -> "已拉黑该申请人并撤销其全部权限"
                            else -> "已处理联系申请"
                        }
                        contactRequestsTrigger++
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "操作失败，请检查网络连接"
                    }
                }
            },
            onClaimLegacyOwnership = { claimToken ->
                scope.launch {
                    repository.claimLegacyMomentsPostOwnership(
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
            onSubmitComment = { content, contactHint ->
                scope.launch {
                    repository.addMomentsComment(
                        postId = activeDetailPost.id,
                        content = content,
                        contactHint = contactHint
                    ).onSuccess {
                        feedbackMessage = "已发表公开线索留言"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "公开留言未能提交至云端，请检查网络连接后重试"
                    }
                }
            },
            onReportPost = {
                scope.launch {
                    repository.reportMomentsPost(activeDetailPost.id)
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
                    repository.toggleMomentsPostResolved(activeDetailPost.id)
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
                    repository.deleteMomentsPost(delId)
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
                    repository.publishMomentsPost(
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
                    repository.publishMomentsBookListing(
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
                    repository.upsertAntiLostTag(
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
                    repository.reportAntiLostTagFound(
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

@Composable
private fun MomentsPostCard(
    post: MomentsPostItem,
    matchedCluesCount: Int,
    isFavorited: Boolean,
    isMine: Boolean,
    onOpenDetail: () -> Unit,
    onLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyContact: () -> Unit
) {
    XPeachCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                XPeachStatusBadge(
                    text = if (post.isResolved) "已找回" else post.type.badgeLabel,
                    containerColor = when {
                        post.isResolved -> MaterialTheme.colorScheme.surfaceVariant
                        post.type == MomentsPostType.FOUND -> XPeachTheme.successContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = when {
                        post.isResolved -> MaterialTheme.colorScheme.onSurfaceVariant
                        post.type == MomentsPostType.FOUND -> XPeachTheme.onSuccessContainer
                        else -> XPeachTheme.readablePrimary
                    }
                )
                XPeachStatusBadge(
                    text = post.category,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (matchedCluesCount > 0 && !post.isResolved) {
                    XPeachStatusBadge(
                        text = "匹配 $matchedCluesCount",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            if (post.createdAt.isNotBlank()) {
                Text(
                    text = post.createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = post.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (post.isResolved) TextDecoration.LineThrough else null
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${post.campus} · ${post.location}",
            style = MaterialTheme.typography.labelMedium,
            color = XPeachTheme.readablePrimary,
            fontWeight = FontWeight.Medium
        )

        if (post.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = post.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    if (post.authorName.isNotBlank()) append(post.authorName)
                    if (isMine) append(" (我)")
                    if (post.comments.isNotEmpty()) append(" · ${post.comments.size} 留言")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onLike,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUpOffAlt,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("${post.likesCount}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = onToggleFavorite,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorited) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isFavorited) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                FilledTonalButton(
                    onClick = onOpenDetail,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("向发起人发消息", style = MaterialTheme.typography.labelSmall)
                }
                if (post.canViewerReadRealContact) {
                    OutlinedButton(
                        onClick = onCopyContact,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("复制联系", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentsTextbookCard(
    book: MomentsTextbookListing,
    isFavorited: Boolean,
    isMine: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleSold: () -> Unit,
    onDelete: () -> Unit,
    onCopyContact: () -> Unit
) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XPeachStatusBadge(
                        text = if (book.isSold) "已出" else book.conditionLabel,
                        containerColor = if (book.isSold) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (book.isSold) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            XPeachTheme.readablePrimary
                        }
                    )
                    if (book.courseName.isNotBlank()) {
                        XPeachStatusBadge(
                            text = book.courseName,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (book.isSold) TextDecoration.LineThrough else null
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "¥${book.priceYuan}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (book.isSold) MaterialTheme.colorScheme.outline else XPeachTheme.readablePrimary
                )
                if (book.originalPriceYuan > book.priceYuan) {
                    Text(
                        text = "¥${book.originalPriceYuan}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }
        }

        if (book.authorPublisher.isNotBlank() || book.isbn.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    book.authorPublisher.takeIf { it.isNotBlank() },
                    book.isbn.takeIf { it.isNotBlank() }?.let { "ISBN $it" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${book.campus} · ${book.deliveryMode}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        XPeachInsetDivider(verticalPadding = 8.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onToggleFavorite,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorited) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isFavorited) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(if (isFavorited) "已收藏" else "收藏", style = MaterialTheme.typography.labelSmall)
                }
                if (isMine) {
                    TextButton(
                        onClick = onToggleSold,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (book.isSold) "上架" else "已出", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (book.contact.isNotBlank() && !book.isSold) {
                FilledTonalButton(
                    onClick = onCopyContact,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("联系", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AntiLostTagCard(
    tag: AntiLostTagItem,
    claimUrl: String,
    onCopyLink: () -> Unit,
    onOpenWebPage: () -> Unit,
    onToggleLostStatus: () -> Unit,
    onReportFound: () -> Unit
) {
    val matrix = remember(tag.tagCode, tag.itemName) { tag.generateVisualMatrix(21) }
    val primaryColor = XPeachTheme.readablePrimary

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gridCount = matrix.size
                    val cellW = size.width / gridCount
                    val cellH = size.height / gridCount
                    for (r in 0 until gridCount) {
                        for (c in 0 until gridCount) {
                            if (matrix[r][c]) {
                                drawRect(
                                    color = Color(0xFF0F172A),
                                    topLeft = Offset(c * cellW, r * cellH),
                                    size = Size(cellW + 0.4f, cellH + 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XPeachStatusBadge(
                        text = when (tag.status) {
                            "FOUND_REPORTED" -> "已上报 (${tag.reports.size})"
                            "LOST" -> "挂失中"
                            else -> "正常"
                        },
                        containerColor = when (tag.status) {
                            "FOUND_REPORTED" -> XPeachTheme.successContainer
                            "LOST" -> XPeachTheme.dangerContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when (tag.status) {
                            "FOUND_REPORTED" -> XPeachTheme.onSuccessContainer
                            "LOST" -> XPeachTheme.onDangerContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    Text(
                        text = tag.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = tag.itemName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = tag.tagCode,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }
        }

        if (tag.returnNote.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tag.returnNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (tag.reports.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            for (rep in tag.reports.take(2)) {
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "位置：${rep.foundLocation}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    if (rep.message.isNotBlank()) {
                        Text(
                            text = rep.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onCopyLink,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("复制链接", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onOpenWebPage,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("认领页", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onToggleLostStatus,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(
                    if (tag.status == "LOST") "取消挂失" else "挂失",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            TextButton(
                onClick = onReportFound,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("上报拾得", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MomentsPostDetailDialog(
    post: MomentsPostItem,
    matchedClues: List<cn.edu.haut.campus.core.model.MomentsMatchedClue>,
    contactRequests: List<cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper.MomentsContactRequestDto> = emptyList(),
    isMine: Boolean,
    onDismiss: () -> Unit,
    onCopyContact: (String) -> Unit,
    onJumpToCluePost: (String) -> Unit,
    onSubmitContactRequest: (message: String, contactInfo: String) -> Unit = { _, _ -> },
    onHandleContactRequestAction: (requestId: String, action: String) -> Unit = { _, _ -> },
    onClaimLegacyOwnership: (contactProof: String) -> Unit = {},
    onSubmitComment: (content: String, contactHint: String) -> Unit,
    onReportPost: () -> Unit = {},
    onToggleResolved: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var commentInput by remember { mutableStateOf("") }
    var contactHintInput by remember { mutableStateOf("") }
    var legacyProofInput by remember { mutableStateOf("") }
    var showLegacyClaimBox by remember { mutableStateOf(false) }
    var messageModeIndex by remember { mutableIntStateOf(0) } // 0: 私密联系申请(需云端鉴权), 1: 公开线索留言(所有人可见)

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = post.title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${post.campus} · ${post.location} · ${post.category}",
                    style = MaterialTheme.typography.labelMedium,
                    color = XPeachTheme.readablePrimary
                )
                if (post.description.isNotBlank()) {
                    Text(
                        text = post.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (post.rewardNote.isNotBlank()) {
                    Text(
                        text = post.rewardNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (post.contact.isNotBlank()) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = post.contact,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (post.canViewerReadRealContact) {
                                FilledTonalButton(
                                    onClick = { onCopyContact(post.contact) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("复制", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                if (matchedClues.isNotEmpty()) {
                    XPeachInsetDivider(verticalPadding = 4.dp)
                    Text(
                        text = "相关线索 (${matchedClues.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary
                    )
                    for (clue in matchedClues) {
                        XPeachWell(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToCluePost(clue.id) }
                        ) {
                            Text(
                                text = "${clue.type.badgeLabel} · ${clue.title}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${clue.location} · ${clue.contact}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (contactRequests.isNotEmpty()) {
                    XPeachInsetDivider(verticalPadding = 4.dp)
                    Text(
                        text = if (isMine) "收到的私密认领/联系申请 (${contactRequests.size})" else "我的私密联系申请 (${contactRequests.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary
                    )
                    for (req in contactRequests) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = req.senderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                XPeachStatusDotText(
                                    text = when (req.status) {
                                        "APPROVED" -> "已同意共享联系"
                                        "REJECTED" -> "已拒绝"
                                        "BLOCKED" -> "已拉黑屏蔽"
                                        else -> "待发起人确认"
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = req.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (req.contactInfo.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "申请人联系方式：${req.contactInfo}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    XPeachFilterPill(
                                        label = "复制",
                                        selected = false,
                                        compact = true,
                                        onClick = { onCopyContact(req.contactInfo) }
                                    )
                                }
                            }
                            val approvedInitiatorContact = req.initiatorContact
                            if (!approvedInitiatorContact.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "发起人已授权公开联系方式：$approvedInitiatorContact",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = XPeachTheme.readablePrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    XPeachFilterPill(
                                        label = "复制",
                                        selected = true,
                                        compact = true,
                                        onClick = { onCopyContact(approvedInitiatorContact) }
                                    )
                                }
                            }
                            if (isMine && req.status != "BLOCKED") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (req.status != "APPROVED") {
                                        XPeachFilterPill(
                                            label = "同意共享联系",
                                            selected = true,
                                            compact = true,
                                            onClick = { onHandleContactRequestAction(req.id, "APPROVE") }
                                        )
                                    }
                                    if (req.status == "PENDING") {
                                        XPeachFilterPill(
                                            label = "拒绝",
                                            selected = false,
                                            compact = true,
                                            onClick = { onHandleContactRequestAction(req.id, "REJECT") }
                                        )
                                    }
                                    XPeachFilterPill(
                                        label = "拉黑",
                                        selected = false,
                                        compact = true,
                                        onClick = { onHandleContactRequestAction(req.id, "BLOCK") }
                                    )
                                }
                            }
                        }
                    }
                }

                if (post.comments.isNotEmpty()) {
                    XPeachInsetDivider(verticalPadding = 4.dp)
                    Text(
                        text = "公开线索留言 (${post.comments.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    for (c in post.comments) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = c.authorName.ifBlank { "热心同学" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary
                                )
                                XPeachStatusDotText(text = "公开留言")
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = c.content,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (c.contactHint.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "公开备注联系：${c.contactHint}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    XPeachFilterPill(
                                        label = "复制",
                                        selected = false,
                                        compact = true,
                                        onClick = { onCopyContact(c.contactHint) }
                                    )
                                }
                            }
                        }
                    }
                }

                XPeachInsetDivider(verticalPadding = 4.dp)
                Text(
                    text = if (isMine) "补充公开说明" else "联系发起人 / 提供认领线索",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                XPeachSegmentedTabs(
                    items = listOf("私密联系申请", "公开线索留言"),
                    selectedIndex = messageModeIndex,
                    onSelect = { messageModeIndex = it }
                )
                Text(
                    text = if (messageModeIndex == 0) {
                        "通过云端受控接口发送认领/联系申请，仅帖子发起人与您本人可见（需先登录云端账号）"
                    } else {
                        "公开线索留言对所有人可见，请勿在公开留言中填写手机号、微信号等个人敏感隐私"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val quickTemplates = remember(post.type) {
                    if (post.type == MomentsPostType.LOST) {
                        listOf(
                            "我捡到了这件物品，请联系我核对",
                            "在附近看到了类似物品",
                            "已放至就近值班室/服务台"
                        )
                    } else {
                        listOf(
                            "这是我遗失的物品，可提供学号/特征核实",
                            "请问现在方便在哪里当面认领？"
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (tpl in quickTemplates) {
                        XPeachFilterPill(
                            label = tpl,
                            selected = commentInput == tpl,
                            compact = true,
                            onClick = { commentInput = tpl }
                        )
                    }
                }

                OutlinedTextField(
                    value = commentInput,
                    onValueChange = { commentInput = it },
                    label = {
                        Text(
                            if (messageModeIndex == 0) "给发起人的认领说明 / 物品特征（必填，1-240字）"
                            else "公开线索留言（所有人可见，1-240字）"
                        )
                    },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = contactHintInput,
                    onValueChange = { contactHintInput = it },
                    label = {
                        Text(
                            if (messageModeIndex == 0) "我的联系方式（必填，仅发起人可见）"
                            else "公开备注（选填，所有人可见）"
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                val extractedDigits = remember(post.contact, post.canViewerReadRealContact) {
                    if (!post.canViewerReadRealContact) null
                    else Regex("""\d{5,11}""").find(post.contact)?.value
                }
                if (extractedDigits != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (extractedDigits.length == 11 && extractedDigits.startsWith("1")) {
                            FilledTonalButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$extractedDigits")).apply {
                                                putExtra("sms_body", "同学你好，我在工大课表互助广场看到你发布的「${post.title}」：")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("短信联系发起人", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    onCopyContact(extractedDigits)
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("mqqwpa://im/chat?chat_type=wpa&uin=$extractedDigits&version=1&src_type=web")
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("打开 QQ 联系发起人", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                if (!isMine && post.isLegacyUnowned) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (post.canClaimLegacy) {
                                "该帖支持凭发帖时签发的原始高熵所有权凭证（32位以上）认领管理权；为防止冒领，已关闭基于联系方式或脱敏学号的自助认领。"
                            } else {
                                "该帖为历史无主发布记录，为防冒领已关闭自助认领；如需结案、删除或核验处理，请点击下方提交由管理员人工核查。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (post.canClaimLegacy) {
                            Spacer(modifier = Modifier.height(6.dp))
                            if (!showLegacyClaimBox) {
                                OutlinedButton(
                                    onClick = { showLegacyClaimBox = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("凭原始高熵所有权凭证认领旧帖", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                OutlinedTextField(
                                    value = legacyProofInput,
                                    onValueChange = { legacyProofInput = it },
                                    label = { Text("输入 32 位以上原始所有权凭证 (claim_token)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    enabled = legacyProofInput.trim().length >= 32,
                                    onClick = {
                                        onClaimLegacyOwnership(legacyProofInput.trim())
                                        legacyProofInput = ""
                                        showLegacyClaimBox = false
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("提交所有权凭证核验", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isMine) {
                        OutlinedButton(
                            onClick = onToggleResolved,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (post.isResolved) "重新开启" else "标记已找回")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onReportPost,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (post.isLegacyUnowned) "举报 / 申请管理员结案处理" else "举报不良或虚假信息")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = commentInput.trim().isNotEmpty() &&
                    (messageModeIndex == 1 || contactHintInput.trim().isNotEmpty()),
                onClick = {
                    if (messageModeIndex == 0) {
                        onSubmitContactRequest(commentInput.trim().take(240), contactHintInput.trim().take(64))
                    } else {
                        onSubmitComment(commentInput.trim().take(240), contactHintInput.trim().take(64))
                    }
                    commentInput = ""
                    contactHintInput = ""
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (messageModeIndex == 0) "提交私密联系申请" else "发表公开留言")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublishMomentsPostDialog(
    isCloudSignedIn: Boolean = true,
    onDismiss: () -> Unit,
    onSubmit: (
        type: MomentsPostType,
        title: String,
        category: String,
        campus: String,
        location: String,
        description: String,
        contact: String,
        rewardNote: String,
        contactVisibility: String
    ) -> Unit
) {
    var postType by remember { mutableStateOf(MomentsPostType.LOST) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MomentsItemCategory.CAMPUS_CARD.label) }
    var campus by remember { mutableStateOf("莲花街校区") }
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var contactVisibility by remember { mutableStateOf("ON_REQUEST") }
    var rewardNote by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布寻物 / 招领") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isCloudSignedIn) {
                            "当前已登录云端账号：发布后绑定您的云端身份，换机重装后仍可结案、删除及审批联系申请。"
                        } else {
                            "身份要求：发布寻物或招领信息需先在「我的 → 云端账号」登录账号，以保障跨设备结案、删除与隐私审批权限。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCloudSignedIn) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XPeachFilterPill(
                        label = "寻物",
                        selected = postType == MomentsPostType.LOST,
                        onClick = { postType = MomentsPostType.LOST }
                    )
                    XPeachFilterPill(
                        label = "招领",
                        selected = postType == MomentsPostType.FOUND,
                        onClick = { postType = MomentsPostType.FOUND }
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("物品名称 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (cat in MomentsItemCategory.entries) {
                        XPeachFilterPill(
                            label = cat.label,
                            selected = category == cat.label,
                            compact = true,
                            onClick = { category = cat.label }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (cp in PresetCampusMomentsCatalog.CAMPUS_OPTIONS) {
                        XPeachFilterPill(
                            label = cp.removeSuffix("校区"),
                            selected = campus == cp,
                            compact = true,
                            onClick = { campus = cp }
                        )
                    }
                }

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (locChip in PresetCampusMomentsCatalog.QUICK_LOCATIONS) {
                        XPeachFilterPill(
                            label = locChip,
                            selected = location == locChip,
                            compact = true,
                            onClick = { location = locChip }
                        )
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("联系方式 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "仅申请通过后可见（推荐）",
                        selected = contactVisibility == "ON_REQUEST",
                        compact = true,
                        onClick = { contactVisibility = "ON_REQUEST" }
                    )
                    XPeachFilterPill(
                        label = "直接公开",
                        selected = contactVisibility == "PUBLIC",
                        compact = true,
                        onClick = { contactVisibility = "PUBLIC" }
                    )
                }

                OutlinedTextField(
                    value = rewardNote,
                    onValueChange = { rewardNote = it },
                    label = { Text("备注（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = isCloudSignedIn && title.trim().isNotEmpty() && contact.trim().isNotEmpty(),
                onClick = {
                    onSubmit(
                        postType,
                        title,
                        category,
                        campus,
                        location.ifBlank { "校内" },
                        description,
                        contact,
                        rewardNote,
                        contactVisibility
                    )
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("发布")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublishMomentsBookDialog(
    courseNames: List<String>,
    isbnCatalog: List<cn.edu.haut.campus.core.model.MomentsIsbnCatalogEntry>,
    defaultTrack: CampusMajorTrack,
    onDismiss: () -> Unit,
    onSubmit: (
        title: String,
        isbn: String,
        publisher: String,
        courseName: String,
        track: CampusMajorTrack,
        campus: String,
        condition: String,
        priceYuan: Int,
        originalPriceYuan: Int,
        deliveryMode: String,
        contact: String
    ) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var isbn by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("") }
    var courseName by remember { mutableStateOf("") }
    var majorTrack by remember { mutableStateOf(defaultTrack) }
    var campus by remember { mutableStateOf("莲花街校区") }
    var condition by remember { mutableStateOf(PresetCampusMomentsCatalog.BOOK_CONDITIONS[1]) }
    var priceText by remember { mutableStateOf("") }
    var origPriceText by remember { mutableStateOf("") }
    var deliveryMode by remember { mutableStateOf(PresetCampusMomentsCatalog.BOOK_DELIVERY_MODES[0]) }
    var contact by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布二手教材") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (courseNames.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (cName in courseNames) {
                            XPeachFilterPill(
                                label = cName,
                                selected = courseName == cName,
                                compact = true,
                                onClick = {
                                    courseName = cName
                                    val matched = isbnCatalog.firstOrNull {
                                        it.courseName.contains(cName) || cName.contains(it.courseName)
                                    }
                                    if (matched != null) {
                                        title = matched.title
                                        isbn = matched.isbn
                                        publisher = matched.authorPublisher
                                        majorTrack = matched.majorTrack
                                        origPriceText = matched.originalPriceYuan.toString()
                                    } else if (title.isBlank()) {
                                        title = cName
                                    }
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = isbn,
                    onValueChange = { input ->
                        isbn = input
                        val clean = input.trim()
                        val matched = isbnCatalog.firstOrNull { it.isbn == clean }
                        if (matched != null) {
                            title = matched.title
                            publisher = matched.authorPublisher
                            courseName = matched.courseName
                            majorTrack = matched.majorTrack
                            origPriceText = matched.originalPriceYuan.toString()
                        }
                    },
                    label = { Text("ISBN（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = publisher,
                    onValueChange = { publisher = it },
                    label = { Text("出版社（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.filter(Char::isDigit) },
                        label = { Text("转让价 (¥) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = origPriceText,
                        onValueChange = { origPriceText = it.filter(Char::isDigit) },
                        label = { Text("原价 (¥)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (cond in PresetCampusMomentsCatalog.BOOK_CONDITIONS) {
                        XPeachFilterPill(
                            label = cond,
                            selected = condition == cond,
                            compact = true,
                            onClick = { condition = cond }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (mode in PresetCampusMomentsCatalog.BOOK_DELIVERY_MODES) {
                        XPeachFilterPill(
                            label = mode,
                            selected = deliveryMode == mode,
                            compact = true,
                            onClick = { deliveryMode = mode }
                        )
                    }
                }

                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("联系方式 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.trim().isNotEmpty() && contact.trim().isNotEmpty(),
                onClick = {
                    val p = priceText.toIntOrNull() ?: 15
                    val op = origPriceText.toIntOrNull() ?: p
                    onSubmit(
                        title,
                        isbn,
                        publisher,
                        courseName,
                        majorTrack,
                        campus,
                        condition,
                        p,
                        op,
                        deliveryMode,
                        contact
                    )
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("发布")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateAntiLostTagDialog(
    onDismiss: () -> Unit,
    onSubmit: (
        itemName: String,
        category: String,
        campus: String,
        ownerContact: String,
        returnNote: String
    ) -> Unit
) {
    var itemName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MomentsItemCategory.CUP_UMBRELLA.label) }
    var campus by remember { mutableStateOf("莲花街校区") }
    var ownerContact by remember { mutableStateOf("") }
    var returnNote by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成防丢码") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text("物品名称 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (cat in MomentsItemCategory.entries) {
                        XPeachFilterPill(
                            label = cat.label,
                            selected = category == cat.label,
                            compact = true,
                            onClick = { category = cat.label }
                        )
                    }
                }

                OutlinedTextField(
                    value = ownerContact,
                    onValueChange = { ownerContact = it },
                    label = { Text("联系方式 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = returnNote,
                    onValueChange = { returnNote = it },
                    label = { Text("归还说明（选填）") },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = itemName.trim().isNotEmpty() && ownerContact.trim().isNotEmpty(),
                onClick = { onSubmit(itemName, category, campus, ownerContact, returnNote) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ReportAntiLostFoundDialog(
    initialTagCode: String,
    onDismiss: () -> Unit,
    onSubmit: (
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String
    ) -> Unit
) {
    var tagCode by remember(initialTagCode) { mutableStateOf(initialTagCode) }
    var finderName by remember { mutableStateOf("") }
    var foundLocation by remember { mutableStateOf("") }
    var finderContact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上报拾得") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tagCode,
                    onValueChange = { tagCode = it.uppercase() },
                    label = { Text("防丢码 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = foundLocation,
                    onValueChange = { foundLocation = it },
                    label = { Text("放置位置 *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = finderContact,
                    onValueChange = { finderContact = it },
                    label = { Text("联系方式（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("留言（选填）") },
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = tagCode.trim().isNotEmpty() && foundLocation.trim().isNotEmpty(),
                onClick = { onSubmit(tagCode, finderName, foundLocation, finderContact, message) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun copyTextToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}
