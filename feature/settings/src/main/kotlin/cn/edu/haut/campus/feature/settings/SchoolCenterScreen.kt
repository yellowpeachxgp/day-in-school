package cn.edu.haut.campus.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.campusservices.CampusServiceProviderRegistry
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusMapProviderRegistry
import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicSystemProfile
import cn.edu.haut.campus.core.model.CapabilityEvidence
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.CapabilityState
import cn.edu.haut.campus.core.model.CapabilityStatus
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.protocol.ProtocolGatewayRegistry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SCHOOL_ACADEMIC_CAPABILITIES = listOf(
    CapabilityKind.TIMETABLE_READ,
    CapabilityKind.GRADES_READ,
    CapabilityKind.EXAMS_READ,
    CapabilityKind.ACADEMIC_CALENDAR_READ,
    CapabilityKind.ACADEMIC_TERMS_READ,
    CapabilityKind.GRADE_DETAIL_READ,
    CapabilityKind.TRAINING_PLAN_READ,
    CapabilityKind.SELECTION_QUERY,
    CapabilityKind.SELECTION_WRITE
)

/**
 * 学校目录、身份管理与逐能力证据浏览。身份操作交给宿主的账号协调器，不直接改写课表。
 * 页面不发送学校网络请求；新建身份默认为离线数据，切换由宿主生命周期完成。
 */
