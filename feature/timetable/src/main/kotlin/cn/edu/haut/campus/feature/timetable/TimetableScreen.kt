package cn.edu.haut.campus.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "第 $selectedWeek 周",
            subtitle = term?.displayName ?: "河南工业大学",
            badgeText = if (selectedWeek == currentActualWeek) "本周" else null,
            actions = {
                if (selectedWeek != currentActualWeek) {
                    XPeachFilterPill(
                        label = "本周",
                        selected = true,
                        onClick = { selectedWeek = currentActualWeek }
                    )
                }
                XPeachIconButton(
                    icon = Icons.Default.ChevronLeft,
                    contentDescription = "上一周",
                    onClick = { if (selectedWeek > 1) selectedWeek-- }
                )
                XPeachIconButton(
                    icon = Icons.Default.ChevronRight,
                    contentDescription = "下一周",
                    onClick = {
                        val maxW = term?.weekCount ?: 20
                        if (selectedWeek < maxW) selectedWeek++
                    }
                )
                XPeachIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "添加课程",
                    onClick = { showAddCourseDialog = true }
                )
                XPeachIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = "导入",
                    onClick = onNavigateToImporter
                )
            }
        )

        val weekDates = remember(term, selectedWeek) {
            term?.let { TeachingWeekCalculator.datesOfTeachingWeek(it, selectedWeek) }
                ?: (0L..6L).map { today.plusDays(it) }
        }

        // 极简课表卡片容器（去重框线，柔和圆角色块）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 顶部星期栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.width(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${dateMonthLabel(weekDates.firstOrNull())}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                for (date in weekDates) {
                    val isToday = date == today
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = date.dayOfWeek.ChineseLabel.replace("周", ""),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = date.format(SHORT_DATE_FMT),
                            fontSize = 10.sp,
                            color = if (isToday) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )

            // 1-10 节无竖框轻盈网格
            val slotHeightDp = 58.dp
            val maxPeriods = periodTimes.maxOfOrNull { it.periodNo }?.coerceAtLeast(10) ?: 10
            val periodByNo = periodTimes.associateBy { it.periodNo }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier.width(38.dp)
                ) {
                    for (p in 1..maxPeriods) {
                        val pt = periodByNo[p]
                        Column(
                            modifier = Modifier
                                .height(slotHeightDp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "$p",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (pt != null) {
                                Text(
                                    text = pt.startLocalTime.format(PERIOD_TIME_FMT),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                for (date in weekDates) {
                    val dayOccurrences = weekOccurrencesMap[date].orEmpty()
                    val isToday = date == today

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(slotHeightDp * maxPeriods)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                else Color.Transparent
                            )
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            repeat(maxPeriods) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(slotHeightDp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(0.5.dp)
                                            .align(Alignment.BottomCenter)
                                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    )
                                }
                            }
                        }

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
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(baseColor.copy(alpha = 0.14f))
                                    .clickable { editingOccurrence = occ }
                            ) {
                                // 顶部柔和色条 + 紧凑文字排布
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .background(baseColor.copy(alpha = 0.8f))
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = occ.courseName,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            lineHeight = 13.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = occ.rawRoom,
                                            color = baseColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
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
    }

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
            0xFF0072BC, 0xFF0284C7, 0xFF0D9488, 0xFF16A34A,
            0xFF4F46E5, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48
        )

        AlertDialog(
            onDismissRequest = { editingOccurrence = null },
            title = { Text("课程编辑") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${occToEdit.startTime.format(PERIOD_TIME_FMT)}–${occToEdit.endTime.format(PERIOD_TIME_FMT)} (${occToEdit.periodRangeText})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                        label = { Text("教室") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedTeacher,
                        onValueChange = { editedTeacher = it },
                        label = { Text("教师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedWeeksText,
                        onValueChange = { editedWeeksText = it },
                        label = { Text("周次（如 1-16周）") },
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
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex))
                                    .border(
                                        width = if (selectedColor == colorHex) 2.5.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = colorHex }
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            editingOccurrence = null
                            onNavigateToMapWithRoom(editedRoom)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("在地图中查看教室位置", maxLines = 1, softWrap = false)
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
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingOccurrence = null }) {
                    Text("取消")
                }
            }
        )
    }

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
            title = { Text("添加课程") },
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
                        label = { Text("教室") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTeacher,
                        onValueChange = { newTeacher = it },
                        label = { Text("教师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newWeekday.toString(),
                            onValueChange = { newWeekday = (it.toIntOrNull() ?: 1).coerceIn(1, 7) },
                            label = { Text("星期") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newStartPeriod,
                            onValueChange = { newStartPeriod = it },
                            label = { Text("起始节") },
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
                        label = { Text("周次") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activeTermId = term?.id ?: "2025-2026-2"
                        if (newName.isNotBlank()) {
                            scope.launch {
                                val sp = (newStartPeriod.toIntOrNull() ?: 1).coerceIn(1, 10)
                                val ep = (newEndPeriod.toIntOrNull() ?: sp).coerceIn(sp, 10)
                                val weeks = WeeksSetParser.parse(newWeeks).ifEmpty { setOf(selectedWeek) }
                                repository.addManualCourseWithRule(
                                    termId = activeTermId,
                                    name = newName.trim(),
                                    teacher = newTeacher.trim().ifBlank { null },
                                    rawRoom = newRoom.trim().ifBlank { "待定" },
                                    weekday = DayOfWeek.of(newWeekday),
                                    startPeriod = sp,
                                    endPeriod = ep,
                                    weeks = weeks,
                                    colorArgb = 0xFF0072BC
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                showAddCourseDialog = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
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

private fun dateMonthLabel(date: LocalDate?): String =
    date?.let { "${it.monthValue}月" } ?: ""

