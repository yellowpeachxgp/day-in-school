package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * LibraryBooking 拆分期运行时端口。
 * preferencesFlow、会话门禁、测试传输钩子、repoScope 与云同步回连仍由兼容外观持有；
 * 预约创建/执行片段逐次现读同一实例，保持搬迁前触发与回连时序。
 */
internal interface LibraryBookingRuntimePort {
    val preferencesFlow: Flow<AppPreferencesState>

    /** 门面同一 CoroutineScope：后台 syncWithCampusCloud 火后即忘顺序不得改变。 */
    val repoScope: CoroutineScope

    val isSessionActive: Boolean

    fun requireActiveSession()

    fun cloudHttpTransportForTest(): CloudHttpTransportForTest?

    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult>
}