@Composable
fun SchoolCenterScreen(
    onBack: () -> Unit,
    directory: SchoolDirectory = remember { SchoolDirectory.builtIn() },
    onOpenOfflineImporter: (() -> Unit)? = null,
    onSelectSchool: ((SchoolProfile) -> Unit)? = null,
    sessions: AccountSessionCoordinator? = null,
    activeSession: ActiveSession? = null,
    onSwitchProfile: ((String) -> Unit)? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSchoolId by rememberSaveable { mutableStateOf<String?>(null) }
    var showContribution by rememberSaveable { mutableStateOf(false) }
    var showCreateProfile by rememberSaveable { mutableStateOf(false) }
    var creatingProfile by remember { mutableStateOf(false) }
    var profileFeedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val profiles = sessions?.profiles?.collectAsState(initial = emptyList())?.value.orEmpty()
    val selectedSchool = directory.schools.firstOrNull { it.id.value == selectedSchoolId }
    val matches = remember(directory, query) { directory.search(query) }
    val navigateBack = {
        when {
            showContribution -> showContribution = false
            selectedSchoolId != null -> selectedSchoolId = null
            else -> onBack()
        }
    }
    BackHandler(enabled = showContribution || selectedSchoolId != null, onBack = navigateBack)

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = when {
                showContribution -> "贡献学校适配"
                selectedSchool != null -> "学校详情"
                else -> "学校与能力"
            },
            subtitle = when {
                showContribution -> "从公开入口和脱敏样本开始"
                selectedSchool != null -> selectedSchool.displayName
                else -> "离线目录 · 逐项了解适配进度"
            },
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = navigateBack
                )
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                showContribution -> {
                    item(key = "contribution") { SchoolContributionCard() }
                }
                selectedSchool != null -> {
                    item(key = "school") {
                        SchoolSummaryCard(selectedSchool)
                    }
                    items(selectedSchool.campuses, key = { "campus-${it.id.value}" }) { campus ->
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = campus.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "课程时区：${campus.timeZone.id}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "校区信息来自本地目录；校园地图与教务能力分别核验。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (selectedSchool.academicSystems.isEmpty()) {
                        item(key = "no-academic") {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "尚未提供该校教务适配，可使用手工课表或课表文件导入。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    selectedSchool.academicSystems.forEach { system ->
                        item(key = "system-${system.systemId}") {
                            SchoolSystemAndCapabilitiesCard(
                                system = system,
                                timeZone = selectedSchool.primaryCampus?.timeZone ?: ZoneId.of("Asia/Shanghai")
                            )
                        }
                    }
                    val campusServiceStatuses = buildList {
                        add(
                            CampusMapProviderRegistry.Default.capabilityStatus(
                                selectedSchool.id,
                                selectedSchool.primaryCampus?.id ?: SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID
                            )
                        )
                        listOf(
                            CapabilityKind.LIBRARY_CATALOG,
                            CapabilityKind.LIBRARY_SEATS,
                            CapabilityKind.UTILITIES
                        ).forEach { kind ->
                            add(CampusServiceProviderRegistry.Default.capabilityStatus(selectedSchool.id, kind))
                        }
                    }
                    item(key = "campus-services-card") {
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "校园服务独立提供方",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "地图路网、图书馆检索、座位预约和宿舍水电均作为独立 Provider 按学校核验，未提供适配时不会借用其他学校的楼宇或接口。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            campusServiceStatuses.forEach { status ->
                                XPeachInsetDivider(verticalPadding = 8.dp)
                                SchoolCapabilityRow(
                                    status = status,
                                    timeZone = selectedSchool.primaryCampus?.timeZone ?: ZoneId.of("Asia/Shanghai")
                                )
                            }
                        }
                    }
                    if (onSelectSchool != null) {
                        item(key = "select") {
                            Button(
                                onClick = { onSelectSchool(selectedSchool) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("选择这所学校") }
                        }
                    }
                    if (sessions != null) {
                        item(key = "create-profile") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { showCreateProfile = true },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("为这所学校添加离线身份")
                                }
                                Text(
                                    text = "新身份拥有独立课表与成绩数据，创建后可在身份列表中切换。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    profileFeedback?.let { feedback ->
                        item(key = "profile-feedback") { Text(feedback, style = MaterialTheme.typography.bodyMedium) }
                    }
                    item(key = "contribute") {
                        TextButton(onClick = { showContribution = true }) { Text("补充这所学校的适配信息") }
                    }
                }
                else -> {
                    if (sessions != null) {
                        item(key = "profiles-title") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "我的身份",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "每个身份独立保存课表、学业与设置。切换时会结束当前网页会话。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(profiles, key = { "profile-${it.id}" }) { profile ->
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = profile.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = profile.school.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (profile.id == activeSession?.profileId) {
                                        XPeachStatusDotText(
                                            text = "当前身份",
                                            dotColor = XPeachTheme.readablePrimary
                                        )
                                    } else {
                                        onSwitchProfile?.let { switch ->
                                            TextButton(onClick = { switch(profile.id) }) { Text("切换到此身份") }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "create-custom-profile") {
                            TextButton(onClick = { showCreateProfile = true }) { Text("添加其他学校的离线身份") }
                        }
                        profileFeedback?.let { feedback ->
                            item(key = "profile-feedback") { Text(feedback, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item(key = "directory-intro") {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "当前目录收录 ${directory.schools.size} 所学校。学校详情展示现有实现与核验边界，浏览目录不会切换或清除当前数据。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item(key = "search") {
                        XPeachCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it.take(SchoolDirectory.MAX_QUERY_LENGTH) },
                                label = { Text("搜索学校") },
                                supportingText = { Text("支持中文、英文、拼音和首字母") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (matches.isEmpty()) {
                        item(key = "empty") {
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "还没有找到这所学校",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "可尝试学校全名或别名。目录尚在扩充，未收录学校仍可使用本地课表文件和手工录入。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    onOpenOfflineImporter?.let { openImporter ->
                                        TextButton(onClick = openImporter) { Text("打开导入中心") }
                                    }
                                }
                            }
                        }
                    }
                    items(matches, key = { it.school.id.value }) { match ->
                        XPeachCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedSchoolId = match.school.id.value }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = match.school.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    XPeachStatusDotText(
                                        text = verificationLabel(match.school.verification),
                                        dotColor = XPeachTheme.readablePrimary
                                    )
                                }
                                Text(
                                    text = listOfNotNull(match.school.province, match.school.city).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (match.approximate) "近似匹配，请核对校名与城市" else "查看校区、教务能力与核验说明",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item(key = "protocol-note") {
                        val protocolDescriptors = remember { ProtocolGatewayRegistry.descriptors() }
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "支持的高校教务系统类型",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "已支持 ${protocolDescriptors.size} 类主流高校教务系统（正方、强智、金智、青果、树维、URP 等）的课表、成绩与考试同步：",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                protocolDescriptors.values.forEach { descriptor ->
                                    Text(
                                        text = "• ${protocolLabel(descriptor.protocol)}：支持课表、考试与成绩导入",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { showContribution = true }) { Text("如何适配我的学校") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCreateProfile && sessions != null) {
        CreateOfflineProfileDialog(
            school = selectedSchool,
            busy = creatingProfile,
            feedback = profileFeedback,
            onDismiss = { if (!creatingProfile) showCreateProfile = false },
            onCreate = { school, label ->
                if (!creatingProfile) scope.launch {
                    creatingProfile = true
                    profileFeedback = null
                    try {
                        sessions.createOfflineProfile(school, label)
                        showCreateProfile = false
                        selectedSchoolId = null
                        profileFeedback = "已创建离线身份，可在上方身份列表中切换"
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        profileFeedback = "创建未完成，已有数据保持不变，请稍后重试"
                    } finally {
                        creatingProfile = false
                    }
                }
            }
        )
    }
}

@Composable
private fun CreateOfflineProfileDialog(
    school: SchoolProfile?,
    busy: Boolean,
    feedback: String?,
    onDismiss: () -> Unit,
    onCreate: (SchoolProfile, String) -> Unit
) {
    var schoolName by rememberSaveable(school?.id?.value) { mutableStateOf(school?.displayName.orEmpty()) }
    var profileLabel by rememberSaveable { mutableStateOf("离线身份") }
    val newSchoolId = rememberSaveable { "local-${UUID.randomUUID()}" }
    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加离线身份") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("课表、成绩和设置会保存到独立的本地空间。此操作不登录学校教务，也不请求学校网络。")
                OutlinedTextField(
                    value = schoolName,
                    onValueChange = { schoolName = it.take(80) },
                    enabled = school == null && !busy,
                    label = { Text("学校名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = profileLabel,
                    onValueChange = { profileLabel = it.take(80) },
                    enabled = !busy,
                    label = { Text("身份名称") },
                    supportingText = { Text("例如：本科课表、交流学期；不必填写学号") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (school == null) {
                    Text(
                        text = "未收录学校使用中国标准时间，可先通过手工录入或文件导入使用课表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && schoolName.isNotBlank() && profileLabel.isNotBlank(),
                onClick = {
                    onCreate(
                        school ?: SchoolProfile(SchoolId(newSchoolId), schoolName.trim()),
                        profileLabel.trim()
                    )
                }
            ) { Text(if (busy) "正在创建" else "创建离线身份") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun SchoolSummaryCard(school: SchoolProfile) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = school.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                XPeachStatusDotText(
                    text = verificationLabel(school.verification),
                    dotColor = XPeachTheme.readablePrimary
                )
            }
            Text(
                text = listOfNotNull(school.province, school.city).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            school.verification?.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            school.officialWebsite?.let {
                SelectionContainer {
                    Text(
                        text = "学校官网：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SchoolSystemAndCapabilitiesCard(
    system: AcademicSystemProfile,
    timeZone: ZoneId
) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = protocolLabel(system.protocolFamily),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(
                    text = "教务入口：${system.baseUrl}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "以下能力分别核验。浏览学校目录不会请求教务网络或提交身份信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SCHOOL_ACADEMIC_CAPABILITIES.forEach { kind ->
            XPeachInsetDivider(verticalPadding = 8.dp)
            SchoolCapabilityRow(
                status = system.capability(kind),
                timeZone = timeZone
            )
        }
    }
}

@Composable
private fun SchoolCapabilityRow(status: CapabilityStatus, timeZone: ZoneId) {
    var showEvidence by rememberSaveable(status.kind.name) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = capabilityLabel(status.kind),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (status.providerSupports) {
                XPeachStatusDotText(
                    text = verificationLabel(status.evidence),
                    dotColor = XPeachTheme.readablePrimary
                )
            }
        }
        Text(
            text = capabilityStateLabel(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status.providerSupports) {
            Text(
                text = "学校账号核验：${if (status.verifiedForAccount) "已核验" else "尚未核验"} · 查询授权：${if (status.userConsent) "已授权" else "尚未授权"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        status.evidence?.let { evidence ->
            TextButton(onClick = { showEvidence = !showEvidence }) {
                Text(if (showEvidence) "收起核验依据" else "查看核验依据")
            }
            if (showEvidence) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        evidence.note?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        evidence.sourceReference?.let {
                            Text(
                                text = "来源：$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        evidence.evidenceId?.let {
                            Text(
                                text = "证据编号：$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = evidence.verifiedAt?.let {
                                "核验日期：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(timeZone).format(it)}"
                            } ?: "尚未提供设备或学校核验日期",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private const val SCHOOL_CONTRIBUTION_TEMPLATE = """学校名称：
所在省市：
校区名称：
学校公开官网：
公开教务入口：
希望支持的能力：
公开来源与许可说明：
验证日期与设备（若已取得授权）：

请勿填写学号、姓名、密码、Cookie、票据、成绩、考场座号或带个人参数的链接。
响应样本必须先脱敏；提交前请再次检查附件与截图。
"""

@Composable
private fun SchoolContributionCard() {
    val context = LocalContext.current
    var copyFeedback by remember { mutableStateOf<String?>(null) }
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "先提供公开资料",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "整理学校与校区、官方入口、希望支持的功能及公开来源。贡献资料不代表该学校已经适配，学校级验证仍需要授权账户和设备证据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "提交前移除身份信息、会话令牌和个人学业数据。请在项目仓库的议题页面检查内容后手工提交。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "项目仓库：https://github.com/yellowpeachxgp/day-in-school",
                        style = MaterialTheme.typography.bodySmall,
                        color = XPeachTheme.readablePrimary
                    )
                    Text(
                        text = SCHOOL_CONTRIBUTION_TEMPLATE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("学校适配资料模板", SCHOOL_CONTRIBUTION_TEMPLATE))
                        copyFeedback = "已复制空白模板，请填写公开资料"
                    } else {
                        copyFeedback = "暂时无法使用剪贴板，可长按上方模板复制"
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) { Text("复制空白资料模板") }
            copyFeedback?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = XPeachTheme.readablePrimary
                )
            }
        }
    }
}

private fun verificationLabel(evidence: CapabilityEvidence?): String = when (evidence?.level) {
    VerificationLevel.SOURCE_CONFIRMED -> "源码已确认 · 学校待核验"
    VerificationLevel.MOCK_TESTED -> "协议测试通过 · 学校待核验"
    VerificationLevel.DEVICE_TESTED -> "设备已验证 · 学校待核验"
    VerificationLevel.SCHOOL_VERIFIED -> "学校能力已核验"
    VerificationLevel.DISTRIBUTION_READY -> "发行检查通过"
    null -> "尚无核验依据"
}

private fun capabilityStateLabel(status: CapabilityStatus): String = when (status.state) {
    CapabilityState.ENABLED -> "当前账号可用"
    CapabilityState.NEEDS_USER_CONSENT -> "使用前需要你的查询授权"
    CapabilityState.NEEDS_USER_LOGIN -> "需要登录学校教务"
    CapabilityState.NEEDS_VERIFICATION -> "已有实现，尚未完成当前学校账号核验"
    CapabilityState.NOT_OPEN -> "学校暂未开放"
    CapabilityState.UNSUPPORTED -> "当前适配未提供此能力"
    CapabilityState.REVOKED -> "授权已撤销"
}

private fun capabilityLabel(kind: CapabilityKind): String = when (kind) {
    CapabilityKind.ACADEMIC_TERMS_READ -> "学期目录"
    CapabilityKind.ACADEMIC_CALENDAR_READ -> "学期校历"
    CapabilityKind.TIMETABLE_READ -> "课表查询"
    CapabilityKind.GRADES_READ -> "成绩查询"
    CapabilityKind.GRADE_DETAIL_READ -> "成绩分项详情"
    CapabilityKind.EXAMS_READ -> "考试安排"
    CapabilityKind.TRAINING_PLAN_READ -> "培养方案"
    CapabilityKind.SELECTION_QUERY -> "选课目录"
    CapabilityKind.SELECTION_WRITE -> "选退课提交"
    CapabilityKind.CAMPUS_MAP -> "校园地图"
    CapabilityKind.CAMPUS_BUILDINGS -> "校区楼宇"
    CapabilityKind.LIBRARY_CATALOG -> "馆藏查询"
    CapabilityKind.LIBRARY_SEATS -> "图书馆选座"
    CapabilityKind.UTILITIES -> "宿舍水电"
    CapabilityKind.ROOM_FINDER -> "空教室参考"
    CapabilityKind.COMMUNITY -> "校园社群"
}

private fun protocolLabel(protocol: AcademicProtocolFamily): String = when (protocol) {
    AcademicProtocolFamily.ZF_MODERN -> "新正方教务"
    AcademicProtocolFamily.ZF_LEGACY -> "旧正方教务"
    AcademicProtocolFamily.QZ_MODERN -> "新强智教务"
    AcademicProtocolFamily.QZ_LEGACY -> "旧强智教务"
    AcademicProtocolFamily.JINZHI -> "金智教务"
    AcademicProtocolFamily.CHENGFANG -> "乘方教务"
    AcademicProtocolFamily.KINGOSOFT -> "青果教务"
    AcademicProtocolFamily.EAMS -> "树维教务"
    AcademicProtocolFamily.CHAOXING_ACADEMIC -> "超星智慧教务"
}
