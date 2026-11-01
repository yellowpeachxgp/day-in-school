package cn.edu.haut.campus.core.campusservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 智能水电传输层验证与门控状态。
 */
enum class UtilityTransportVerificationState(val label: String, val description: String) {
    TRANSPORT_UNVERIFIED(
        label = "待实机核验",
        description = "河南工业大学智能水电租户代码(43)与 POST 请求体传输需本人已绑房间核实；手工记录始终可用。"
    ),
    VERIFIED_ACTIVE(
        label = "已核验可用",
        description = "已在当前设备完成智能水电已绑房间实测核验。"
    ),
    DISABLED_BY_POLICY(
        label = "已停用在线传输",
        description = "在线查询已关闭，仅使用本地手工记录。"
    )
}

sealed class UtilityTransportException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {

    class UnverifiedTransportBlockedException : UtilityTransportException(
        "智能水电在线接口处于待核验状态，请先开启在线联调开关，或使用下方手工记录。"
    )

    class TransportDisabledException : UtilityTransportException(
        "智能水电在线传输已停用，请使用手工读数记录。"
    )

    class SecurityViolationException(reason: String) : UtilityTransportException(
        "安全传输策略拦截：$reason"
    )

    class NetworkOrHttpException(userMessage: String, val httpStatus: Int? = null) :
        UtilityTransportException(userMessage)
}

fun interface UtilityTransport {
    suspend fun postForm(
        method: String,
        payload: JSONObject,
        allowUnverifiedTransport: Boolean
    ): String
}

/**
 * 基于 HttpURLConnection 的安全 POST Body 传输实现。
 *
 * 安全不变量：
 * 1. 严禁在 URL 中拼接任何 query string（URL 恒定为纯 HTTPS 路径）。
 * 2. 强制使用 POST + application/x-www-form-urlencoded; charset=UTF-8 请求体写入 customercode/method/param。
 * 3. 禁用自动重定向、禁用 HTTP 缓存，响应体严格限制在 1,000,000 字符以内。
 */
class WanxiaoFormPostTransport(
    private val endpointUrl: String = WANXIAO_ENDPOINT_URL,
    private val customerCode: String = HAUT_CUSTOMER_CODE,
    @Volatile var verificationState: UtilityTransportVerificationState =
        UtilityTransportVerificationState.TRANSPORT_UNVERIFIED,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : UtilityTransport {

    companion object {
        const val WANXIAO_ENDPOINT_URL =
            "https://xqh5.17wanxiao.com/smartWaterAndElectricityService/SWAEServlet"
        const val HAUT_CUSTOMER_CODE = "43"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_RESPONSE_CHARS = 1_000_000

        fun encodeFormBody(
            customerCode: String,
            method: String,
            paramJson: String
        ): ByteArray {
            val encodedPairs = listOf(
                "customercode" to customerCode,
                "method" to method,
                "param" to paramJson
            ).joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            return encodedPairs.toByteArray(StandardCharsets.UTF_8)
        }

        fun validateCleanHttpsEndpoint(rawUrl: String): URL {
            val endpoint = OfficialServiceRoutePolicy.parseHttpsEndpoint(rawUrl)
                ?: throw UtilityTransportException.SecurityViolationException("非法的 HTTPS 服务端点地址")
            if (endpoint.host != OfficialServiceRoutePolicy.WANXIAO_HOST) {
                throw UtilityTransportException.SecurityViolationException("非授权的水电服务域名")
            }
            if (endpoint.uri.rawQuery != null || rawUrl.contains('?')) {
                throw UtilityTransportException.SecurityViolationException("严禁在 URL 中携带查询参数")
            }
            return endpoint.uri.toURL()
        }
    }

    override suspend fun postForm(
        method: String,
        payload: JSONObject,
        allowUnverifiedTransport: Boolean
    ): String = withContext(Dispatchers.IO) {
        when (verificationState) {
            UtilityTransportVerificationState.DISABLED_BY_POLICY ->
                throw UtilityTransportException.TransportDisabledException()

            UtilityTransportVerificationState.TRANSPORT_UNVERIFIED ->
                if (!allowUnverifiedTransport) {
                    throw UtilityTransportException.UnverifiedTransportBlockedException()
                }

            UtilityTransportVerificationState.VERIFIED_ACTIVE -> Unit
        }

        require(method == "getbindroom" || method == "h5_getstuindexpage") {
            "仅支持只读水电查询方法"
        }

        val targetUrl = validateCleanHttpsEndpoint(endpointUrl)
        val bodyBytes = encodeFormBody(
            customerCode = customerCode,
            method = method,
            paramJson = payload.toString()
        )

        val connection = connectionFactory(targetUrl)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw UtilityTransportException.NetworkOrHttpException(
                    userMessage = "智能水电服务暂时不可用（HTTP $code），请稍后重试",
                    httpStatus = code
                )
            }

            connection.inputStream.let { stream ->
                InputStreamReader(stream, StandardCharsets.UTF_8).buffered().use { reader ->
                    val output = StringBuilder()
                    val chunk = CharArray(4096)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        if (output.length + count > MAX_RESPONSE_CHARS) {
                            throw UtilityTransportException.SecurityViolationException("服务响应过大，已停止读取")
                        }
                        output.append(chunk, 0, count)
                    }
                    output.toString()
                }
            }
        } catch (e: UtilityTransportException) {
            throw e
        } catch (_: Exception) {
            throw UtilityTransportException.NetworkOrHttpException("连接智能水电失败，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }
}
