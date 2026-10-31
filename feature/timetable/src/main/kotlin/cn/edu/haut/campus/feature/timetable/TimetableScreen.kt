package cn.edu.haut.campus.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("MM/dd")
private val PERIOD_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val term by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val currentActualWeek = remember(term, today, calendarOverrides) {
        term?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides).teachingWeek } ?: 8
    }

    var selectedWeek by remember { mutableIntStateOf(currentActualWeek) }
    LaunchedEffect(currentActualWeek) {
        selectedWeek = currentActualWeek
    }

    var editingOccurrence by remember { mutableStateOf<ClassOccurrence?>(null) }
    var showAddCourseDialog by remember { mutableStateOf(false) }

    val weekOccurrencesMap = remember(
        selectedWeek,
        term,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings
    ) {
        val activeTerm = term ?: return@remember emptyMap<LocalDate, List<ClassOccurrence>>()
        ScheduleEngine.resolveOccurrencesForTeachingWeek(
            teachingWeek = selectedWeek,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (selectedWeek > 1) selectedWeek-- }
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "上一周")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "第 $selectedWeek 教学周" + if (selectedWeek == currentActualWeek) " (本周)" else "",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = term?.displayName ?: "河南工业大学",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                val maxW = term?.weekCount ?: 20
                                if (selectedWeek < maxW) selectedWeek++
                            }
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "下一周")
                        }
                    }
                },
                actions = {
                    if (selectedWeek != currentActualWeek) {
                        AssistChip(
                            onClick = { selectedWeek = currentActualWeek },
                            label = { Text("回本周") }
                        )
                    }
                    IconButton(onClick = { showAddCourseDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "手动添加课程")
                    }
                    IconButton(onClick = onNavigateToImporter) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "教务导入")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val weekDates = remember(term, selectedWeek) {
                term?.let { TeachingWeekCalculator.datesOfTeachingWeek(it, selectedWeek) }
                    ?: (0L..6L).map { today.plusDays(it) }
            }

            // 顶部星期与日期栏（周一至周日）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.width(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${selectedWeek}周",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                for (date in weekDates) {
                    val isToday = date == today
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = date.dayOfWeek.ChineseLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = date.format(SHORT_DATE_FMT),
                            fontSize = 10.sp,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 纵向 1-10 节周课表网格
            val slotHeightDp = 62.dp
            val maxPeriods = periodTimes.maxOfOrNull { it.periodNo }?.coerceAtLeast(10) ?: 10
            val periodByNo = periodTimes.associateBy { it.periodNo }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
            ) {
                // 左侧节次与起止时刻栏
                Column(
                    modifier = Modifier
                        .width(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                ) {
                    for (p in 1..maxPeriods) {
                        val pt = periodByNo[p]
                        Column(
                            modifier = Modifier
                                .height(slotHeightDp)
                                .fillMaxWidth()
                                .border(
                                    width = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "$p",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (pt != null) {
                                Text(
                                    text = pt.startLocalTime.format(PERIOD_TIME_FMT),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = pt.endLocalTime.format(PERIOD_TIME_FMT),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 周一至周日 7 列排课块（跨节次纵向合并、冲突横向并列）
                for (date in weekDates) {
                    val dayOccurrences = weekOccurrencesMap[date].orEmpty()
                    val isToday = date == today

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(slotHeightDp * maxPeriods)
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                    ) {
                        // 背景网格线
                        Column(modifier = Modifier.fillMaxSize()) {
                            repeat(maxPeriods) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(slotHeightDp)
                                        .border(
                                            width = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                        )
                                )
                            }
                        }

                        // 课程块
                        for (occ in dayOccurrences) {
                            val span = (occ.endPeriod - occ.startPeriod + 1).coerceAtLeast(1)
                            val topOffset = slotHeightDp * (occ.startPeriod - 1)
                            val blockHeight = slotHeightDp * span
                            val baseColor = Color(occ.colorArgb)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = topOffset)
                                    .height(blockHeight)
                                    .padding(1.5.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(baseColor.copy(alpha = 0.88f))
                                    .clickable { editingOccurrence = occ }
                                    .padding(3.dp)
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = buildString {
                                            if (occ.conflictColumnCount > 1) append("[冲突]")
                                            if (occ.userModified) append("★")
                                            append(occ.courseName)
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 13.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "@${occ.rawRoom}",
                                        color = Color.White.copy(alpha = 0.92f),
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 点击课程：查看详情或手动编辑（编辑后标记 userModified = true，不被下次导入覆盖）
    val occToEdit = editingOccurrence
    if (occToEdit != null) {
        val targetCourse = courses.firstOrNull { it.id == occToEdit.courseId }
        val targetRule = rules.firstOrNull { it.id == occToEdit.ruleId }

        var editedName by remember(occToEdit) { mutableStateOf(occToEdit.courseName) }
        var editedRoom by remember(occToEdit) { mutableStateOf(occToEdit.rawRoom) }
        var editedTeacher by remember(occToEdit) { mutableStateOf(occToEdit.teacher.orEmpty()) }
        var editedWeeksText by remember(targetRule) {
            mutableStateOf(targetRule?.let { WeeksSetParser.format(it.weeks) } ?: "1-16周")
        }
        var selectedColor by remember(occToEdit) { mutableLongStateOf(occToEdit.colorArgb) }

        val palette = listOf(
            0xFF2563EB, 0xFFEA580C, 0xFF059669, 0xFF7C3AED,
            0xFFD97706, 0xFF0891B2, 0xFF16A34A, 0xFFDC2626
        )

        AlertDialog(
            onDismissRequest = { editingOccurrence = null },
            title = { Text("课程详情与编辑") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("课程名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedRoom,
                        onValueChange = { editedRoom = it },
                        label = { Text("上课教室（点击下方可导航）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedTeacher,
                        onValueChange = { editedTeacher = it },
                        label = { Text("任课教师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedWeeksText,
                        onValueChange = { editedWeeksText = it },
                        label = { Text("上课周次（如 1-16周、1-15单周、2,4,6-12周）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        for (colorHex in palette) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex))
                                    .border(
                                        width = if (selectedColor == colorHex) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = colorHex }
                            )
                        }
                    }

                    Text(
                        text = "提示：手动保存后将标记为「用户已修改」，下次教务导入时会自动保护您的修改不被静默覆盖。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = {
                            editingOccurrence = null
                            onNavigateToMapWithRoom(editedRoom)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("在莲花街校区地图中查看该教室")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (targetCourse != null && targetRule != null) {
                                val parsedWeeks = WeeksSetParser.parse(editedWeeksText).ifEmpty { targetRule.weeks }
                                repository.saveUserCourseEdit(
                                    course = targetCourse.copy(
                                        name = editedName.trim().ifEmpty { targetCourse.name },
                                        teacher = editedTeacher.trim().ifEmpty { null },
                                        colorArgb = selectedColor
                                    ),
                                    rule = targetRule.copy(
                                        rawRoom = editedRoom.trim().ifEmpty { targetRule.rawRoom },
                                        weeks = parsedWeeks
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            }
                            editingOccurrence = null
                        }
                    }
                ) {
                    Text("保存修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingOccurrence = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 手动新增课程弹窗
    if (showAddCourseDialog) {
        var newName by remember { mutableStateOf("") }
        var newRoom by remember { mutableStateOf("1号教学楼1106") }
        var newTeacher by remember { mutableStateOf("") }
        var newWeekday by remember { mutableIntStateOf(today.dayOfWeek.value) }
        var newStartPeriod by remember { mutableStateOf("1") }
        var newEndPeriod by remember { mutableStateOf("2") }
        var newWeeks by remember { mutableStateOf("1-16周") }

        AlertDialog(
            onDismissRequest = { showAddCourseDialog = false },
            title = { Text("手动添加课程 / 补课") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("课程名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoom,
                        onValueChange = { newRoom = it },
                        label = { Text("教室（如 2号教学楼2204）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTeacher,
                        onValueChange = { newTeacher = it },
                        label = { Text("教师（选填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newWeekday.toString(),
                            onValueChange = { newWeekday = (it.toIntOrNull() ?: 1).coerceIn(1, 7) },
                            label = { Text("星期(1-7)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newStartPeriod,
                            onValueChange = { newStartPeriod = it },
                            label = { Text("开始节") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newEndPeriod,
                            onValueChange = { newEndPeriod = it },
                            label = { Text("结束节") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = newWeeks,
                        onValueChange = { newWeeks = it },
                        label = { Text("周次（如 1-16周 或 第8周）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activeTermId = term?.id ?: "2026-2027-1"
                        if (newName.isNotBlank()) {
                            scope.launch {
                                val sp = (newStartPeriod.toIntOrNull() ?: 1).coerceIn(1, 10)
                                val ep = (newEndPeriod.toIntOrNull() ?: sp).coerceIn(sp, 10)
                                val weeks = WeeksSetParser.parse(newWeeks).ifEmpty { setOf(selectedWeek) }
                                repository.addManualCourseWithRule(
                                    termId = activeTermId,
                                    name = newName.trim(),
                                    teacher = newTeacher.trim().ifBlank { null },
                                    rawRoom = newRoom.trim().ifBlank { "待定教室" },
                                    weekday = DayOfWeek.of(newWeekday),
                                    startPeriod = sp,
                                    endPeriod = ep,
                                    weeks = weeks,
                                    colorArgb = 0xFF059669
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                showAddCourseDialog = false
                            }
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCourseDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
