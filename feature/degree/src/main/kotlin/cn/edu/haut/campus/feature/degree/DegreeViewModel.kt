package cn.edu.haut.campus.feature.degree

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.degree.DegreeRepository
import cn.edu.haut.campus.core.degree.OverrideInput
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.plan.ImportInput
import cn.edu.haut.campus.core.plan.OverrideAction
import cn.edu.haut.campus.feature.degree.state.DegreeStatus
import cn.edu.haut.campus.feature.degree.state.DegreeUiAction
import cn.edu.haut.campus.feature.degree.state.DegreeUiEffect
import cn.edu.haut.campus.feature.degree.state.DegreeUiState
import cn.edu.haut.campus.feature.degree.state.OverrideDraft
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 毕业进度看板状态边界。DegreeRepository 由本 VM 独占，清理时 close 释放 vault outbox observer。
 * 全部写操作Actor.USER：人工认定的审批痕迹归属用户本人。
 */
class DegreeViewModel(
    repositoryFactory: () -> DegreeRepository,
    private val gradeAttemptsProvider: suspend () -> List<GradeAttempt>,
    private val nowProvider: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {
    private val repository = repositoryFactory()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(DegreeUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DegreeUiEffect>(extraBufferCapacity = 4)
    val effects = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onAction(action: DegreeUiAction) {
        when (action) {
            DegreeUiAction.Refresh -> refresh()
            DegreeUiAction.OpenImportDialog -> _uiState.update {
                it.copy(showImportDialog = true, importError = null)
            }
            DegreeUiAction.CloseImportDialog -> _uiState.update {
                it.copy(showImportDialog = false, importError = null, importText = "")
            }
            is DegreeUiAction.ImportTextChanged -> _uiState.update {
                it.copy(importText = action.text, importError = null)
            }
            DegreeUiAction.SubmitImport -> submitImport()
            is DegreeUiAction.ConfirmPlan -> confirmPlan(action.importId)
            is DegreeUiAction.ActivateImport -> activateImport(action.importId)
            is DegreeUiAction.ToggleGroupTrace -> _uiState.update { state ->
                val next = if (action.groupId in state.expandedGroupIds) {
                    state.expandedGroupIds - action.groupId
                } else {
                    state.expandedGroupIds + action.groupId
                }
                state.copy(expandedGroupIds = next)
            }
            is DegreeUiAction.OpenOverride -> {
                val resolvedAttemptId = _uiState.value.report?.representativeAttemptIds?.get(action.attemptId)
                if (resolvedAttemptId == null) {
                    _uiState.update { it.copy(notice = "找不到该课程的成绩记录，无法认定") }
                } else {
                    _uiState.update {
                        it.copy(
                            overrideDraft = OverrideDraft(
                                attemptId = resolvedAttemptId,
                                courseName = action.courseName,
                                targetGroupId = null,
                                exclude = false,
                                reason = ""
                            )
                        )
                    }
                }
            }
            DegreeUiAction.CloseOverride -> _uiState.update { it.copy(overrideDraft = null) }
            is DegreeUiAction.OverrideTargetChanged -> _uiState.update { state ->
                state.copy(
                    overrideDraft = state.overrideDraft?.copy(
                        targetGroupId = action.groupId,
                        exclude = action.exclude
                    )
                )
            }
            is DegreeUiAction.OverrideReasonChanged -> _uiState.update { state ->
                state.copy(overrideDraft = state.overrideDraft?.copy(reason = action.reason))
            }
            DegreeUiAction.SubmitOverride -> submitOverride()
            DegreeUiAction.DismissNotice -> _uiState.update { it.copy(notice = null) }
        }
    }

    private fun refresh() {
        scope.launch {
            _uiState.update { it.copy(status = DegreeStatus.LOADING, errorMessage = null) }
            try {
                val imports = repository.listImports()
                if (imports.isEmpty()) {
                    _uiState.update {
                        it.copy(status = DegreeStatus.EMPTY, imports = emptyList(), report = null, activeImportId = null)
                    }
                    return@launch
                }
                val attempts = gradeAttemptsProvider()
                val report = repository.auditActivePlan(attempts, nowProvider())
                val active = repository.activeImport()
                _uiState.update {
                    it.copy(
                        status = DegreeStatus.READY,
                        imports = imports,
                        activeImportId = active?.importId,
                        report = report,
                        gradeAttemptCount = attempts.size
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(status = DegreeStatus.ERROR, errorMessage = error.message ?: "加载失败")
                }
            }
        }
    }

    private fun submitImport() {
        val text = _uiState.value.importText.trim()
        if (text.isEmpty()) {
            _uiState.update { it.copy(importError = "请粘贴 univ-training-plan/v1 JSON 或扁平 CSV 文本") }
            return
        }
        val input = if (text.startsWith("{")) {
            ImportInput.JsonDocument(text)
        } else {
            ImportInput.CsvDocument(text)
        }
        scope.launch {
            _uiState.update { it.copy(busy = true, importError = null) }
            try {
                val record = repository.importPlan(input, Actor.USER)
                val warningNote = if (record.warnings.isNotEmpty()) {
                    "；有 " + record.warnings.size + " 条导入提示请核对"
                } else {
                    ""
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        showImportDialog = false,
                        importText = "",
                        notice = "已导入「" + record.title + "」" + warningNote
                    )
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(busy = false, importError = error.message ?: "导入失败，请检查文本格式")
                }
            }
        }
    }

    private fun confirmPlan(importId: String) {
        scope.launch {
            try {
                repository.setConfirmedForStudent(importId, true, Actor.USER)
                _uiState.update { it.copy(notice = "已确认方案适用于本人，结论升级为本地推导") }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = "确认失败：" + (error.message ?: "未知错误")) }
            }
        }
    }

    private fun activateImport(importId: String) {
        scope.launch {
            try {
                repository.setActiveImport(importId, Actor.USER)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = "切换失败：" + (error.message ?: "未知错误")) }
            }
        }
    }

    private fun submitOverride() {
        val draft = _uiState.value.overrideDraft ?: return
        if (draft.reason.isBlank()) {
            _uiState.update { it.copy(notice = "人工认定必须填写依据") }
            return
        }
        if (!draft.exclude && draft.targetGroupId == null) {
            _uiState.update { it.copy(notice = "请选择目标要求组或改为排除") }
            return
        }
        scope.launch {
            try {
                repository.recordOverride(
                    OverrideInput(
                        attemptId = draft.attemptId,
                        action = if (draft.exclude) OverrideAction.EXCLUDE else OverrideAction.ASSIGN,
                        mapToGroupId = if (draft.exclude) null else draft.targetGroupId,
                        reason = draft.reason.trim()
                    ),
                    Actor.USER
                )
                _uiState.update { it.copy(overrideDraft = null, notice = "人工认定已记录并写入变更历史") }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(notice = "认定失败：" + (error.message ?: "未知错误")) }
            }
        }
    }

    override fun onCleared() {
        closeForSessionEnd()
    }

    /** 会话结束（含测试）统一清理入口：取消协程并释放 vault outbox observer。 */
    internal fun closeForSessionEnd() {
        scope.cancel()
        repository.close()
    }
}
