package cn.edu.haut.campus.core.vault.sync

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 面向 UI/Worker 的高层同步客户端：显式同意、设备登记、手动/后台 runOnce 与冲突解决入口。 */
class E2eeSyncClient internal constructor(
    private val stateStore: E2eeSyncStateStore,
    private val keyStore: E2eeSyncKeyStore,
    private val backupStore: SyncBackupStore,
    private val apiFactory: suspend (syncDeviceIdProvider: () -> String?) -> E2eeSyncApi,
    private val cloudUserIdProvider: suspend () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuid: () -> String = { UUID.randomUUID().toString() }
) {
    private val lifecycleMutex = Mutex()

    suspend fun status(): E2eeSyncStatus = newEngineForCurrentState().status()

    suspend fun listDevices(): List<SyncDevice> {
        val (_, api) = stateAndApi()
        // 设备管理是账号安全控制：即使 consent 已撤回或本机已停用，仍允许列出现存登记并撤销失窃设备。
        return api.listDevices()
    }

    /**
     * 同意并登记设备。CREATE_NEW_SYNC_SPACE 只在本地没有既有密钥时生成新恢复密钥；
     * 返回的恢复密钥是秘密，UI 只能一次性展示，不得写日志/备份/诊断。
     */
    suspend fun enable(
        mode: E2eeSyncEnableMode,
        deviceLabel: String?,
        recoveryKeyInput: String? = null
    ): E2eeSyncEnableResult = lifecycleMutex.withLock {
        val state = stateStore.load()
        val cloudUserId = cloudUserIdProvider()?.trim().orEmpty()
        if (cloudUserId.isEmpty()) throw E2eeSyncConsentException("需要先登录 XPeach Cloud 才能启用 E2EE 同步")
        if (state.enabled) {
            if (state.cloudUserId != cloudUserId) {
                throw E2eeSyncConsentException("云账号已变化，原同步状态已冻结；请先停用本地同步状态")
            }
            return@withLock E2eeSyncEnableResult(
                deviceId = state.deviceId.orEmpty(),
                consentId = state.consentId.orEmpty(),
                syncSpaceId = state.syncSpaceId.orEmpty(),
                recoveryKeyForUserCopy = null
            )
        }
        var recoveryForUser: String? = null
        when (mode) {
            E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE -> {
                if (keyStore.exists()) {
                    // 首轮启用可能在密钥落盘后、设备登记成功前失败；此时用户尚未拿到恢复密钥。
                    // state.keyId 仍为空可证明从未完成启用，必须复用该密钥完成同一轮注册而不是迫使用户丢失空间。
                    if (state.keyId != null) {
                        throw E2eeSyncCryptoException("本机已存在同步密钥；为避免割裂同步空间，请使用已有恢复密钥加入或先明确擦除本地同步状态")
                    }
                } else {
                    recoveryForUser = keyStore.createNew()
                }
            }
            E2eeSyncEnableMode.JOIN_EXISTING_SYNC_SPACE -> {
                val recovery = recoveryKeyInput?.trim().orEmpty()
                if (recovery.isEmpty()) throw E2eeSyncCryptoException("请输入另一台设备显示的同步恢复密钥")
                keyStore.importRecoveryKey(recovery)
            }
        }
        val material = keyStore.load()
        if (mode == E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE && recoveryForUser == null && state.keyId == null) {
            recoveryForUser = E2eeSyncRecoveryKeys.encodeMaterial(material.masterKey, material.syncSpaceId)
        }
        try {
            val deviceId = state.deviceId?.takeIf { it.isNotBlank() } ?: uuid()
            E2eeSyncProtocol.requireValidUuid(deviceId, "deviceId")
            if (state.deviceId != deviceId) {
                // 在任何网络写之前持久化 installation UUID：注册结果不确定时，下一次重试必须携带同一
                // device_id 与同一 Idempotency-Key，不能因进程重启/网络中断再生成的新设备挤占配额。
                stateStore.save(state.copy(deviceId = deviceId))
            }
            val api = apiFactory { deviceId }
            val consent = api.grantConsent()
            val label = deviceLabel?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            val registered = api.registerDevice(
                deviceId = deviceId,
                consentId = consent.id,
                deviceLabel = label,
                idempotencyKey = "e2ee-device-" + cn.edu.haut.campus.core.model.json.Digests.sha256Hex(deviceId, consent.id, label.orEmpty()).take(72)
            )
            if (registered.status != "ACTIVE") throw E2eeSyncConsentException("云端未接受同步设备登记")
            val cloudUserAfterRegistration = cloudUserIdProvider()?.trim().orEmpty()
            if (cloudUserAfterRegistration != cloudUserId) {
                throw E2eeSyncConsentException("云账号在启用期间变化，已拒绝保存可能错绑的同步状态")
            }
            val next = E2eeSyncState(
                enabled = true,
                deviceId = deviceId,
                consentId = consent.id,
                syncSpaceId = material.syncSpaceId,
                keyId = material.keyId,
                cloudUserId = cloudUserId,
                deviceLabel = label,
                cursor = null,
                objects = state.objects,
                pendingWrites = state.pendingWrites,
                conflicts = state.conflicts,
                lastRunAtEpochMs = state.lastRunAtEpochMs,
                lastRunSummary = state.lastRunSummary
            )
            stateStore.save(next)
            E2eeSyncEnableResult(
                deviceId = deviceId,
                consentId = consent.id,
                syncSpaceId = material.syncSpaceId,
                recoveryKeyForUserCopy = recoveryForUser
            )
        } finally {
            material.zero()
        }
    }

    suspend fun runOnce(): E2eeSyncRunReport = newEngineForCurrentState().runOnce()

    suspend fun resolveConflict(conflictId: String, resolution: E2eeSyncConflictResolution): E2eeSyncRunReport =
        newEngineForCurrentState().resolveConflict(conflictId, resolution)

    /** 撤销任意一台仍属当前云账号的同步设备；撤销当前设备时同时走本地停用/cursor 失效路径。 */
    suspend fun revokeDevice(deviceId: String, reason: String = "USER_REMOVED") {
        lifecycleMutex.withLock {
            val state = stateStore.load()
            val api = apiFactory { state.deviceId }
            api.revokeDevice(deviceId, reason, "e2ee-revoke-" + uuid())
            if (state.deviceId == deviceId) {
                val next = state.copy(
                    enabled = false,
                    deviceId = null,
                    consentId = null,
                    deviceLabel = null,
                    cursor = null
                )
                next.pendingWrites.clear()
                stateStore.save(next)
            }
        }
    }

    /** 停用云端处理并撤销本设备。密钥默认保留在本机密封存储，30 天保留期内重新同意仍可解密既有密文。 */
    suspend fun disableAndWithdraw(reason: String = "USER_REMOVED"): E2eeSyncDisableReport = lifecycleMutex.withLock {
        val state = stateStore.load()
        if (!state.enabled) return@withLock E2eeSyncDisableReport(deviceRevoked = false, consentWithdrawn = false)
        val api = runCatching { apiFactory { state.deviceId } }.getOrNull()
        // 顺序必须是先撤回 consent 再 revoke 当前设备：revoke 当前设备会失效其绑定的会话族，
        // 若先 revoke，后续 withdraw 请求会被服务器拒绝。设备管理在 consent 撤回后仍可作为安全控制使用。
        val consentId = state.consentId
        val consentWithdrawn = if (api != null && !consentId.isNullOrBlank()) {
            runCatching { api.withdrawConsent(consentId) }.isSuccess
        } else {
            false
        }
        val deviceId = state.deviceId
        val deviceRevoked = if (api != null && !deviceId.isNullOrBlank()) {
            runCatching { api.revokeDevice(deviceId, reason, "e2ee-revoke-" + uuid()) }.isSuccess
        } else {
            false
        }
        // CT-07：撤回会使全部 device-bound cursor/ack watermark 失效；本地绝不能继续沿用。
        // revoke 是永久 tombstone，重新同意必须生成全新 installation UUID，不能复活旧 device_id。
        val next = state.copy(
            enabled = false,
            deviceId = null,
            consentId = null,
            deviceLabel = null,
            cursor = null
        )
        next.pendingWrites.clear()
        stateStore.save(next)
        E2eeSyncDisableReport(deviceRevoked = deviceRevoked, consentWithdrawn = consentWithdrawn)
    }

    /** 本地擦除钩子：绝不为了擦除而等待网络；密钥/状态/游标/冲突缓存都必须消失。 */
    suspend fun wipeLocalOnly() {
        lifecycleMutex.withLock {
            stateStore.wipe()
            keyStore.wipe()
        }
    }

    private suspend fun newEngineForCurrentState(): E2eeSyncEngine {
        val (state, api) = stateAndApi()
        return E2eeSyncEngine(
            stateStore = stateStore,
            keyStore = keyStore,
            api = api,
            backupStore = backupStore,
            expectedCloudUserId = runCatching { cloudUserIdProvider() }.getOrNull(),
            clock = clock,
            uuid = uuid
        ).also {
            // state 只用于装配 deviceId provider；引擎运行时会重新加载并校验 enabled/key/space。
            state.deviceId?.let { E2eeSyncProtocol.requireValidUuid(it, "deviceId") }
        }
    }

    private suspend fun stateAndApi(): Pair<E2eeSyncState, E2eeSyncApi> {
        val state = stateStore.load()
        val api = apiFactory { state.deviceId }
        return state to api
    }
}
