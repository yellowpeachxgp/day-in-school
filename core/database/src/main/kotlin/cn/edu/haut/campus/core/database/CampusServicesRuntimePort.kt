package cn.edu.haut.campus.core.database

import kotlinx.coroutines.flow.Flow

/**
 * CampusServices 拆分期运行时端口。
 * 教务凭据迁移/读取、preferencesFlow、云身份提交屏障与世代推进仍由兼容外观的
 * 后续批次所有者提供；本批服务只消费端口，不提前搬迁 CloudIdentity/Sync 实现。
 */
internal interface CampusServicesRuntimePort {
    val preferencesFlow: Flow<AppPreferencesState>
    val activeSessionGeneration: Long?

    suspend fun migrateLegacyAcademicCredentialsIfNeeded()

    fun readAcademicCredential(accountId: String): AcademicCredentialReadResult

    suspend fun <T> withCloudIdentityBarrier(block: suspend () -> T): T

    suspend fun bumpCloudIdentityGenerationForBaseUrlChange(cloudUserId: String?): Long

    suspend fun currentCloudUserIdInBarrier(): String?
}
