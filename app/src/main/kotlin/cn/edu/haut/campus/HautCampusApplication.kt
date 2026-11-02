package cn.edu.haut.campus

import android.app.Application
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
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
        WidgetRefreshCoordinator.registerWidgetProviders(
            NextClassAppWidgetProvider::class.java,
            TodayAppWidgetProvider::class.java,
            TimelineAppWidgetProvider::class.java,
            WeekAppWidgetProvider::class.java,
            ParcelAppWidgetProvider::class.java
        )
        val sessions = AccountSessionCoordinator.getInstance(this)
        sessions.registerLifecycleHook(
            key = "account_background_work",
            beforeSwitch = { old ->
                WidgetRefreshCoordinator.clearForAccountSwitch(this)
                ReminderCoordinator.deactivateCurrent()
                FocusCoordinator.deactivateCurrent()
                old?.let { UtilitySamplingWorker.cancelPeriodicWork(this, it) }
            },
            afterSwitch = { active ->
                UtilitySamplingWorker.schedulePeriodicWork(this, active)
                ReminderCoordinator.bind(this, active).initialize()
                FocusCoordinator.bind(this, active)
                WidgetRefreshCoordinator.requestImmediateRefreshAll(this)
            }
        )
        applicationScope.launch {
            runCatching { sessions.initialize() }
        }
    }
}
