package cn.edu.haut.campus.feature.knowledge

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.knowledge.CourseMaterial
import cn.edu.haut.campus.core.knowledge.CourseMaterialKind
import cn.edu.haut.campus.core.knowledge.KnowledgeRepository
import cn.edu.haut.campus.core.knowledge.KnowledgeSearchHit
import cn.edu.haut.campus.core.knowledge.KnowledgeCoordinateSpace
import cn.edu.haut.campus.core.knowledge.ReviewTask
import cn.edu.haut.campus.core.knowledge.ReviewTaskState
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.textextract.ExtractionConfirmationState
import cn.edu.haut.campus.feature.knowledge.state.CourseDeadlineUi
import cn.edu.haut.campus.feature.knowledge.state.CourseMaterialCardUi
import cn.edu.haut.campus.feature.knowledge.state.CourseReviewTaskUi
import cn.edu.haut.campus.feature.knowledge.state.CourseSearchHitUi
import cn.edu.haut.campus.feature.knowledge.state.CourseSpaceStatus
import cn.edu.haut.campus.feature.knowledge.state.CourseSpaceUiAction
import cn.edu.haut.campus.feature.knowledge.state.CourseSpaceUiEffect
import cn.edu.haut.campus.feature.knowledge.state.CourseSpaceUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 课程空间状态边界。ui/ 只渲染 UiState/发送 UiAction；KnowledgeRepository 由本 VM 独占，
 * 组合作用域 Owner 清理 VM 时同步 close，防止旧 Profile 的 outbox/索引进程越过会话。
 */
