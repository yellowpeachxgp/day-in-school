package cn.edu.haut.campus.core.database

import android.content.Context
import cn.edu.haut.campus.core.campusservices.ParcelHubEngine
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.XiaomiAccountCredential
import cn.edu.haut.campus.core.campusservices.XiaomiAccountSecureVault
import cn.edu.haut.campus.core.campusservices.XiaomiApiSimpleResult
import cn.edu.haut.campus.core.campusservices.XiaomiExpressApi
import cn.edu.haut.campus.core.campusservices.XiaomiPassportClient
import cn.edu.haut.campus.core.campusservices.XiaomiSyncSummary
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ParcelHub 拆分期运行时端口。
 * 云同步编排仍由兼容外观的 Cloud/Sync 后续批次所有者提供；本批只保留原有异步触发时序。
 */
internal interface ParcelHubRuntimePort {
    fun launchCloudSync()
}

/**
 * 快递聚合、取件码解析合并、手动取件状态与小米智能助理账号/手机号绑定。
 * 实现从 CampusRepository 兼容门面逐字迁入；Room schema、通知原因与云同步触发时序保持不变。
 */
class ParcelHubRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val appContext: Context,
    private val xiaomiAccountVault: XiaomiAccountSecureVault,
    private val runtimePort: ParcelHubRuntimePort
) {
    private val cloudAndParcelDao = environment.database.cloudAndParcelDao()

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }

    // --- Parcel Hub (Multi-Source Express Aggregation, OTP Filter & Provenance Merge) ---
    val parcelItemsFlow: Flow<List<ParcelItemEntity>> =
        cloudAndParcelDao.observeParcelItems()

    val parcelSourceConnectionsFlow: Flow<List<ParcelSourceConnectionEntity>> =
        cloudAndParcelDao.observeParcelSourceConnections()

    suspend fun upsertParcelItemFromInput(
        trackingNumberRaw: String,
        carrierName: String = "校园快递",
        providerId: String = "parcel.manual",
        pickupCodeInput: String? = null,
        stationNameInput: String? = null,
        itemTitleInput: String? = null,
        rawNotificationText: String? = null
    ): Result<ParcelItemEntity> = runCatching {
        val spec = ParcelHubEngine.enforceProviderAuthority(providerId)
        val normalizedTracking = ParcelHubEngine.normalizeTrackingNumber(trackingNumberRaw)
        val extracted = ParcelHubEngine.resolvePickupCodeFromText(rawNotificationText)
        val effectivePickup = pickupCodeInput.cleanNullableLiteral() ?: extracted?.pickupCode.cleanNullableLiteral()
        val effectiveStation = stationNameInput.cleanNullableLiteral() ?: extracted?.stationName.cleanNullableLiteral()
        val effectiveTitle = itemTitleInput.cleanNullableLiteral()
        val now = System.currentTimeMillis()

        val existing = cloudAndParcelDao.getParcelByTrackingNumber(normalizedTracking)
        val cleanExistingPickup = existing?.pickupCode.cleanNullableLiteral()
        val mergedPickup = effectivePickup ?: cleanExistingPickup
        val nextStatus = if (!mergedPickup.isNullOrBlank()) {
            ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
        } else {
            existing?.status?.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName) ?: ParcelLifecycleStatus.IN_TRANSIT.wireName
        }

        val cleanCarrier = carrierName.cleanNullLiteral(existing?.carrierName.cleanNullLiteral("校园快递"))
        val entity = ParcelItemEntity(
            id = existing?.id ?: "prc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            trackingNumber = normalizedTracking,
            carrierCode = cleanCarrier.uppercase().take(8),
            carrierName = cleanCarrier,
            status = nextStatus,
            pickupCode = mergedPickup,
            stationName = effectiveStation ?: existing?.stationName.cleanNullableLiteral() ?: "莲花街北苑菜鸟驿站",
            itemTitle = effectiveTitle ?: existing?.itemTitle.cleanNullableLiteral(),
            estimatedDeliveryText = if (nextStatus == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                "已到达驿站/快递柜，请凭取件码取件"
            } else {
                "运输中，预计 1-2 日送达校园驿站"
            },
            sourcesJson = """[{"provider_id":"${spec.providerId}","priority":${spec.sourcePriority}}]""",
            fieldProvenanceJson = """{"provider_id":"${spec.providerId}","updated_at":$now}""",
            checkpointsJson = existing?.checkpointsJson ?: "[]",
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now
        )
        cloudAndParcelDao.upsertParcelItem(entity)
        notifyScheduleOrPolicyChanged("PARCEL_UPSERTED")
        runtimePort.launchCloudSync()
        entity
    }

    suspend fun markParcelPickedUp(parcelId: String) {
        val list = cloudAndParcelDao.getParcelItemsSnapshot()
        val found = list.firstOrNull { it.id == parcelId } ?: return
        cloudAndParcelDao.upsertParcelItem(
            found.copy(
                status = ParcelLifecycleStatus.PICKED_UP.wireName,
                estimatedDeliveryText = "本人已取件签收",
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
        notifyScheduleOrPolicyChanged("PARCEL_PICKED_UP")
        runtimePort.launchCloudSync()
    }

    suspend fun markAllStationParcelsPickedUp(stationName: String) {
        val now = System.currentTimeMillis()
        val list = cloudAndParcelDao.getParcelItemsSnapshot()
        var updatedCount = 0
        for (item in list) {
            if (item.status != ParcelLifecycleStatus.PICKED_UP.wireName &&
                (item.stationName ?: "校内菜鸟驿站") == stationName
            ) {
                cloudAndParcelDao.upsertParcelItem(
                    item.copy(
                        status = ParcelLifecycleStatus.PICKED_UP.wireName,
                        estimatedDeliveryText = "本人已取件签收",
                        updatedAtEpochMillis = now
                    )
                )
                updatedCount++
            }
        }
        if (updatedCount > 0) {
            notifyScheduleOrPolicyChanged("PARCEL_STATION_BATCH_PICKED_UP")
            runtimePort.launchCloudSync()
        }
    }

    suspend fun deleteParcelById(parcelId: String) {
        cloudAndParcelDao.deleteParcelItemById(parcelId)
        notifyScheduleOrPolicyChanged("PARCEL_DELETED")
    }

    // --- Xiaomi Smart Assistant Account, Phone Number SMS Binding & Express Sync ---
    fun getXiaomiAccounts(): List<XiaomiAccountCredential> =
        xiaomiAccountVault.readAccounts()

    suspend fun saveXiaomiAccountFromWebLogin(
        token: String,
        cUserId: String,
        accountId: String,
        detectedPhones: List<String>
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "小米 serviceToken 不能为空" }
            val effectiveAccountId = accountId.trim().ifBlank { cUserId.trim() }
            require(effectiveAccountId.isNotBlank()) { "小米账号标识不能为空" }
            val oaid = XiaomiExpressApi.resolveOaid(appContext, effectiveAccountId)
            val vaid = XiaomiExpressApi.resolveVaid(appContext, effectiveAccountId)
            val credId = "xiaomi_$effectiveAccountId"
            val cleanPhones = detectedPhones
                .map { it.trim() }
                .filter { XiaomiPassportClient.isValidMainlandPhone(it) }
                .distinct()
            val list = xiaomiAccountVault.upsertAccount(
                XiaomiAccountCredential(
                    id = credId,
                    label = "小米账号",
                    token = token.trim(),
                    cUserId = cUserId.trim().ifBlank { effectiveAccountId },
                    accountId = effectiveAccountId,
                    oaid = oaid,
                    vaid = vaid,
                    phones = cleanPhones,
                    enabled = true,
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            val saved = list.first { it.id == credId || it.accountId == effectiveAccountId }
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = saved.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${saved.label} · ${saved.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            saved
        }
    }

    suspend fun sendXiaomiPhoneVerificationCode(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiApiSimpleResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号，请先完成小米账号登录")
            XiaomiExpressApi.sendVerificationCode(appContext, cred, phone)
        }
    }

    suspend fun verifyAndBindXiaomiPhone(
        accountIdOrId: String,
        phone: String,
        verificationCode: String
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = phone.trim()
            require(XiaomiPassportClient.isValidMainlandPhone(cleanPhone)) {
                "请输入有效的 11 位中国大陆手机号"
            }
            require(verificationCode.trim().isNotEmpty()) {
                "请输入短信验证码"
            }
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号")
            val checkRes = XiaomiExpressApi.checkVerificationCode(
                appContext,
                cred,
                cleanPhone,
                verificationCode.trim()
            )
            if (!checkRes.ok) {
                throw IllegalStateException("验证码校验失败: ${checkRes.message}")
            }
            val nextPhones = (cred.phones + cleanPhone).distinct()
            val bindRes = XiaomiExpressApi.bindPhone(
                appContext,
                cred,
                cleanPhone,
                nextPhones,
                bind = true
            )
            if (!bindRes.ok) {
                throw IllegalStateException("绑定手机号失败: ${bindRes.message}")
            }
            val updated = xiaomiAccountVault.updateAccountPhones(cred.id, nextPhones)
                ?: throw IllegalStateException("更新本地手机号列表失败")
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = updated.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${updated.label} · ${updated.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            updated
        }
    }

    suspend fun addXiaomiPhoneDirectly(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = phone.trim()
            require(XiaomiPassportClient.isValidMainlandPhone(cleanPhone)) {
                "请输入有效的 11 位中国大陆手机号"
            }
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号")
            val nextPhones = (cred.phones + cleanPhone).distinct()
            runCatching {
                XiaomiExpressApi.bindPhone(appContext, cred, cleanPhone, nextPhones, bind = true)
            }
            val updated = xiaomiAccountVault.updateAccountPhones(cred.id, nextPhones)
                ?: throw IllegalStateException("保存收件手机号失败")
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = updated.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${updated.label} · ${updated.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            updated
        }
    }

    suspend fun unbindXiaomiPhone(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = phone.trim()
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号")
            val remaining = cred.phones.filterNot { it == cleanPhone }
            runCatching {
                XiaomiExpressApi.bindPhone(appContext, cred, cleanPhone, remaining, bind = false)
            }
            val updated = xiaomiAccountVault.updateAccountPhones(cred.id, remaining)
                ?: throw IllegalStateException("解绑手机号更新失败")
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = updated.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${updated.label} · ${updated.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            updated
        }
    }

    suspend fun removeXiaomiAccount(accountIdOrId: String) = withContext(Dispatchers.IO) {
        val current = xiaomiAccountVault.readAccounts()
            .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
        xiaomiAccountVault.removeAccount(accountIdOrId)
        if (current != null) {
            cloudAndParcelDao.deleteParcelSourceConnection(current.id)
        } else {
            cloudAndParcelDao.deleteParcelSourceConnection(accountIdOrId)
        }
    }

    suspend fun syncXiaomiExpressPackages(): Result<XiaomiSyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val accounts = xiaomiAccountVault.readAccounts().filter { it.enabled }
            if (accounts.isEmpty()) {
                throw IllegalStateException("尚未登录小米账号，请先点击「手机号/扫码登录小米账号」")
            }
            val activeWithPhones = accounts.filter { it.phones.isNotEmpty() }
            if (activeWithPhones.isEmpty()) {
                throw IllegalStateException("已登录小米账号，但尚未绑定收件手机号。请点击「绑定/管理收件手机号」添加手机号后再同步")
            }
            var totalFetched = 0
            var readyCount = 0
            val now = System.currentTimeMillis()
            for (cred in activeWithPhones) {
                val remoteItems = XiaomiExpressApi.fetchExpressList(appContext, cred)
                for (dto in remoteItems) {
                    val normalizedTracking = runCatching {
                        ParcelHubEngine.normalizeTrackingNumber(dto.mailNo)
                    }.getOrElse { dto.mailNo.trim().uppercase() }
                    val existing = cloudAndParcelDao.getParcelByTrackingNumber(normalizedTracking)
                    val mergedPickup = dto.pickupCode.cleanNullableLiteral() ?: existing?.pickupCode.cleanNullableLiteral()
                    val mergedStation = dto.stationName.cleanNullableLiteral()
                        ?: existing?.stationName.cleanNullableLiteral()
                        ?: "校园快递服务点"
                    val effectiveStatus = when {
                        existing?.status == ParcelLifecycleStatus.PICKED_UP.wireName ->
                            ParcelLifecycleStatus.PICKED_UP.wireName
                        !mergedPickup.isNullOrBlank() && dto.lifecycleStatus != ParcelLifecycleStatus.PICKED_UP ->
                            ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
                        else -> dto.lifecycleStatus.wireName
                    }
                    if (effectiveStatus == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                        readyCount++
                    }
                    val checkpointsArr = org.json.JSONArray()
                    for (pt in dto.checkpoints) {
                        checkpointsArr.put(
                            org.json.JSONObject()
                                .put("desc", pt.desc.cleanNullLiteral())
                                .put("time", pt.time.cleanNullLiteral())
                        )
                    }
                    val provenanceObj = org.json.JSONObject()
                        .put("provider_id", "parcel.xiaomi.personal-assistant")
                        .put("xiaomi_account_id", cred.id)
                        .put("cp_code", dto.cpCode.cleanNullLiteral())
                        .put("provider", dto.provider.cleanNullLiteral())
                        .put("state_num", dto.stateNum)
                        .put("latest_time", dto.latestTime.cleanNullLiteral())
                        .put("phone", dto.phone.cleanNullableLiteral() ?: cred.phones.firstOrNull().orEmpty())
                        .put("query_channel", dto.queryChannel.cleanNullLiteral())
                        .put("updated_at", now)

                    val cleanCompany = dto.companyName.cleanNullLiteral(existing?.carrierName.cleanNullLiteral("校园快递"))
                    val cleanStateLabel = dto.stateName.cleanNullLiteral(dto.lifecycleStatus.label)
                    val entity = ParcelItemEntity(
                        id = existing?.id ?: "prc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                        trackingNumber = normalizedTracking,
                        carrierCode = dto.cpCode.cleanNullLiteral("XIAOMI").uppercase().take(12),
                        carrierName = cleanCompany,
                        status = effectiveStatus,
                        pickupCode = mergedPickup,
                        stationName = mergedStation,
                        itemTitle = existing?.itemTitle.cleanNullableLiteral() ?: "$cleanCompany · $cleanStateLabel",
                        estimatedDeliveryText = dto.latestText.cleanNullLiteral().ifBlank {
                            if (dto.latestTime.cleanNullLiteral().isNotBlank()) "更新于 ${dto.latestTime.cleanNullLiteral()}" else "来自小米智能助理"
                        },
                        sourcesJson = """[{"provider_id":"parcel.xiaomi.personal-assistant","priority":95,"account":"${cred.id}"}]""",
                        fieldProvenanceJson = provenanceObj.toString(),
                        checkpointsJson = if (checkpointsArr.length() > 0) checkpointsArr.toString() else (existing?.checkpointsJson ?: "[]"),
                        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                        updatedAtEpochMillis = now
                    )
                    cloudAndParcelDao.upsertParcelItem(entity)
                    totalFetched++
                }
            }
            val totalPhones = activeWithPhones.sumOf { it.phones.size }
            notifyScheduleOrPolicyChanged("XIAOMI_PARCEL_SYNCED")
            runtimePort.launchCloudSync()
            XiaomiSyncSummary(
                accountCount = activeWithPhones.size,
                phoneCount = totalPhones,
                fetchedParcelCount = totalFetched,
                readyPickupCount = readyCount,
                message = "小米智能助理同步成功：已查询 ${activeWithPhones.size} 个账号（共 $totalPhones 个手机号），同步 $totalFetched 件快递（待取 $readyCount 件）"
            )
        }
    }

    suspend fun fetchXiaomiParcelDetail(parcelId: String): Result<List<XiaomiTrackPoint>> = withContext(Dispatchers.IO) {
        runCatching {
            val parcel = cloudAndParcelDao.getParcelItemsSnapshot().firstOrNull { it.id == parcelId }
                ?: throw IllegalArgumentException("未找到该包裹记录")
            val prov = runCatching { org.json.JSONObject(parcel.fieldProvenanceJson) }.getOrNull()
            val accountId = prov?.optString("xiaomi_account_id").orEmpty()
            val accounts = xiaomiAccountVault.readAccounts()
            val cred = accounts.firstOrNull { it.id == accountId || it.accountId == accountId }
                ?: accounts.firstOrNull { it.enabled && it.phones.isNotEmpty() }
                ?: throw IllegalStateException("未找到可用的小米登录账号")
            val detail = XiaomiExpressApi.fetchExpressDetail(
                context = appContext,
                cred = cred,
                mailNo = parcel.trackingNumber,
                cpCode = prov?.optString("cp_code")?.takeIf { it.isNotBlank() } ?: parcel.carrierCode,
                companyName = parcel.carrierName,
                provider = prov?.optString("provider"),
                stateNum = prov?.optInt("state_num", 0) ?: 0,
                latestTime = prov?.optString("latest_time"),
                phone = prov?.optString("phone")?.takeIf { it.isNotBlank() } ?: cred.phones.firstOrNull(),
                queryChannel = prov?.optString("query_channel")
            )
            if (detail.points.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for (pt in detail.points) {
                    arr.put(org.json.JSONObject().put("desc", pt.desc).put("time", pt.time))
                }
                val latestDesc = detail.points.firstOrNull()?.desc ?: parcel.estimatedDeliveryText
                val extracted = ParcelHubEngine.resolvePickupCodeFromText(
                    detail.points.joinToString(" ") { it.desc }
                )
                cloudAndParcelDao.upsertParcelItem(
                    parcel.copy(
                        pickupCode = extracted?.pickupCode ?: parcel.pickupCode,
                        stationName = extracted?.stationName ?: parcel.stationName,
                        status = when {
                            detail.isReceived -> ParcelLifecycleStatus.PICKED_UP.wireName
                            extracted != null -> ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
                            else -> parcel.status
                        },
                        estimatedDeliveryText = latestDesc,
                        checkpointsJson = arr.toString(),
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
            detail.points
        }
    }
}
