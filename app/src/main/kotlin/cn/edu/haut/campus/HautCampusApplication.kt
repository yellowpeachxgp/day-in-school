package cn.edu.haut.campus

import android.app.Application
import cn.edu.haut.campus.core.automation.HomeRefreshActionPort
import cn.edu.haut.campus.core.automation.android.AutomationRuntime
import cn.edu.haut.campus.core.automation.android.AutomationRuntimeCoordinator
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ExtensionPluginWipeRegistration
import cn.edu.haut.campus.core.designsystem.LauncherIconAliasReconciler
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
import cn.edu.haut.campus.core.vault.VaultRuntimeInstaller
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider
import cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider
import cn.edu.haut.campus.widget.today.ParcelAppWidgetProvider
import cn.edu.haut.campus.widget.today.TodayAppWidgetProvider
import cn.edu.haut.campus.widget.week.WeekAppWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HautCampusApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LauncherIconAliasReconciler.reconcile(this)
        WidgetRefreshCoordinator.registerWidgetProviders(
            NextClassAppWidgetProvider::class.java,
            TodayAppWidgetProvider::class.java,
            TimelineAppWidgetProvider::class.java,
            WeekAppWidgetProvider::class.java,
            ParcelAppWidgetProvider::class.java
        )
        VaultRuntimeInstaller.install()
        val sessions = AccountSessionCoordinator.getInstance(this)
        sessions.registerLifecycleHook(
            key = "extension_plugin_security_wipe",
            beforeSwitch = { },
            afterSwitch = { active ->
                ExtensionPluginWipeRegistration.register(
                    active.repository,
                    ExtensionPluginWipeRegistration.rootForProfile(filesDir, active.profileId)
                )
            }
        )
        sessions.registerLifecycleHook(
            key = "account_background_work",
            beforeSwitch = { old ->
                WidgetRefreshCoordinator.clearForAccountSwitch(this)
                ReminderCoordinator.deactivateCurrent()
                FocusCoordinator.deactivateCurrent()
                AutomationRuntime.deactivateCurrent(this)
                old?.let { UtilitySamplingWorker.cancelPeriodicWork(this, it) }
            },
            afterSwitch = { active ->
                UtilitySamplingWorker.schedulePeriodicWork(this, active)
                ReminderCoordinator.bind(this, active).initialize()
                FocusCoordinator.bind(this, active)
                val automationRefreshPort = object : HomeRefreshActionPort {
                    override suspend fun refresh(
                        scope: cn.edu.haut.campus.core.model.SchoolAccountScope,
                        reason: String
                    ): Boolean {
                        // 首页卡片由 Room Flow 驱动；这里执行既有自节流的教务/水电刷新，成功后广播小组件。
                        val academicHealthy = if (!active.repository.supportsHautServices) {
                            true // 离线学校没有远程学业刷新；本地 Room Flow + 小组件刷新本身就是有效路径
                        } else {
                            runCatching {
                                when (val result = active.repository.performDynamicAcademicSync(force = false)) {
                                    null -> true // 频率限制/功能关闭意味着本轮无需刷新
                                    else -> result.isSuccess
                                }
                            }.getOrDefault(false)
                        }
                        val utilitySampled = runCatching {
                            active.repository.performPeriodicUtilitySampleIfEligible()
                        }.getOrDefault(false)
                        if (academicHealthy || utilitySampled) {
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(this@HautCampusApplication)
                        }
                        return academicHealthy || utilitySampled
                    }
                }
                AutomationRuntime.bind(this@HautCampusApplication, active, automationRefreshPort)
                AutomationRuntimeCoordinator.enqueueImmediate(
                    this@HautCampusApplication,
                    AccountBackgroundScope.from(active),
                    "account-active"
                )
                WidgetRefreshCoordinator.requestImmediateRefreshAll(this)
            }
        )
        applicationScope.launch {
            runCatching { sessions.initialize() }
        }
    }
}
