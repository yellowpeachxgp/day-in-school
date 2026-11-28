package cn.edu.haut.campus.core.vault.sync

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 面向 UI/Worker 的高层同步客户端：显式同意、设备登记、手动/后台 runOnce 与冲突解决入口。 */
class E2eeSyncClient(
    private val stateStore: E2eeSyncStateStore,
    private val keyStore: E2eeSyncKeyStore,
    private val backupStore: SyncBackupStore,
    private val apiFactory: suspend (syncDeviceIdProvider: () -> String?) -> E2eeSyncApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuid: () -> String = { UUID.randomUUID().toString() }
) {
    private val lifecycleMutex = Mutex()

    suspend fun status(): E2eeSyncStatus = newEngineForCurrentState().status()

    suspend fun listDevices(): List<SyncDevice> {
        val (state, api) = stateAndApi()
        if (!state.enabled) return emptyList()
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
        if (state.enabled) {
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
                    throw E2eeSyncCryptoException("本机已存在同步密钥；为避免割裂同步空间，请使用已有恢复密钥加入或先明确擦除本地同步状态")
                }
                recoveryForUser = keyStore.createNew()
            }
            E2eeSyncEnableMode.JOIN_EXISTING_SYNC_SPACE -> {
                val recovery = recoveryKeyInput?.trim().orEmpty()
                if (recovery.isEmpty()) throw E2eeSyncCryptoException("请输入另一台设备显示的同步恢复密钥")
                keyStore.importRecoveryKey(recovery)
            }
        }
        val material = keyStore.load()
        try {
            val deviceId = state.deviceId?.takeIf { it.isNotBlank() } ?: uuid()
            E2eeSyncProtocol.requireValidUuid(deviceId, "deviceId")
            val api = apiFactory { deviceId }
            val consent = api.grantConsent()
            val label = deviceLabel?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            val registered = api.registerDevice(
                deviceId = deviceId,
                consentId = consent.id,
                deviceLabel = label,
                idempotencyKey = "e2ee-device-" + cn.edu.haut.campus.core.model.json.Digests.sha256Hex(deviceId, consent.id).take(72)
            )
            if (registered.status != "ACTIVE") throw E2eeSyncConsentException("云端未接受同步设备登记")
            val next = E2eeSyncState(
                enabled = true,
                deviceId = deviceId,
                consentId = consent.id,
                syncSpaceId = material.syncSpaceId,
                keyId = material.keyId,
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

    /** 停用云端处理并撤销本设备。密钥默认保留在本机密封存储，30 天保留期内重新同意仍可解密既有密文。 */
    suspend fun disableAndWithdraw(reason: String = "USER_REMOVED") {
        lifecycleMutex.withLock {
            val state = stateStore.load()
            if (!state.enabled) return@withLock
            val api = apiFactory { state.deviceId }
            val deviceId = state.deviceId
            if (!deviceId.isNullOrBlank()) {
                runCatching { api.revokeDevice(deviceId, reason) }
            }
            val consentId = state.consentId
            if (!consentId.isNullOrBlank()) {
                runCatching { api.withdrawConsent(consentId) }
            }
            // CT-07：撤回会使全部 device-bound cursor/ack watermark 失效；本地绝不能继续沿用。
            state.enabled = false
            state.cursor = null
            state.pendingWrites.clear()
            stateStore.save(state)
        }
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
