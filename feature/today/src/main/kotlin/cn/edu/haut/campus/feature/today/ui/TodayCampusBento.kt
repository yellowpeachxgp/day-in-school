package cn.edu.haut.campus.feature.today.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.database.isPendingPickup
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ClassEndMealRecommendation
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

internal data class HomeModularCardSpec(
    val id: String,
    val title: String,
    val badgeText: String,
    val subtitle: String,
    val highlighted: Boolean = false,
    val actionPillText: String? = null,
    val onActionPillClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

@Composable
internal fun TodayCampusBentoSection(
    examSchedules: List<ExamScheduleItem>,
    activeTermId: String?,
    selectedDate: LocalDate,
    todayDate: LocalDate,
    currentNow: ZonedDateTime,
    teachingWeek: Int,
    rules: List<CourseScheduleRule>,
    resolvedDateOccurrences: List<cn.edu.haut.campus.core.model.ClassOccurrence> = emptyList(),
    hasStudyScheduleBasis: Boolean = false,
    studyBasisReasonLabel: String? = null,
    selectedCampusId: String = "",
    activeTimeTableId: String? = null,
    periodTimes: List<cn.edu.haut.campus.core.model.PeriodTime> = emptyList(),
    activeFocusSession: cn.edu.haut.campus.core.model.FocusSessionDomain?,
    completedFocusSessions: List<cn.edu.haut.campus.core.model.FocusSessionDomain>,
    onlineRooms: List<cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain>,
    utilityMeters: List<cn.edu.haut.campus.core.model.UtilityMeterDomain>,
    utilityReadings: List<cn.edu.haut.campus.core.model.UtilityReadingDomain>,
    parcelItems: List<cn.edu.haut.campus.core.database.ParcelItemEntity>,
    bookingPlans: List<cn.edu.haut.campus.core.database.LibraryBookingPlanEntity>,
    cardOrder: List<String>,
    hiddenCards: Set<String>,
    fullWidthCards: Set<String>,
    campusClubsCount: Int = PresetCampusCommunityCatalog.defaultCampusClubs.size,
    competitionsCount: Int = PresetCampusCommunityCatalog.defaultCompetitions.size,
    mealRecommendation: ClassEndMealRecommendation,
    onOpenDiningDialog: () -> Unit,
    onOpenCustomizer: () -> Unit,
    onNavigateToGrades: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit,
    onNavigateToFocus: () -> Unit,
    onNavigateToElectricity: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit,
    onNavigateToEmptyClassrooms: () -> Unit,
    onNavigateToCommunity: () -> Unit,
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToMoments: () -> Unit = onNavigateToCommunity,
    onNavigateToParcelHub: () -> Unit
) {
    // 顶部板块栏：左标题 + 右侧「编辑」按钮
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "常用功能",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        XPeachFilterPill(
            label = "编辑",
            selected = false,
            onClick = onOpenCustomizer
        )
    }

    val currentTermExams = remember(examSchedules, activeTermId) {
        val effectiveTermId = activeTermId?.takeIf { it.isNotBlank() }
            ?: cn.edu.haut.campus.core.model.AcademicTermResolver.defaultTermId()
        examSchedules.filter { it.termId == effectiveTermId }
    }

    val nearestExam = remember(currentTermExams, selectedDate, todayDate) {
        val sameDate = currentTermExams.firstOrNull { it.examDate == selectedDate }
        if (sameDate != null) {
            sameDate
        } else {
            currentTermExams.filter { it.examDate == null || !it.examDate!!.isBefore(todayDate) }
                .minByOrNull { it.examDate ?: LocalDate.of(2099, 12, 31) }
        }
    }

    val todayDoneMins = remember(completedFocusSessions, todayDate) {
        completedFocusSessions.sumOf { s ->
            val doneMs = s.completedAtEpochMs ?: s.expectedEndAtEpochMs
            val d = Instant.ofEpochMilli(doneMs).atZone(HAUT_ZONE_ID).toLocalDate()
            if (d == todayDate) s.durationMinutes else 0
        }
    }
    val runningFocus = activeFocusSession
    val focusBadge = if (runningFocus != null) "进行中" else "番茄钟"
    val focusSub = if (runningFocus != null) {
        val remMin = ((runningFocus.remainingMillis(System.currentTimeMillis()) + 59_999L) / 60_000L)
            .coerceAtLeast(1L)
        "剩余约 $remMin 分钟"
    } else if (todayDoneMins > 0) {
        "今日已专注 $todayDoneMins 分钟"
    } else {
        "番茄钟倒计时"
    }

    val (elecBadge, elecSub) = remember(onlineRooms, utilityMeters, utilityReadings) {
        if (onlineRooms.isNotEmpty()) {
            val room = onlineRooms.first()
            val firstDev = decodeOnlineDevicesJson(room.devicesJson).firstOrNull()
            val b = firstDev?.balanceRaw?.takeIf { it.isNotBlank() } ?: "已绑定"
            b to room.roomName
        } else if (utilityMeters.isNotEmpty()) {
            val meter = utilityMeters.first()
            val latest = utilityReadings.filter { it.meterId == meter.id }
                .maxByOrNull { it.recordedAtEpochMs }
            val b = if (latest != null) "${latest.value}${meter.unit}" else "记录"
            b to "${meter.roomName} · ${meter.name}"
        } else {
            "电费" to "剩余电量与用电记录"
        }
    }

    val (currentSlotLabel, studyAvailability) = remember(
        currentNow,
        selectedDate,
        resolvedDateOccurrences,
        selectedCampusId,
        rules,
        periodTimes,
        activeTimeTableId
    ) {
        val resolution = CampusStudyRoomCatalog.resolveActiveSlotForTimeTable(
            nowTime = currentNow.toLocalTime(),
            periodTimes = periodTimes,
            timeTableId = activeTimeTableId
        )
        val effectiveCampus = selectedCampusId.trim().ifEmpty {
            cn.edu.haut.campus.core.domain.ClassroomBuildingResolver.detectCampusHint(
                (resolvedDateOccurrences.map { it.rawRoom } + rules.map { it.rawRoom })
            )?.value ?: "lianhuajie"
        }
        val summary = CampusStudyRoomCatalog.computeAvailabilityForOccurrences(
            occurrences = resolvedDateOccurrences,
            slot = resolution.slot,
            campusId = effectiveCampus
        )
        resolution.displayLabel to summary
    }

    val examSpec = if (nearestExam != null) {
        val daysLeft = nearestExam.examDate?.let { ChronoUnit.DAYS.between(todayDate, it) }
        val countdownBadge = when {
            daysLeft == null -> "待定"
            daysLeft == 0L -> "今天考试"
            daysLeft == 1L -> "明天考试"
            daysLeft > 1L -> "还有 ${daysLeft} 天"
            else -> "考试安排"
        }
        HomeModularCardSpec(
            id = "EXAM_SUMMARY",
            title = nearestExam.courseName,
            badgeText = countdownBadge,
            subtitle = "${nearestExam.rawTimeText} · ${nearestExam.rawRoom}",
            highlighted = daysLeft == 0L || daysLeft == 1L,
            actionPillText = "去考场",
            onActionPillClick = { onNavigateToMapWithRoom(nearestExam.rawRoom) },
            onClick = onNavigateToGrades
        )
    } else {
        HomeModularCardSpec(
            id = "EXAM_SUMMARY",
            title = "考试与成绩",
            badgeText = "学业",
            subtitle = when {
                currentTermExams.isNotEmpty() -> "本学期 ${currentTermExams.size} 场考试 · 成绩与绩点"
                examSchedules.isNotEmpty() -> "本学期暂未发布考试 · 历史 ${examSchedules.size} 场"
                else -> "本学期暂未发布考试 · 成绩与绩点"
            },
            onClick = onNavigateToGrades
        )
    }

    val specsById = mapOf(
        "EXAM_SUMMARY" to examSpec,
        "STUDY_FOCUS" to HomeModularCardSpec(
            id = "STUDY_FOCUS",
            title = "自习专注",
            badgeText = focusBadge,
            subtitle = focusSub,
            highlighted = runningFocus != null,
            onClick = onNavigateToFocus
        ),
        "DORM_ELECTRICITY" to HomeModularCardSpec(
            id = "DORM_ELECTRICITY",
            title = "宿舍电费",
            badgeText = elecBadge,
            subtitle = elecSub,
            onClick = onNavigateToElectricity
        ),
        "LIBRARY_SEAT" to run {
            val activePlan = bookingPlans.firstOrNull {
                it.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "CONFIRMED")
            }
            if (activePlan != null) {
                val cleanArea = activePlan.preferredAreaName.cleanUiText() ?: "自习区"
                val seatNo = activePlan.preferredSeatLabel.cleanUiText()?.split(",")?.firstOrNull()?.trim() ?: "自动选座"
                HomeModularCardSpec(
                    id = "LIBRARY_SEAT",
                    title = "座位预约",
                    badgeText = if (activePlan.status == "CONFIRMED") "已预约" else "待预约",
                    subtitle = "$cleanArea · ${seatNo}号 (${activePlan.targetLocalTime})",
                    highlighted = true,
                    onClick = onNavigateToLibrarySeats
                )
            } else {
                HomeModularCardSpec(
                    id = "LIBRARY_SEAT",
                    title = "座位预约",
                    badgeText = "云端抢座",
                    subtitle = "云端准点抢座与网页选座",
                    onClick = onNavigateToLibrarySeats
                )
            }
        },
        HomeCardModule.PARCEL_HUB.id to run {
            val waitingParcels = parcelItems.filter { it.isPendingPickup() }
            val waitingCount = waitingParcels.size
            val firstWaiting = waitingParcels.firstOrNull()
            HomeModularCardSpec(
                id = HomeCardModule.PARCEL_HUB.id,
                title = HomeCardModule.PARCEL_HUB.title,
                badgeText = if (waitingCount > 0) "待取 $waitingCount" else "取件码",
                subtitle = if (firstWaiting != null) {
                    val codeLabel = firstWaiting.pickupCode.cleanUiText() ?: "待取件"
                    val stationOrTitle = firstWaiting.stationName.cleanUiText()
                        ?: firstWaiting.itemTitle.cleanUiText()
                        ?: firstWaiting.trackingNumber
                    "$codeLabel · $stationOrTitle"
                } else {
                    "取件码汇总与查件"
                },
                highlighted = waitingCount > 0,
                onClick = onNavigateToParcelHub
            )
        },
        "LIBRARY_CATALOG" to HomeModularCardSpec(
            id = "LIBRARY_CATALOG",
            title = "馆藏检索",
            badgeText = "馆藏",
            subtitle = "书目搜索与在馆状态",
            onClick = onNavigateToLibraryCatalog
        ),
        "EMPTY_CLASSROOM" to HomeModularCardSpec(
            id = "EMPTY_CLASSROOM",
            title = HomeCardModule.EMPTY_CLASSROOM.title,
            badgeText = currentSlotLabel,
            // 严格依据判定：无本学期可展开排课输入时只显示名录规模，绝不以 N/N 冒充"全部无冲突"；
            // 副标题诚实带原因态（尚无依据 / 作息表不完整 / 依据不完整）
            subtitle = if (hasStudyScheduleBasis) {
                "${studyAvailability.campusShortLabel} ${studyAvailability.freeRoomsCount}/${studyAvailability.totalCandidateRoomsCount} 间个人课表无冲突（参考）"
            } else {
                "${studyAvailability.campusShortLabel} ${studyAvailability.totalCandidateRoomsCount} 间自习教室名录（${studyBasisReasonLabel ?: "尚无课表依据"}）"
            },
            onClick = onNavigateToEmptyClassrooms
        ),
        HomeCardModule.CAMPUS_COMMUNITY.id to HomeModularCardSpec(
            id = HomeCardModule.CAMPUS_COMMUNITY.id,
            title = HomeCardModule.CAMPUS_COMMUNITY.title,
            badgeText = "$campusClubsCount 社团",
            subtitle = "社团 · 失物招领 · 二手书",
            actionPillText = "失物/二手",
            onActionPillClick = onNavigateToMoments,
            onClick = onNavigateToCommunity
        ),
        HomeCardModule.COMPETITION_GUIDE.id to HomeModularCardSpec(
            id = HomeCardModule.COMPETITION_GUIDE.id,
            title = HomeCardModule.COMPETITION_GUIDE.title,
            badgeText = "$competitionsCount 赛事",
            subtitle = HomeCardModule.COMPETITION_GUIDE.subtitle,
            actionPillText = "查赛事",
            onActionPillClick = onNavigateToCompetitions,
            onClick = onNavigateToCompetitions
        ),
        HomeCardModule.CAMPUS_DINING.id to HomeModularCardSpec(
            id = HomeCardModule.CAMPUS_DINING.id,
            title = HomeCardModule.CAMPUS_DINING.title,
            badgeText = mealRecommendation.primaryCandidate.profile.shortName,
            subtitle = mealRecommendation.primaryCandidate.recommendedDishes.take(2).joinToString(" / "),
            highlighted = mealRecommendation.isWithinOneHourBeforeEnd,
            actionPillText = "去导航",
            onActionPillClick = { onNavigateToMapWithRoom(mealRecommendation.primaryCandidate.profile.name) },
            onClick = onOpenDiningDialog
        )
    )

    val visibleSpecs = cardOrder
        .filter { it !in hiddenCards }
        .mapNotNull { specsById[it] }

    if (visibleSpecs.isEmpty()) {
        XPeachWell(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCustomizer)
        ) {
            Text(
                text = "卡片已全部隐藏，点击恢复",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        // 智能模块化排版：通栏卡片独占一行，相邻双列卡片自动两两配对成双列 Bento 网格
        var idx = 0
        while (idx < visibleSpecs.size) {
            val current = visibleSpecs[idx]
            val isCurrentFull = current.id in fullWidthCards
            if (isCurrentFull) {
                FullWidthModularCard(spec = current)
                idx += 1
            } else {
                val next = visibleSpecs.getOrNull(idx + 1)
                if (next != null && next.id !in fullWidthCards) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BentoModuleTile(
                            modifier = Modifier.weight(1f),
                            title = current.title,
                            badgeText = current.badgeText,
                            subtitle = current.subtitle,
                            highlighted = current.highlighted,
                            onClick = current.onClick
                        )
                        BentoModuleTile(
                            modifier = Modifier.weight(1f),
                            title = next.title,
                            badgeText = next.badgeText,
                            subtitle = next.subtitle,
                            highlighted = next.highlighted,
                            onClick = next.onClick
                        )
                    }
                    idx += 2
                } else {
                    FullWidthModularCard(spec = current)
                    idx += 1
                }
            }
        }
    }
}

