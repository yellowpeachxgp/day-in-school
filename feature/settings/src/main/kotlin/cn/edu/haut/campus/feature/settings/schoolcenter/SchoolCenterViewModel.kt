package cn.edu.haut.campus.feature.settings.schoolcenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiAction
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiEffect
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 聚合学校目录页身份 Flow，并把全部写操作留在 ViewModel。 */
class SchoolCenterViewModel(
    private val sessions: AccountSessionCoordinator?
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SchoolCenterUiState(sessionsAvailable = sessions != null)
    )
    val uiState: StateFlow<SchoolCenterUiState> = mutableUiState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<SchoolCenterUiEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SchoolCenterUiEffect> = mutableEffects.asSharedFlow()

    init {
        if (sessions != null) {
            viewModelScope.launch {
                sessions.profiles.collectLatest { profiles ->
                    mutableUiState.update { it.copy(profiles = profiles) }
                }
            }
        }
    }

    fun onAction(action: SchoolCenterUiAction) {
        when (action) {
            is SchoolCenterUiAction.UpdateQuery -> mutableUiState.update { it.copy(query = action.value) }
            is SchoolCenterUiAction.SelectSchool -> mutableUiState.update { it.copy(selectedSchoolId = action.schoolId) }
            is SchoolCenterUiAction.SetShowContribution -> mutableUiState.update {
                // 拆分前复制反馈保存在贡献卡片 remember 中，离开卡片即消失。
                it.copy(showContribution = action.visible, copyFeedback = null)
            }
            is SchoolCenterUiAction.SetShowCreateProfile -> mutableUiState.update { it.copy(showCreateProfile = action.visible) }
            is SchoolCenterUiAction.CreateOfflineProfile -> createOfflineProfile(action)
            SchoolCenterUiAction.CopyContributionTemplate -> viewModelScope.launch {
                mutableEffects.emit(
                    SchoolCenterUiEffect.CopyToClipboard(
                        label = "学校适配资料模板",
                        text = SCHOOL_CONTRIBUTION_TEMPLATE_TEXT
                    )
                )
            }
            is SchoolCenterUiAction.CopyContributionFeedback -> mutableUiState.update {
                it.copy(
                    copyFeedback = if (action.succeeded) {
                        "已复制空白模板，请填写公开资料"
                    } else {
                        "暂时无法使用剪贴板，可长按上方模板复制"
                    }
                )
            }
        }
    }

    private fun createOfflineProfile(action: SchoolCenterUiAction.CreateOfflineProfile) {
        val coordinator = sessions ?: return
        if (mutableUiState.value.creatingProfile) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(creatingProfile = true, profileFeedback = null) }
            try {
                coordinator.createOfflineProfile(action.school, action.label)
                mutableUiState.update {
                    it.copy(
                        showCreateProfile = false,
                        selectedSchoolId = null,
                        profileFeedback = "已创建离线身份，可在上方身份列表中切换"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(profileFeedback = "创建未完成，已有数据保持不变，请稍后重试")
                }
            } finally {
                mutableUiState.update { it.copy(creatingProfile = false) }
            }
        }
    }

    companion object {
        const val SCHOOL_CONTRIBUTION_TEMPLATE_TEXT = """学校名称：
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

        fun factory(sessions: AccountSessionCoordinator?): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchoolCenterViewModel(sessions) }
        }
    }
}
