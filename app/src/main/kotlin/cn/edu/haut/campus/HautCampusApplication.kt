package cn.edu.haut.campus

import android.app.Application
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider
import cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider
import cn.edu.haut.campus.widget.today.TodayAppWidgetProvider
import cn.edu.haut.campus.widget.week.WeekAppWidgetProvider

class HautCampusApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CampusRepository.getInstance(this)
        ReminderCoordinator.getInstance(this).initialize()
        FocusCoordinator.getInstance(this)
        UtilitySamplingWorker.schedulePeriodicWork(this)
        WidgetRefreshCoordinator.registerWidgetProviders(
            NextClassAppWidgetProvider::class.java,
            TodayAppWidgetProvider::class.java,
            TimelineAppWidgetProvider::class.java,
            WeekAppWidgetProvider::class.java
        )
    }
}