class CourseSpaceViewModel(
    repositoryFactory: () -> KnowledgeRepository,
    private val scope: SchoolAccountScope,
    private val courseId: String,
    courseName: String,
    termLabel: String
) : ViewModel() {
    private val repository = repositoryFactory()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var loadedMaterials: List<CourseMaterial> = emptyList()
    private var loadedReviewTasks: List<ReviewTask> = emptyList()

    private val _uiState = MutableStateFlow(
        CourseSpaceUiState(
            status = CourseSpaceStatus.LOADING,
            courseName = courseName,
            termLabel = termLabel,
            selectedWeek = null,
            availableWeeks = emptyList(),
            query = ""
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CourseSpaceUiEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onAction(action: CourseSpaceUiAction) {
        when (action) {
            is CourseSpaceUiAction.QueryChanged -> updateQuery(action.value)
            is CourseSpaceUiAction.WeekSelected -> selectWeek(action.week)
            is CourseSpaceUiAction.MaterialOpened -> emit(CourseSpaceUiEffect.OpenMaterial(action.materialId))
            CourseSpaceUiAction.ExportClicked -> emit(CourseSpaceUiEffect.ExportCoursePackage(courseId))
            CourseSpaceUiAction.RetryClicked -> refresh()
            CourseSpaceUiAction.PermissionRequested -> emit(CourseSpaceUiEffect.RequestFilePermission)
            CourseSpaceUiAction.LoginRequested -> emit(CourseSpaceUiEffect.NavigateToLogin)
        }
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = coroutineScope.launch {
            _uiState.update { it.copy(status = CourseSpaceStatus.LOADING, errorMessage = null, notice = null) }
            try {
                loadedMaterials = repository.listMaterials(scope = scope, courseId = courseId)
                loadedReviewTasks = repository.listReviewTasks(scope = scope, courseId = courseId)
                applyLoadedState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        status = CourseSpaceStatus.ERROR_RETRYABLE,
                        errorMessage = error.message?.takeIf(String::isNotBlank) ?: "课程资料加载失败"
                    )
                }
            }
        }
    }

    private fun selectWeek(week: Int?) {
        _uiState.update { it.copy(selectedWeek = week) }
        applyLoadedState()
        val query = _uiState.value.query
        if (query.isNotBlank()) updateQuery(query)
    }

    private fun applyLoadedState() {
        val selectedWeek = _uiState.value.selectedWeek
        val visibleMaterials = loadedMaterials.filter { selectedWeek == null || it.course.teachingWeek == selectedWeek }
        val status = if (loadedMaterials.isEmpty()) CourseSpaceStatus.EMPTY else CourseSpaceStatus.CONTENT
        _uiState.update {
            it.copy(
                status = status,
                availableWeeks = loadedMaterials.map { material -> material.course.teachingWeek }.distinct().sorted(),
                materials = visibleMaterials.map(::toMaterialCard),
                deadlines = visibleMaterials.mapNotNull(::toDeadlineCard),
                reviewTasks = loadedReviewTasks.map(::toReviewTaskCard),
                errorMessage = null
            )
        }
    }

    private fun updateQuery(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            _uiState.update { it.copy(searchHits = emptyList()) }
            return
        }
        searchJob = coroutineScope.launch {
            try {
                val hits = repository.searchMaterials(
                    scope = scope,
                    text = value,
                    courseId = courseId,
                    teachingWeek = _uiState.value.selectedWeek,
                    limit = 20
                )
                _uiState.update {
                    if (it.query == value) it.copy(searchHits = hits.map(::toSearchHitCard), notice = null) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (it.query == value) {
                        it.copy(notice = error.message?.takeIf(String::isNotBlank) ?: "搜索暂时不可用")
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun toMaterialCard(material: CourseMaterial): CourseMaterialCardUi {
        val extraction = material.extraction
        return CourseMaterialCardUi(
            id = material.id,
            title = material.title,
            kindLabel = when (material.kind) {
                CourseMaterialKind.SLIDES -> "课件"
                CourseMaterialKind.PDF -> "PDF"
                CourseMaterialKind.PHOTO -> "照片"
                CourseMaterialKind.NOTE -> "笔记"
                CourseMaterialKind.ASSIGNMENT -> "作业"
            },
            teachingWeek = material.course.teachingWeek,
            fileLabel = material.fileName,
            extractionLabel = when {
                extraction == null -> "未抽取正文"
                extraction.confirmationState == ExtractionConfirmationState.USER_CONFIRMED -> "文本已确认"
                else -> "抽取待确认"
            },
            extractionNeedsConfirmation = extraction?.confirmationState == ExtractionConfirmationState.UNCONFIRMED,
            updatedLabel = material.updatedAt.toString().replace('T', ' ').take(16)
        )
    }

    private fun toDeadlineCard(material: CourseMaterial): CourseDeadlineUi? {
        val assignment = material.assignment ?: return null
        return CourseDeadlineUi(
            id = material.id,
            title = material.title,
            dueLabel = assignment.dueAt.atZone(assignment.zone).toLocalDateTime().toString().replace('T', ' ') + " 截止",
            inTimeline = assignment.timelineEventId != null
        )
    }

    private fun toReviewTaskCard(task: ReviewTask): CourseReviewTaskUi = CourseReviewTaskUi(
        id = task.id,
        title = task.title,
        stateLabel = when (task.state) {
            ReviewTaskState.TODO -> "待做"
            ReviewTaskState.DONE -> "已完成"
            ReviewTaskState.ARCHIVED -> "已归档"
        },
        dueLabel = task.dueAt?.toString()?.replace('T', ' ')?.take(16) ?: "未设截止",
        estimatedLabel = task.estimatedMinutes?.let { "约 " + it + " 分钟" } ?: "未估时"
    )

    private fun toSearchHitCard(hit: KnowledgeSearchHit): CourseSearchHitUi {
        val pageIndex = hit.pageIndex
        return CourseSearchHitUi(
            id = hit.materialId + ":" + hit.pageIndex + ":" + hit.lineIndex,
            materialId = hit.materialId,
            title = hit.title,
            snippet = hit.snippet,
            locationLabel = when {
                hit.coordinateSpace == KnowledgeCoordinateSpace.IMAGE_PIXEL_TOP_LEFT && hit.bounds != null -> "图片区域"
                pageIndex != null -> "第 " + (pageIndex + 1) + " 页"
                else -> "资料元数据"
            },
            needsConfirmation = hit.confirmationState == ExtractionConfirmationState.UNCONFIRMED
        )
    }

    private fun emit(effect: CourseSpaceUiEffect) {
        check(_effects.tryEmit(effect)) { "课程空间事件缓冲已满" }
    }

    override fun onCleared() {
        refreshJob?.cancel()
        searchJob?.cancel()
        coroutineScope.cancel()
        repository.close()
    }
}
