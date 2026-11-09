package cn.edu.haut.campus.core.designsystem

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面启动图标别名互斥收敛器（Item #12）：
 * - 解决旧版本已开启 `MainActivityAnimeIconAlias` 的用户升级到新增 `MainActivityDefaultIconAlias(android:enabled="true")` 版本后出现双桌面图标的问题；
 * - 解决新装、默认图标升级、以及开关切换中途崩溃/中断导致的双开或双关状态，确保任意时刻仅保留唯一一个激活的 LAUNCHER 入口。
 */
object LauncherIconAliasReconciler {
    const val DEFAULT_ALIAS_CLASS_NAME = "cn.edu.haut.campus.MainActivityDefaultIconAlias"
    const val ANIME_ALIAS_CLASS_NAME = "cn.edu.haut.campus.MainActivityAnimeIconAlias"

    private const val PREFS_NAME = "launcher_icon_alias_reconciler"
    private const val KEY_DESIRED_STYLE = "desired_style"
    const val STYLE_DEFAULT = "default"
    const val STYLE_ANIME = "anime"

    data class AliasResolutionPlan(
        val activeStyle: String,
        val targetDefaultComponentState: Int?,
        val targetAnimeComponentState: Int?
    ) {
        val isAnimeActive: Boolean get() = activeStyle == STYLE_ANIME
    }

    /**
     * 纯函数状态收敛计算（供单元测试与运行时共用）：
     * - Manifest 默认值：`MainActivityDefaultIconAlias` = true，`MainActivityAnimeIconAlias` = false。
     * - 当旧版本用户曾开启 `MainActivityAnimeIconAlias`（`animeState == ENABLED`）且尚未写入本地偏好，升级后 `defaultState == DEFAULT`（等效开启）时，
     *   自动识别用户原意为 `STYLE_ANIME`，并将 `MainActivityDefaultIconAlias` 显式置为 `DISABLED`，消除双图标。
     * - 若切换中途异常中断导致双开（且已有明确 `recordedDesiredStyle`）或双关，收敛到唯一激活入口，绝不留下 0 个或 2 个桌面图标。
     */
    fun computeResolutionPlan(
        defaultComponentState: Int,
        animeComponentState: Int,
        recordedDesiredStyle: String?
    ): AliasResolutionPlan {
        val defaultEffectivelyEnabled =
            defaultComponentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                defaultComponentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val animeEffectivelyEnabled =
            animeComponentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        val targetStyle = when {
            // 双开（旧版开启二次元图标后升级导致 default=DEFAULT(true) + anime=ENABLED，或切换中断双 ENABLED）
            defaultEffectivelyEnabled && animeEffectivelyEnabled -> {
                when (recordedDesiredStyle) {
                    STYLE_DEFAULT -> STYLE_DEFAULT
                    STYLE_ANIME -> STYLE_ANIME
                    // 无偏好记录时，animeComponentState 只能来自用户在旧版主动开启二次元图标，优先保留用户选择
                    else -> STYLE_ANIME
                }
            }
            // 双关（切换中断或系统异常禁用两个别名）：必须兜底恢复唯一可用入口，防止桌面图标消失
            !defaultEffectivelyEnabled && !animeEffectivelyEnabled -> {
                if (recordedDesiredStyle == STYLE_ANIME) STYLE_ANIME else STYLE_DEFAULT
            }
            animeEffectivelyEnabled -> STYLE_ANIME
            else -> STYLE_DEFAULT
        }

        return if (targetStyle == STYLE_ANIME) {
            AliasResolutionPlan(
                activeStyle = STYLE_ANIME,
                targetDefaultComponentState = if (defaultEffectivelyEnabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else null,
                targetAnimeComponentState = if (!animeEffectivelyEnabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else null
            )
        } else {
            AliasResolutionPlan(
                activeStyle = STYLE_DEFAULT,
                targetDefaultComponentState = if (!defaultEffectivelyEnabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else null,
                targetAnimeComponentState = if (animeEffectivelyEnabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else null
            )
        }
    }

    /**
     * 在 Application 启动与 `MY_PACKAGE_REPLACED` 升级广播中调用，幂等修复双图标或零图标异常状态。
     */
    fun reconcile(context: Context): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        val defaultAlias = ComponentName(pkg, DEFAULT_ALIAS_CLASS_NAME)
        val animeAlias = ComponentName(pkg, ANIME_ALIAS_CLASS_NAME)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recordedStyle = prefs.getString(KEY_DESIRED_STYLE, null)

        val defaultState = runCatching { pm.getComponentEnabledSetting(defaultAlias) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val animeState = runCatching { pm.getComponentEnabledSetting(animeAlias) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        val plan = computeResolutionPlan(
            defaultComponentState = defaultState,
            animeComponentState = animeState,
            recordedDesiredStyle = recordedStyle
        )

        runCatching {
            // 先启用目标入口，再禁用冗余入口，确保任意中断时刻至少存在一个可用桌面入口
            if (plan.isAnimeActive) {
                plan.targetAnimeComponentState?.let {
                    pm.setComponentEnabledSetting(animeAlias, it, PackageManager.DONT_KILL_APP)
                }
                plan.targetDefaultComponentState?.let {
                    pm.setComponentEnabledSetting(defaultAlias, it, PackageManager.DONT_KILL_APP)
                }
            } else {
                plan.targetDefaultComponentState?.let {
                    pm.setComponentEnabledSetting(defaultAlias, it, PackageManager.DONT_KILL_APP)
                }
                plan.targetAnimeComponentState?.let {
                    pm.setComponentEnabledSetting(animeAlias, it, PackageManager.DONT_KILL_APP)
                }
            }
            if (recordedStyle != plan.activeStyle) {
                prefs.edit().putString(KEY_DESIRED_STYLE, plan.activeStyle).apply()
            }
        }
        return plan.isAnimeActive
    }

    /**
     * 用户在设置页主动切换桌面图标时调用：先持久化目标意图并先开后关，避免中途崩溃丢失意图或产生零图标。
     */
    fun applyUserIconSelection(context: Context, enableAnimeIcon: Boolean) {
        val pm = context.packageManager
        val pkg = context.packageName
        val defaultAlias = ComponentName(pkg, DEFAULT_ALIAS_CLASS_NAME)
        val animeAlias = ComponentName(pkg, ANIME_ALIAS_CLASS_NAME)
        val desired = if (enableAnimeIcon) STYLE_ANIME else STYLE_DEFAULT

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DESIRED_STYLE, desired)
            .commit()

        if (enableAnimeIcon) {
            pm.setComponentEnabledSetting(
                animeAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            pm.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                animeAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