@Composable
internal fun FullWidthModularCard(spec: HomeModularCardSpec) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (spec.highlighted) {
            XPeachTheme.primaryAccentCardColor
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (spec.highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        onClick = spec.onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (spec.highlighted) {
                        XPeachStatusBadge(
                            text = spec.badgeText,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!spec.highlighted) {
                        XPeachStatusDotText(text = spec.badgeText)
                    }
                }
                Text(
                    text = spec.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (spec.actionPillText != null && spec.onActionPillClick != null) {
                XPeachFilterPill(
                    label = spec.actionPillText,
                    selected = spec.highlighted,
                    onClick = spec.onActionPillClick
                )
            }
        }
    }
}

@Composable
internal fun HomeCardLayoutCustomizerDialog(
    currentOrder: List<String>,
    currentHidden: Set<String>,
    currentFullWidth: Set<String>,
    onSave: (List<String>, Set<String>, Set<String>) -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    var workingOrder by remember(currentOrder) {
        mutableStateOf(HomeCardModule.normalizeOrderIds(currentOrder))
    }
    var workingHidden by remember(currentHidden) { mutableStateOf(currentHidden) }
    var workingFullWidth by remember(currentFullWidth) { mutableStateOf(currentFullWidth) }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("卡片管理", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "调整顺序、尺寸与显示状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                workingOrder.forEachIndexed { index, cardId ->
                    val title = HomeCardModule.fromId(cardId)?.title ?: cardId
                    val isVisible = cardId !in workingHidden
                    val isFull = cardId in workingFullWidth

                    if (index > 0) {
                        XPeachInsetDivider(verticalPadding = 6.dp)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}. $title",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isVisible) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            XPeachStatusDotText(
                                text = if (!isVisible) {
                                    "已隐藏"
                                } else if (isFull) {
                                    "大卡片"
                                } else {
                                    "小卡片"
                                },
                                dotColor = if (isVisible) {
                                    XPeachTheme.readablePrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                XPeachFilterPill(
                                    label = if (isFull) "小卡" else "大卡",
                                    selected = isFull,
                                    compact = true,
                                    onClick = {
                                        workingFullWidth = if (isFull) {
                                            workingFullWidth - cardId
                                        } else {
                                            workingFullWidth + cardId
                                        }
                                    }
                                )
                                XPeachFilterPill(
                                    label = if (isVisible) "显示" else "隐藏",
                                    selected = isVisible,
                                    compact = true,
                                    onClick = {
                                        workingHidden = if (isVisible) {
                                            workingHidden + cardId
                                        } else {
                                            workingHidden - cardId
                                        }
                                    }
                                )
                                XPeachFilterPill(
                                    label = "↑",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        if (index > 0) {
                                            val mut = workingOrder.toMutableList()
                                            val tmp = mut[index - 1]
                                            mut[index - 1] = mut[index]
                                            mut[index] = tmp
                                            workingOrder = mut
                                        }
                                    }
                                )
                                XPeachFilterPill(
                                    label = "↓",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        if (index < workingOrder.lastIndex) {
                                            val mut = workingOrder.toMutableList()
                                            val tmp = mut[index + 1]
                                            mut[index + 1] = mut[index]
                                            mut[index] = tmp
                                            workingOrder = mut
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = onResetDefault,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("恢复默认")
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSave(workingOrder, workingHidden, workingFullWidth) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
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
internal fun BentoModuleTile(
    modifier: Modifier = Modifier,
    title: String,
    badgeText: String,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    XPeachCard(
        modifier = modifier,
        containerColor = if (highlighted) {
            XPeachTheme.primaryAccentCardColor
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            if (highlighted) {
                XPeachStatusBadge(
                    text = badgeText,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                XPeachStatusDotText(text = badgeText)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
