package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.StudentProfile
import kotlinx.coroutines.flow.Flow

internal typealias CloudHttpTransportForTest = (String, String, String?, String?, Map<String, String>) -> String

/**
 * Moments 云访问运行时端口。
 * 云身份令牌、Facade preferencesFlow 与测试传输仍由兼容外观/云身份分片拥有；
 * MomentsRepository 只消费该端口，避免把 CampusServices B6+ 实现提前搬迁。
 */
internal interface MomentsRuntimePort {
    val preferencesFlow: Flow<AppPreferencesState>
    val studentProfileFlow: Flow<StudentProfile?>

    suspend fun resolveValidCloudAccessToken(forceRefresh: Boolean): String

    fun cloudHttpTransportForTest(): CloudHttpTransportForTest?
}
