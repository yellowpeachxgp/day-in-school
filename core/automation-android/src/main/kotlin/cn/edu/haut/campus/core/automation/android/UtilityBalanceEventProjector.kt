package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.automation.AutomationKeys
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 把在线水电缓存投影成 utility.balance.sampled outbox 事实。
 *
 * 只处理明确可解析的在线余额数字。现有万校协议按“度/吨”等表计原生单位返回余额；
 * DomainEvent schema 的 balanceCents 在本首期中承载“原生单位 ×100”的整数化数值，
 * 绝不按未知电价/水价伪造成货币金额。手工表计单位未纳入统一 metric，绝不猜测。
 * 同一表计余额未变化时不重复发事件，变化事件由 outbox at-least-once 驱动阈值规则。
 */
/** 从 domain_outbox 还原同一账号的水电采样历史，供阈值进入边沿判断。 */
class RoomUtilityBalanceHistoryProvider(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope
) {
    private val codecRegistry = DomainEventCodecRegistry.DEFAULT

    suspend fun samples(): List<DomainEvent.UtilityBalanceSampled> =
        database.vaultDao().getOutboxSnapshot().mapNotNull { row ->
            if (row.eventType != "utility.balance.sampled") return@mapNotNull null
            runCatching {
                codecRegistry.decode(
                    cn.edu.haut.campus.core.events.CanonicalEventEnvelopeCodec.decode(
                        cn.edu.haut.campus.core.events.CanonicalJson.of(row.envelopeJson)
                    )
                ) as? DomainEvent.UtilityBalanceSampled
            }.getOrNull()?.takeIf { it.scope == scope }
        }
}

class UtilityBalanceEventProjector(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now,
    private val outbox: RoomEventOutbox = RoomEventOutbox(database)
) {
    private val codecRegistry = DomainEventCodecRegistry.DEFAULT

    suspend fun publishChangedBalances(): Int {
        val existing = latestBalancesByMeter()
        var published = 0
        val now = clock()
        for (room in database.utilityAndFocusDao().getOnlineRoomsSnapshot()) {
            if (room.failed) continue
            val devices = runCatching { decodeOnlineDevicesJson(room.devicesJson) }.getOrDefault(emptyList())
            for (device in devices) {
                val balanceCents = parseBalanceCents(device.balanceRaw) ?: continue
                val meterId = "online/" + device.category.name.lowercase() + "/" + room.roomId + "/" + device.name
                if (existing[meterId] == balanceCents) continue
                val updatedAt = Instant.ofEpochMilli(room.updatedAtEpochMs)
                val event = DomainEvent.UtilityBalanceSampled(
                    eventId = eventIdFor(meterId, balanceCents, room.updatedAtEpochMs),
                    scope = scope,
                    occurredAt = now,
                    meterRef = EntityRef(EntityType.UTILITY_METER, meterId, scope),
                    balanceCents = balanceCents,
                    estimatedDaysLeft = null,
                    provenance = DataProvenance(
                        providerId = "wanxiao.utility",
                        schoolId = scope.schoolId,
                        accountId = scope.accountId,
                        sourceSystem = "online-utility-cache",
                        sourceRecordId = meterId,
                        fetchedAt = updatedAt,
                        completeness = SnapshotCompleteness.UNKNOWN,
                        trust = VerificationLevel.SOURCE_CONFIRMED,
                        schemaVersion = 1
                    )
                )
                outbox.append(codecRegistry.encode(event))
                published++
            }
        }
        return published
    }

    private suspend fun latestBalancesByMeter(): Map<String, Long> {
        return database.vaultDao().getOutboxSnapshot().mapNotNull { row ->
            if (row.eventType != "utility.balance.sampled") return@mapNotNull null
            val decoded = runCatching {
                codecRegistry.decode(
                    cn.edu.haut.campus.core.events.CanonicalEventEnvelopeCodec.decode(
                        cn.edu.haut.campus.core.events.CanonicalJson.of(row.envelopeJson)
                    )
                ) as? DomainEvent.UtilityBalanceSampled
            }.getOrNull() ?: return@mapNotNull null
            if (decoded.scope == scope) decoded else null
        }.groupBy { it.meterRef.id }
            .mapValues { (_, events) -> events.maxByOrNull { it.occurredAt }?.balanceCents ?: Long.MIN_VALUE }
    }

    private fun parseBalanceCents(raw: String): Long? {
        val match = NUMBER_REGEX.find(raw) ?: return null
        return runCatching {
            BigDecimal(match.groupValues[1])
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
                .takeIf { it >= 0L }
        }.getOrNull()
    }

    private fun eventIdFor(meterId: String, balanceCents: Long, updatedAtEpochMs: Long): String =
        "utility-" + AutomationKeys.sha256Hex(meterId + "|" + balanceCents + "|" + updatedAtEpochMs).substring(0, 32)

    private companion object {
        val NUMBER_REGEX = Regex("(-?[0-9]+(?:[.][0-9]+)?)")
    }
}
