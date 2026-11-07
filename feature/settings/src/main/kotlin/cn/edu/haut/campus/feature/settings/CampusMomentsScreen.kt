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
    val snapshot = prefs.campusMomentsSnapshot

    var selectedTab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var isRefreshing by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    // Tab 0: 失物招领筛选状态
    var selectedPostType by rememberSaveable { mutableStateOf<MomentsPostType?>(null) }
    var selectedPostCampus by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPostCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyOpenPosts by rememberSaveable { mutableStateOf(false) }
    var postSearchQuery by rememberSaveable { mutableStateOf("") }
    var activePostDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPublishPostDialog by rememberSaveable { mutableStateOf(false) }

    // Tab 1: 二手教材筛选状态
    val inferredTrack = remember(studentProfile?.major) {
        CampusMajorTrack.inferFromMajorText(studentProfile?.major)
    }
    var selectedBookTrack by rememberSaveable { mutableStateOf<CampusMajorTrack?>(null) }
    var selectedBookCampus by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyAvailableBooks by rememberSaveable { mutableStateOf(true) }
    var bookSearchQuery by rememberSaveable { mutableStateOf("") }
    var showPublishBookDialog by rememberSaveable { mutableStateOf(false) }

    // Tab 2: 防丢码弹窗状态
    var showCreateTagDialog by rememberSaveable { mutableStateOf(false) }
    var showReportFoundDialog by rememberSaveable { mutableStateOf(false) }
    var reportTargetTagCode by rememberSaveable { mutableStateOf("") }

    // Tab 3: 我的与线索子标签
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
            title = "失物招领与二手书",
            subtitle = "河南工业大学 · 寻物招领 · 二手教材 · 防丢码",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (isRefreshing) "同步中…" else "刷新",
                    selected = false,
                    compact = true,
                    onClick = {
                        if (!isRefreshing) {
                            scope.launch {
                                isRefreshing = true
                                repository.refreshCampusMomentsSnapshot()
                                    .onSuccess { updated ->
                                        feedbackMessage = "已同步最新动态：招领/寻物 ${updated.posts.size} 条 · 教材 ${updated.bookListings.size} 本"
                                    }
                                    .onFailure {
                                        feedbackMessage = "当前显示本地缓存记录"
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
                items = listOf(
                    "失物招领 (${snapshot.posts.size})",
                    "二手教材 (${snapshot.bookListings.size})",
                    "防丢码 (${snapshot.antiLostTags.size})",
                    "我的/线索"
                ),
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "校园寻物与失物招领",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "支持按校区与物品类别筛选，自动匹配同校区疑似线索",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                            placeholder = "搜索物品名称、地点（如图书馆、二餐厅、校园卡）"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachFilterPill(
                                label = "全部类型",
                                selected = selectedPostType == null,
                                compact = true,
                                onClick = { selectedPostType = null }
                            )
                            XPeachFilterPill(
                                label = "失物招领 (${snapshot.posts.count { it.type == MomentsPostType.FOUND }})",
                                selected = selectedPostType == MomentsPostType.FOUND,
                                compact = true,
                                onClick = { selectedPostType = MomentsPostType.FOUND }
                            )
                            XPeachFilterPill(
                                label = "寻物启事 (${snapshot.posts.count { it.type == MomentsPostType.LOST }})",
                                selected = selectedPostType == MomentsPostType.LOST,
                                compact = true,
                                onClick = { selectedPostType = MomentsPostType.LOST }
                            )
                            XPeachFilterPill(
                                label = "仅看未找回",
                                selected = onlyOpenPosts,
                                compact = true,
                                onClick = { onlyOpenPosts = !onlyOpenPosts }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachFilterPill(
                                label = "全部校区",
                                selected = selectedPostCampus == null,
                                compact = true,
                                onClick = { selectedPostCampus = null }
                            )
                            for (campus in PresetCampusMomentsCatalog.CAMPUS_OPTIONS) {
                                XPeachFilterPill(
                                    label = campus.removeSuffix("校区"),
                                    selected = selectedPostCampus == campus,
                                    compact = true,
                                    onClick = { selectedPostCampus = campus }
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
                            title = "暂无匹配的寻物或招领记录",
                            subtitle = "可切换筛选条件或点击右上角发布新信息",
                            icon = Icons.Rounded.FindInPage,
                            actionLabel = "重置筛选",
                            onAction = {
                                selectedPostType = null
                                selectedPostCampus = null
                                selectedPostCategory = null
                                onlyOpenPosts = false
                                postSearchQuery = ""
                            }
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
                                isMine = post.id in snapshot.myPostIds,
                                onOpenDetail = { activePostDetailId = post.id },
                                onLike = { scope.launch { repository.toggleMomentsPostLike(post.id) } },
                                onToggleFavorite = { scope.launch { repository.toggleMomentsPostFavorite(post.id) } },
                                onCopyContact = {
                                    copyTextToClipboard(context, "失物招领联系方式", post.contact)
                                    feedbackMessage = "已复制联系方式：${post.contact}"
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "二手教材与复习资料流转",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "支持从个人课表一键选教材、ISBN 识别与同校区自提",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                Text("挂单出书", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        XPeachSearchField(
                            value = bookSearchQuery,
                            onValueChange = { bookSearchQuery = it },
                            placeholder = "搜索教材名称、课程名或 ISBN（如高等数学、数据结构）"
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
                                    label = "本专业 (${inferredTrack.shortLabel})",
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
                                label = "仅看在售",
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
                            title = "暂无匹配的二手教材",
                            subtitle = "点击右上角「挂单出书」即可发布闲置课本或复习资料",
                            icon = Icons.Rounded.MenuBook,
                            actionLabel = "查看全部教材",
                            onAction = {
                                selectedBookTrack = null
                                selectedBookCampus = null
                                onlyAvailableBooks = false
                                bookSearchQuery = ""
                            }
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
                                    copyTextToClipboard(context, "卖家联系方式", book.contact)
                                    feedbackMessage = "已复制卖家联系方式：${book.contact}"
                                }
                            )
                        }
                    }
                }

                2 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "随身物品防丢码",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "为保温杯、雨伞、校园卡套、耳机仓生成专属防丢码与认领网页（campus.yellowpeachxgp.com/claim/…）。拾得者扫码免安装即可上报位置并通知失主。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showCreateTagDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("生成我的防丢码")
                            }
                            FilledTonalButton(
                                onClick = {
                                    reportTargetTagCode = ""
                                    showReportFoundDialog = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("输入防丢码上报拾得")
                            }
                        }
                    }

                    for (tag in snapshot.antiLostTags) {
                        val claimUrl = remember(tag.tagCode, prefs.networkProxyConfig.customCloudApiBaseUrl) {
                            tag.claimWebUrl(prefs.networkProxyConfig.customCloudApiBaseUrl)
                        }
                        AntiLostTagCard(
                            tag = tag,
                            claimUrl = claimUrl,
                            onCopyLink = {
                                copyTextToClipboard(context, "防丢码认领链接", claimUrl)
                                feedbackMessage = "已复制防丢码认领链接：$claimUrl"
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
                                    feedbackMessage = if (nextStatus == "LOST") {
                                        "已将「${tag.itemName}」标记为挂失寻找中"
                                    } else {
                                        "已将「${tag.itemName}」恢复为正常随身状态"
                                    }
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
                        items = listOf(
                            "我的发布 (${snapshot.myPostIds.size + snapshot.myBookIds.size})",
                            "我的收藏 (${snapshot.favoritePostIds.size + snapshot.favoriteBookIds.size})",
                            "线索与通知"
                        ),
                        selectedIndex = mySubTab,
                        onSelect = { mySubTab = it }
                    )

                    when (mySubTab) {
                        0 -> {
                            val myPosts = snapshot.posts.filter { it.id in snapshot.myPostIds }
                            val myBooks = snapshot.bookListings.filter { it.id in snapshot.myBookIds }
                            if (myPosts.isEmpty() && myBooks.isEmpty()) {
                                XPeachEmptyState(
                                    title = "暂未发布寻物或二手教材",
                                    subtitle = "您发布的寻物招领与二手教材会汇总在此处，方便随时更新状态",
                                    icon = Icons.Rounded.FindInPage,
                                    actionLabel = "去发布寻物/招领",
                                    onAction = {
                                        selectedTab = 0
                                        showPublishPostDialog = true
                                    }
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
                                        copyTextToClipboard(context, "联系方式", post.contact)
                                        feedbackMessage = "已复制：${post.contact}"
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
                                        feedbackMessage = "已复制：${book.contact}"
                                    }
                                )
                            }
                        }

                        1 -> {
                            val favPosts = snapshot.posts.filter { it.id in snapshot.favoritePostIds }
                            val favBooks = snapshot.bookListings.filter { it.id in snapshot.favoriteBookIds }
                            if (favPosts.isEmpty() && favBooks.isEmpty()) {
                                XPeachEmptyState(
                                    title = "暂无收藏内容",
                                    subtitle = "浏览失物招领或二手教材时点击收藏图标即可保存",
                                    icon = Icons.Rounded.BookmarkBorder
                                )
                            }
                            for (post in favPosts) {
                                MomentsPostCard(
                                    post = post,
                                    matchedCluesCount = PresetCampusMomentsCatalog.computeLocalMatches(post, snapshot.posts).size,
                                    isFavorited = true,
                                    isMine = post.id in snapshot.myPostIds,
                                    onOpenDetail = { activePostDetailId = post.id },
                                    onLike = { scope.launch { repository.toggleMomentsPostLike(post.id) } },
                                    onToggleFavorite = { scope.launch { repository.toggleMomentsPostFavorite(post.id) } },
                                    onCopyContact = {
                                        copyTextToClipboard(context, "联系方式", post.contact)
                                        feedbackMessage = "已复制：${post.contact}"
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
                                        feedbackMessage = "已复制：${book.contact}"
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
                                    title = "暂无新线索或拾得通知",
                                    subtitle = "当有同学通过防丢码上报拾得或在帖子下提供线索时，将在此处展示",
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
                                            text = "防丢码拾得通知 · ${tag.itemName}",
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
                                        text = "拾得/放置位置：${rep.foundLocation}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (rep.message.isNotBlank()) {
                                        Text(
                                            text = "补充留言：${rep.message}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "上报人：${rep.finderName}${if (rep.finderContact.isNotBlank()) " · 联系：${rep.finderContact}" else ""} · ${rep.createdAt}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                                        text = "${latestComment.authorName}：${latestComment.content}",
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
        MomentsPostDetailDialog(
            post = activeDetailPost,
            matchedClues = matchedClues,
            isMine = activeDetailPost.id in snapshot.myPostIds,
            onDismiss = { activePostDetailId = null },
            onCopyContact = { contactText ->
                copyTextToClipboard(context, "联系方式", contactText)
                feedbackMessage = "已复制联系方式：$contactText"
            },
            onJumpToCluePost = { clueId ->
                activePostDetailId = clueId
            },
            onSubmitComment = { content, contactHint ->
                scope.launch {
                    repository.addMomentsComment(
                        postId = activeDetailPost.id,
                        content = content,
                        contactHint = contactHint
                    ).onSuccess {
                        feedbackMessage = "已提交线索留言"
                    }
                }
            },
            onToggleResolved = {
                scope.launch {
                    repository.toggleMomentsPostResolved(activeDetailPost.id)
                    feedbackMessage = if (activeDetailPost.isResolved) "已重新开启该帖" else "已标记为找回/认领完成"
                }
            },
            onDelete = {
                scope.launch {
                    val delId = activeDetailPost.id
                    activePostDetailId = null
                    repository.deleteMomentsPost(delId)
                    feedbackMessage = "已删除该条记录"
                }
            }
        )
    }

    if (showPublishPostDialog) {
        PublishMomentsPostDialog(
            onDismiss = { showPublishPostDialog = false },
            onSubmit = { type, title, category, campus, location, desc, contact, reward ->
                scope.launch {
                    repository.publishMomentsPost(
                        type = type,
                        title = title,
                        category = category,
                        campus = campus,
                        location = location,
                        description = desc,
                        contact = contact,
                        rewardNote = reward
                    ).onSuccess { created ->
                        showPublishPostDialog = false
                        feedbackMessage = "已发布${created.type.label}：${created.title}"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "发布失败，请检查填写内容"
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
                    ).onSuccess { created ->
                        showPublishBookDialog = false
                        feedbackMessage = "已挂单二手教材：《${created.title}》（¥${created.priceYuan}）"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "发布失败，请检查填写内容"
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
                    ).onSuccess { tag ->
                        showCreateTagDialog = false
                        feedbackMessage = "已生成防丢码：${tag.tagCode}（${tag.itemName}）"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "生成失败，请检查填写内容"
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
                    ).onSuccess { tag ->
                        showReportFoundDialog = false
                        feedbackMessage = "已上报「${tag.itemName}」拾得位置，感谢热心相助"
                    }.onFailure { err ->
                        feedbackMessage = err.message ?: "提交失败，请检查防丢码与位置"
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
                        text = "疑似匹配 $matchedCluesCount",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Text(
                text = post.createdAt.ifBlank { "今天" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    append(post.authorName)
                    if (isMine) append(" (我)")
                    if (post.comments.isNotEmpty()) append(" · ${post.comments.size} 条线索")
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
                        contentDescription = "点赞",
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
                        contentDescription = "收藏",
                        tint = if (isFavorited) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (post.contact.isNotBlank()) {
                    FilledTonalButton(
                        onClick = onCopyContact,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
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
                        text = "原价 ¥${book.originalPriceYuan}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = buildString {
                append(book.authorPublisher)
                if (book.isbn.isNotBlank()) append(" · ISBN ${book.isbn}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${book.campus} · ${book.deliveryMode} · 卖家：${book.sellerName}",
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
                    Text(if (isFavorited) "已想买" else "想买", style = MaterialTheme.typography.labelSmall)
                }
                if (isMine) {
                    TextButton(
                        onClick = onToggleSold,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (book.isSold) "重新上架" else "标记已出", style = MaterialTheme.typography.labelSmall)
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
                    Text("联系卖家", style = MaterialTheme.typography.labelSmall)
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
                    .size(92.dp)
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
                            "FOUND_REPORTED" -> "有人上报拾得 (${tag.reports.size})"
                            "LOST" -> "挂失寻找中"
                            else -> "正常随身"
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

                Text(
                    text = "${tag.campus} · 失主：${tag.ownerName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        XPeachWell(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "归还说明：${tag.returnNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "扫码认领页：$claimUrl",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (tag.reports.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            for (rep in tag.reports.take(2)) {
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "📍 拾得上报：${rep.foundLocation}",
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
                    Text(
                        text = "${rep.finderName}${if (rep.finderContact.isNotBlank()) " · 联系：${rep.finderContact}" else ""} · ${rep.createdAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                Text("复制防丢链接", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onOpenWebPage,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("打开认领网页", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onToggleLostStatus,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(
                    if (tag.status == "LOST") "取消挂失" else "挂失物品",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            TextButton(
                onClick = onReportFound,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("模拟拾得上报", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MomentsPostDetailDialog(
    post: MomentsPostItem,
    matchedClues: List<cn.edu.haut.campus.core.model.MomentsMatchedClue>,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onCopyContact: (String) -> Unit,
    onJumpToCluePost: (String) -> Unit,
    onSubmitComment: (content: String, contactHint: String) -> Unit,
    onToggleResolved: () -> Unit,
    onDelete: () -> Unit
) {
    var commentInput by remember { mutableStateOf("") }
    var contactHintInput by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${post.type.label}详情",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = post.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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
                        text = "认领/酬谢说明：${post.rewardNote}",
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "发布者：${post.authorName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "联系方式：${post.contact}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
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

                if (matchedClues.isNotEmpty()) {
                    XPeachInsetDivider(verticalPadding = 4.dp)
                    Text(
                        text = "同校区疑似匹配线索 (${matchedClues.size})",
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${clue.type.badgeLabel} · ${clue.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                XPeachStatusBadge(
                                    text = "匹配度 ${clue.matchScore}%",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = XPeachTheme.readablePrimary
                                )
                            }
                            Text(
                                text = "地点：${clue.location} · 联系：${clue.contact}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                XPeachInsetDivider(verticalPadding = 4.dp)
                Text(
                    text = "线索留言 (${post.comments.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (post.comments.isEmpty()) {
                    Text(
                        text = "暂无线索留言，若您看到过该物品可在下方提供位置线索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    for (c in post.comments) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${c.authorName} · ${c.createdAt}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = c.content,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (c.contactHint.isNotBlank()) {
                                Text(
                                    text = "联系方式：${c.contactHint}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = XPeachTheme.readablePrimary
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = commentInput,
                    onValueChange = { commentInput = it },
                    label = { Text("提供线索或留言") },
                    placeholder = { Text("例如：已放在三楼服务台 / 我在二餐厅看到过") },
                    singleLine = false,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = contactHintInput,
                    onValueChange = { contactHintInput = it },
                    label = { Text("您的联系方式（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleResolved,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (post.isResolved) "重新开启" else "标记已找回")
                    }
                    if (isMine) {
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("删除记录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = commentInput.trim().isNotEmpty(),
                onClick = {
                    onSubmitComment(commentInput.trim(), contactHintInput.trim())
                    commentInput = ""
                    contactHintInput = ""
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("发送线索")
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
    onDismiss: () -> Unit,
    onSubmit: (
        type: MomentsPostType,
        title: String,
        category: String,
        campus: String,
        location: String,
        description: String,
        contact: String,
        rewardNote: String
    ) -> Unit
) {
    var postType by remember { mutableStateOf(MomentsPostType.LOST) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MomentsItemCategory.CAMPUS_CARD.label) }
    var campus by remember { mutableStateOf("莲花街校区") }
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var rewardNote by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布寻物 / 失物招领") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XPeachFilterPill(
                        label = "我丢了物品 (寻物)",
                        selected = postType == MomentsPostType.LOST,
                        onClick = { postType = MomentsPostType.LOST }
                    )
                    XPeachFilterPill(
                        label = "我捡到物品 (招领)",
                        selected = postType == MomentsPostType.FOUND,
                        onClick = { postType = MomentsPostType.FOUND }
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("物品名称 / 标题 *") },
                    placeholder = { Text("例如：拾到校园一卡通 / 寻 AirPods 耳机仓") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("物品分类", style = MaterialTheme.typography.labelSmall)
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

                Text("所属校区", style = MaterialTheme.typography.labelSmall)
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
                    label = { Text("遗失或拾得地点 *") },
                    placeholder = { Text("例如：图书馆三层西书库 / 二餐厅二楼") },
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
                    label = { Text("特征描述或放置位置") },
                    placeholder = { Text("描述物品外观特征、卡号尾号或当前存放在哪个服务台") },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("联系方式 (QQ / 微信 / 电话) *") },
                    placeholder = { Text("例如：QQ: 123456789") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = rewardNote,
                    onValueChange = { rewardNote = it },
                    label = { Text("认领或酬谢备注（选填）") },
                    placeholder = { Text("例如：凭学号认领 / 奶茶酬谢") },
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
                    onSubmit(
                        postType,
                        title,
                        category,
                        campus,
                        location.ifBlank { "校内" },
                        description,
                        contact,
                        rewardNote
                    )
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确认发布")
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
    var publisher by remember { mutableStateOf("高等教育出版社") }
    var courseName by remember { mutableStateOf("") }
    var majorTrack by remember { mutableStateOf(defaultTrack) }
    var campus by remember { mutableStateOf("莲花街校区") }
    var condition by remember { mutableStateOf(PresetCampusMomentsCatalog.BOOK_CONDITIONS[1]) }
    var priceText by remember { mutableStateOf("15") }
    var origPriceText by remember { mutableStateOf("55") }
    var deliveryMode by remember { mutableStateOf(PresetCampusMomentsCatalog.BOOK_DELIVERY_MODES[0]) }
    var contact by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布二手教材") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (courseNames.isNotEmpty()) {
                    Text(
                        text = "从我的课表快速选课填充：",
                        style = MaterialTheme.typography.labelSmall,
                        color = XPeachTheme.readablePrimary
                    )
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
                                        title = "$cName 教材与复习资料"
                                    }
                                }
                            )
                        }
                    }
                }

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
                    label = { Text("ISBN 书号（输入自动匹配教材信息）") },
                    placeholder = { Text("例如：9787040589818") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("教材名称 *") },
                    placeholder = { Text("例如：高等数学（同济第八版）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = publisher,
                    onValueChange = { publisher = it },
                    label = { Text("作者 / 出版社") },
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

                Text("品相成色", style = MaterialTheme.typography.labelSmall)
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

                Text("交接方式", style = MaterialTheme.typography.labelSmall)
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
                    label = { Text("卖家联系方式 (QQ / 微信) *") },
                    placeholder = { Text("例如：QQ: 2310406002") },
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
                    val op = origPriceText.toIntOrNull() ?: (p + 30)
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
                Text("确认上架")
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
    var returnNote by remember { mutableStateOf("若拾到请放至就近教学楼/图书馆服务台，或通过本页留言告知位置，万分感谢！") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成随身物品防丢码") },
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
                    label = { Text("随身物品名称 *") },
                    placeholder = { Text("例如：白色保温杯 / AirPods 耳机仓 / 校园卡套") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("物品分类", style = MaterialTheme.typography.labelSmall)
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
                    label = { Text("失主联系方式 (QQ / 微信 / 电话) *") },
                    placeholder = { Text("例如：QQ: 310948271") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = returnNote,
                    onValueChange = { returnNote = it },
                    label = { Text("扫码页面显示的归还说明") },
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
                Text("立即生成")
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
    var tagCode by remember(initialTagCode) { mutableStateOf(initialTagCode.ifBlank { "HAUT-TAG-7E2A9B" }) }
    var finderName by remember { mutableStateOf("") }
    var foundLocation by remember { mutableStateOf("") }
    var finderContact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上报拾得防丢码物品") },
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
                    label = { Text("防丢码编号 *") },
                    placeholder = { Text("例如：HAUT-TAG-7E2A9B") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = foundLocation,
                    onValueChange = { foundLocation = it },
                    label = { Text("拾得或当前放置位置 *") },
                    placeholder = { Text("例如：已放在图书馆一楼服务台") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = finderName,
                    onValueChange = { finderName = it },
                    label = { Text("您的称呼（选填）") },
                    placeholder = { Text("例如：热心同学") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = finderContact,
                    onValueChange = { finderContact = it },
                    label = { Text("您的联系方式（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("补充留言（选填）") },
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
                Text("通知失主")
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
