package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral

/**
 * W0-05 个人数据生命周期编排器。
 * 启动 null-literal 清洗从 CampusRepository 兼容门面逐字迁入；后续全量擦除继续归入本类。
 */
class ProfileDataWiper internal constructor(
    private val preferences: DataStore<Preferences>,
    private val cloudAndParcelDao: CloudAndParcelDao
) {
    internal suspend fun sanitizePersistedNullLiteralsForFacade(
        sanitizeBookingPlanWriteHookForTest: (suspend (String) -> Unit)? = null
    ) = sanitizePersistedNullLiterals(sanitizeBookingPlanWriteHookForTest)

    private suspend fun sanitizePersistedNullLiterals(sanitizeBookingPlanWriteHookForTest: (suspend (String) -> Unit)? = null) {
        val parcels = cloudAndParcelDao.getParcelItemsSnapshot()
        for (p in parcels) {
            val cleanPickup = p.pickupCode.cleanNullableLiteral()
            val cleanStation = p.stationName.cleanNullableLiteral() ?: "莲花街北苑菜鸟驿站"
            val cleanCarrier = p.carrierName.cleanNullLiteral("校园快递")
            val cleanTitle = p.itemTitle.cleanNullableLiteral()
            val nextStatus = if (p.pickupCode != null && cleanPickup == null && p.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                ParcelLifecycleStatus.IN_TRANSIT.wireName
            } else {
                p.status.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName)
            }
            val cleanEstimated = p.estimatedDeliveryText.cleanNullLiteral(
                when (nextStatus) {
                    ParcelLifecycleStatus.PICKED_UP.wireName -> "本人已取件签收"
                    ParcelLifecycleStatus.READY_FOR_PICKUP.wireName -> "已到达驿站/快递柜，请凭取件码取件"
                    else -> "运输中，预计 1-2 日送达校园驿站"
                }
            )
            if (p.pickupCode != cleanPickup ||
                p.stationName != cleanStation ||
                p.carrierName != cleanCarrier ||
                p.itemTitle != cleanTitle ||
                p.status != nextStatus ||
                p.estimatedDeliveryText != cleanEstimated
            ) {
                cloudAndParcelDao.upsertParcelItem(
                    p.copy(
                        carrierName = cleanCarrier,
                        status = nextStatus,
                        pickupCode = cleanPickup,
                        stationName = cleanStation,
                        itemTitle = cleanTitle,
                        estimatedDeliveryText = cleanEstimated
                    )
                )
            }
        }

        val plans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
        for (plan in plans) {
            val cleanArea = plan.preferredAreaName.cleanNullLiteral("五层中区")
            val cleanSeat = plan.preferredSeatLabel.cleanNullLiteral("001")
            val cleanExtId = plan.externalBookingId.cleanNullableLiteral()
            val cleanEvidence = plan.evidenceSha256.cleanNullableLiteral()
            val cleanDiag = plan.lastDiagnosticMessage.cleanNullLiteral()
            if (plan.preferredAreaName != cleanArea || plan.preferredSeatLabel != cleanSeat ||
                plan.externalBookingId != cleanExtId || plan.evidenceSha256 != cleanEvidence ||
                plan.lastDiagnosticMessage != cleanDiag
            ) {
                sanitizeBookingPlanWriteHookForTest?.invoke("before_write")
                cloudAndParcelDao.updateLibraryBookingPlanNullLiteralFieldsIfVersionUnchanged(
                    id = plan.id, expectedStateVersion = plan.stateVersion, expectedGeneration = plan.generation,
                    expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
                    preferredAreaName = cleanArea, preferredSeatLabel = cleanSeat, externalBookingId = cleanExtId,
                    evidenceSha256 = cleanEvidence, lastDiagnosticMessage = cleanDiag
                )
                sanitizeBookingPlanWriteHookForTest?.invoke("after_write")
            }
        }

        // Purge any legacy sample plugins or unapproved sample moments records
        runCatching {
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.hello-campus")
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.parcel-station")
        }
        val legacyMomentsSampleIds = setOf(
            "mp_found_card_01",
            "mp_lost_airpods_02",
            "mp_found_keys_03",
            "mp_lost_cup_04",
            "mb_math_01",
            "mb_ds_02",
            "mb_circuit_03",
            "mb_food_04",
            "tag_default_01"
        )
        preferences.edit { prefs ->
            val rawMoments = prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
            if (!rawMoments.isNullOrBlank()) {
                val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                if (parsed != null) {
                    val cleanedSnap = parsed.copy(
                        posts = parsed.posts.filterNot { it.id in legacyMomentsSampleIds },
                        bookListings = parsed.bookListings.filterNot { it.id in legacyMomentsSampleIds },
                        antiLostTags = parsed.antiLostTags.filterNot { it.id in legacyMomentsSampleIds || it.tagCode == "HAUT-TAG-7E2A9B" }
                    )
                    prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(cleanedSnap)
                }
            }
        }
    }
}
