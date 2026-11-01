package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/** 一次录制好的交换。URL 支持结尾或中段的 * 通配，便于表达分页与菜单 ID 变化。 */
data class RecordedExchange(
    val method: HttpMethod,
    val url: String,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
) {
    fun matches(request: GatewayRequest): Boolean =
        method == request.method && wildcardMatch(url, request.url)

    private fun wildcardMatch(pattern: String, actual: String): Boolean {
        val parts = pattern.split("*")
        if (parts.size == 1) return pattern == actual
        if (!actual.startsWith(parts.first())) return false
        if (!actual.endsWith(parts.last())) return false
        var cursor = parts.first().length
        for (index in 1 until parts.size - 1) {
            val part = parts[index]
            if (part.isEmpty()) continue
            val found = actual.indexOf(part, cursor)
            if (found < 0) return false
            cursor = found + part.length
        }
        return cursor <= actual.length - parts.last().length
    }
}

/**
 * 固定夹具回放。只读、离线、确定性；命中顺序按录制顺序消费，
 * 未命中的请求直接失败，避免测试在缺少夹具时静默通过。
 */
class ReplayTransport(exchanges: List<RecordedExchange>) : GatewayTransport {
    private val pending = exchanges.toMutableList()
    val consumed = mutableListOf<GatewayRequest>()

    override suspend fun execute(request: GatewayRequest): GatewayResponse {
        val index = pending.indexOfFirst { it.matches(request) }
        if (index < 0) {
            throw TransportFailure(
                AcademicError.SOURCE_DATA_INVALID,
                "缺少夹具：" + request.method.name + " " + request.url
            )
        }
        val exchange = pending.removeAt(index)
        consumed += request
        return GatewayResponse(
            status = exchange.status,
            headers = exchange.headers,
            body = exchange.body,
            finalUrl = request.url
        )
    }

    fun remaining(): Int = pending.size

    companion object {
        fun fromJson(text: String): ReplayTransport {
            val root = Json.parse(text)
            val exchanges = root.arr("exchanges").orEmpty().map { item ->
                RecordedExchange(
                    method = HttpMethod.valueOf(item.str("method")?.uppercase() ?: "GET"),
                    url = item.str("url") ?: throw IllegalArgumentException("夹具缺少 url"),
                    status = item.obj("status")?.intOrNull() ?: 200,
                    headers = item.obj("headers")?.asObjOrNull?.fields?.mapValues { entry ->
                        (entry.value as? JsonValue.Str)?.value ?: ""
                    }.orEmpty(),
                    body = item.str("body").orEmpty()
                )
            }
            return ReplayTransport(exchanges)
        }

        fun fromResource(path: String): ReplayTransport {
            val loader = ReplayTransport::class.java.classLoader
            val stream = loader.getResourceAsStream(path)
                ?: throw IllegalArgumentException("夹具资源不存在：" + path)
            val text = stream.use { String(it.readBytes(), Charsets.UTF_8) }
            return fromJson(text)
        }
    }
}
