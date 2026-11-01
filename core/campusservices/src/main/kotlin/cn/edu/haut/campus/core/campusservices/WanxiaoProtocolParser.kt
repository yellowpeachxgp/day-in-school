/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.campusservices

import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 表计业务分类：严格只识别公开协议确认的电表(0)与水表(1)。
 */
enum class UtilityMeterCategory(val code: String, val displayLabel: String) {
    ELECTRICITY("0", "电"),
    WATER("1", "水");

    companion object {
        fun fromProtocolCode(raw: String): UtilityMeterCategory? = when (raw.trim()) {
            "0" -> ELECTRICITY
            "1" -> WATER
            else -> null
        }
    }
}

/**
 * 协议解析出的已绑定房间凭据（仅存在于内存中，roomVerify 严禁落盘持久化）。
 */
data class WanxiaoBoundRoomCredential(
    val roomVerify: String,
    val roomName: String
) {
    val stableRoomId: String = MessageDigest.getInstance("SHA-256")
        .digest(roomVerify.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * 在线表计读数快照（数值与单位严格按服务端原文保留）。
 */
data class OnlineUtilityDevice(
    val name: String,
    val category: UtilityMeterCategory,
    val balanceRaw: String,
    val todayUseRaw: String,
    val totalBoughtRaw: String,
    val statusRaw: String
)

/**
 * 在线房间查询结果快照。
 */
data class OnlineUtilityRoomSnapshot(
    val roomId: String,
    val roomName: String,
    val devices: List<OnlineUtilityDevice>,
    val fetchedAtEpochMs: Long,
    val failed: Boolean = false
)

/**
 * 完美校园·智能水电协议纯函数解析器。
 */
object WanxiaoProtocolParser {

    const val MAX_BOUND_ROOMS = 20
    const val MAX_DEVICES_PER_ROOM = 50
    const val MISSING_PLACEHOLDER = "—"

    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    private val ROOM_NAME_CANDIDATE_KEYS = listOf(
        "roomfullname",
        "roomname",
        "roomName",
        "room_name",
        "room",
        "dormname",
        "dormName"
    )

    private val ELECTRIC_TODAY_USE_REGEX = Regex(
        "^\\s*(\\d+(?:\\.\\d+)?)\\s*(度|kWh|kW·h|kW\\.h)?\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun formatRequestTimestamp(instant: Instant = Instant.now()): String {
        return ZonedDateTime.ofInstant(instant, HAUT_ZONE_ID)
            .format(TIMESTAMP_FORMATTER) + "000"
    }

    fun buildGetBindRoomPayload(account: String): JSONObject {
        val clean = UtilityAccountSecureStore.validateAccountFormat(account)
        return JSONObject()
            .put("cmd", "getbindroom")
            .put("account", clean)
    }

    fun buildRoomIndexPayload(
        account: String,
        roomVerify: String,
        timestamp: String = formatRequestTimestamp()
    ): JSONObject {
        val cleanAccount = UtilityAccountSecureStore.validateAccountFormat(account)
        val cleanVerify = roomVerify.trim()
        require(cleanVerify.isNotBlank() && cleanVerify != "null") { "房间缺少查询凭据" }
        require(timestamp.matches(Regex("^\\d{17}$"))) { "时间戳格式非法" }
        return JSONObject()
            .put("cmd", "h5_getstuindexpage")
            .put("account", cleanAccount)
            .put("roomverify", cleanVerify)
            .put("timestamp", timestamp)
    }

    fun parseEnvelopeBody(rawResponseText: String): JSONObject {
        val outer = runCatching { JSONObject(rawResponseText) }.getOrElse {
            throw IllegalArgumentException("智能水电响应格式变化，尚未读取数据")
        }
        val outerCodeOk = outer.optString("code_") == "0"
        val outerResultOk = outer.optString("result_").equals("true", ignoreCase = true)
        require(outerCodeOk && outerResultOk) {
            "智能水电返回失败，请在官方 App 确认账号和绑定状态"
        }

        val rawBody = outer.opt("body")
        val bodyObj = when (rawBody) {
            is JSONObject -> rawBody
            is String -> runCatching { JSONObject(rawBody) }.getOrElse {
                throw IllegalArgumentException("智能水电业务数据无法解析")
            }
            else -> throw IllegalArgumentException("智能水电业务数据无法解析")
        }

        require(bodyObj.optString("result") == "0") {
            "暂未读取到水电数据，请在完美校园确认绑定，或等待接口适配"
        }
        return bodyObj
    }

    fun parseBoundRooms(body: JSONObject): List<WanxiaoBoundRoomCredential> {
        val entries: List<JSONObject> = when (body.optString("existflag")) {
            "0" -> return emptyList()
            "1" -> listOf(body)
            "2" -> {
                val roomList = body.optJSONArray("roomlist")
                    ?: throw IllegalArgumentException("房间列表格式变化")
                require(roomList.length() in 1..MAX_BOUND_ROOMS) { "房间数量异常" }
                (0 until roomList.length()).map { index ->
                    roomList.optJSONObject(index)
                        ?: throw IllegalArgumentException("房间列表项格式异常")
                }
            }
            else -> throw IllegalArgumentException("房间绑定状态无法识别，请在官方 App 确认")
        }

        return entries.mapIndexed { index, row ->
            val verification = row.optString("roomverify").trim()
            require(verification.isNotBlank() && !verification.equals("null", ignoreCase = true)) {
                "房间缺少查询凭据"
            }
            val roomName = ROOM_NAME_CANDIDATE_KEYS
                .asSequence()
                .map { key -> if (row.isNull(key)) "" else row.optString(key).trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: "绑定房间 ${index + 1}"

            WanxiaoBoundRoomCredential(
                roomVerify = verification,
                roomName = roomName
            )
        }.distinctBy { it.roomVerify }
    }

    fun parseDevices(body: JSONObject): List<OnlineUtilityDevice> {
        val list = body.optJSONArray("modlist")
            ?: throw IllegalArgumentException("服务未返回设备列表")
        require(list.length() <= MAX_DEVICES_PER_ROOM) { "表计数量异常" }

        fun sanitizeField(row: JSONObject, key: String): String {
            if (row.isNull(key)) return MISSING_PLACEHOLDER
            val text = row.optString(key).trim()
            return if (text.isBlank() || text.equals("null", ignoreCase = true)) {
                MISSING_PLACEHOLDER
            } else {
                text
            }
        }

        return (0 until list.length()).mapNotNull { index ->
            val row = list.optJSONObject(index) ?: return@mapNotNull null
            val rawType = row.optString("bussnesstype").trim()
                .ifBlank { row.optString("businesstype").trim() }
            val category = UtilityMeterCategory.fromProtocolCode(rawType) ?: return@mapNotNull null

            OnlineUtilityDevice(
                name = sanitizeField(row, "devicename"),
                category = category,
                balanceRaw = sanitizeField(row, "odd"),
                todayUseRaw = sanitizeField(row, "todayuse"),
                totalBoughtRaw = sanitizeField(row, "sumbuy"),
                statusRaw = sanitizeField(row, "status")
            )
        }
    }

    fun parseElectricTodayUseKwh(rawTodayUse: String): Pair<Double, String>? {
        val match = ELECTRIC_TODAY_USE_REGEX.matchEntire(rawTodayUse) ?: return null
        val number = match.groupValues[1].toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        return number to "kWh"
    }
}

/**
 * 智能水电只读查询客户端。
 */
class WanxiaoUtilityClient(
    private val transport: UtilityTransport = WanxiaoFormPostTransport(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val BATCH_QUERY_TIMEOUT_MS = 45_000L
    }

    suspend fun queryOwnBoundRooms(
        account: String,
        allowUnverifiedTransport: Boolean = false
    ): List<OnlineUtilityRoomSnapshot> {
        val validAccount = UtilityAccountSecureStore.validateAccountFormat(account)

        val bindRoomResponseText = transport.postForm(
            method = "getbindroom",
            payload = WanxiaoProtocolParser.buildGetBindRoomPayload(validAccount),
            allowUnverifiedTransport = allowUnverifiedTransport
        )
        val bindRoomBody = WanxiaoProtocolParser.parseEnvelopeBody(bindRoomResponseText)
        val boundRooms = WanxiaoProtocolParser.parseBoundRooms(bindRoomBody)
        require(boundRooms.isNotEmpty()) {
            "未找到绑定房间，请先在完美校园“智能水电”绑定自己的宿舍。"
        }
        require(boundRooms.size <= WanxiaoProtocolParser.MAX_BOUND_ROOMS) {
            "返回房间数异常，已停止查询"
        }

        val batchStartMs = clockMs()
        return boundRooms.map { credential ->
            currentCoroutineContext().ensureActive()
            val nowMs = clockMs()
            if (nowMs - batchStartMs > BATCH_QUERY_TIMEOUT_MS) {
                OnlineUtilityRoomSnapshot(
                    roomId = credential.stableRoomId,
                    roomName = credential.roomName,
                    devices = emptyList(),
                    fetchedAtEpochMs = 0L,
                    failed = true
                )
            } else {
                try {
                    val timestamp = WanxiaoProtocolParser.formatRequestTimestamp(
                        Instant.ofEpochMilli(nowMs)
                    )
                    val detailResponseText = transport.postForm(
                        method = "h5_getstuindexpage",
                        payload = WanxiaoProtocolParser.buildRoomIndexPayload(
                            account = validAccount,
                            roomVerify = credential.roomVerify,
                            timestamp = timestamp
                        ),
                        allowUnverifiedTransport = allowUnverifiedTransport
                    )
                    val detailBody = WanxiaoProtocolParser.parseEnvelopeBody(detailResponseText)
                    val devices = WanxiaoProtocolParser.parseDevices(detailBody)
                    OnlineUtilityRoomSnapshot(
                        roomId = credential.stableRoomId,
                        roomName = credential.roomName,
                        devices = devices,
                        fetchedAtEpochMs = clockMs(),
                        failed = false
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    OnlineUtilityRoomSnapshot(
                        roomId = credential.stableRoomId,
                        roomName = credential.roomName,
                        devices = emptyList(),
                        fetchedAtEpochMs = 0L,
                        failed = true
                    )
                }
            }
        }
    }
}
