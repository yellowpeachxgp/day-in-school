package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import cn.edu.haut.campus.core.domain.SchoolDirectory
import java.time.ZoneId

/**
 * W0-05 拆分期共享的持久化执行环境。
 * 只暴露同一账号 Profile 已绑定好的 Room/DataStore 与通知回调；不包含业务实现。
 */
internal class CampusRepositoryEnvironment(
    val database: HautDatabase,
    val preferences: DataStore<Preferences>,
    val accountProfile: AccountProfileEntity,
    private val scheduleOrPolicyChangedNotifier: suspend (String) -> Unit
) {
    val dao: CampusDao = database.campusDao()

    val supportsHautServices: Boolean
        get() = accountProfile.schoolId == SchoolDirectory.HAUT_SCHOOL_ID.value

    val timeZone: ZoneId
        get() = ZoneId.of(accountProfile.timeZoneId)

    suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        scheduleOrPolicyChangedNotifier(reason)
    }
}
