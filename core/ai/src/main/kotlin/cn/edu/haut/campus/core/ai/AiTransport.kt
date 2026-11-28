package cn.edu.haut.campus.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

data class AiTransportResponse(
    val statusCode: Int,
    val body: String
) {
    init {
        require(statusCode in 100..599) { "HTTP 状态码非法" }
        require(body.length <= MAX_TRANSPORT_BODY_CHARS) { "AI 响应体超限" }
    }

    companion object {
        const val MAX_TRANSPORT_BODY_CHARS = 512 * 1024
    }
}

enum class AiTransportFailureKind {
    NETWORK,
    TIMEOUT,
    TLS,
    RESPONSE_TOO_LARGE,
    INVALID_URL
}

/** 传输异常不携带请求体、Authorization 或远端响应原文；cause 只用于本地诊断调用链，不作为用户文案。 */
class AiTransportException(
    val kind: AiTransportFailureKind,
    cause: Throwable? = null
) : IOException(null, cause)

interface AiTransport {
    suspend fun postJson(url: String, bearerKey: CharArray, body: String): AiTransportResponse
}

/** OpenAI 兼容 HTTPS POST 传输。禁止重定向，避免 Key 被转发到非预期主机。 */
class HttpUrlConnectionAiTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) : AiTransport {
    init {
        require(connectTimeoutMs in 1000..60000 && readTimeoutMs in 1000..120000) { "AI 网络超时配置非法" }
    }

    override suspend fun postJson(url: String, bearerKey: CharArray, body: String): AiTransportResponse = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrElse { throw AiTransportException(AiTransportFailureKind.INVALID_URL, it) }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw AiTransportException(AiTransportFailureKind.INVALID_URL)
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(uri.toASCIIString()).openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer " + String(bearerKey))
                setRequestProperty("User-Agent", "day-in-school-byok/1")
            }
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            connection.outputStream.use { output -> output.write(bodyBytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readLimited(it, AiTransportResponse.MAX_TRANSPORT_BODY_CHARS + 1) }.orEmpty()
            if (text.length > AiTransportResponse.MAX_TRANSPORT_BODY_CHARS) {
                throw AiTransportException(AiTransportFailureKind.RESPONSE_TOO_LARGE)
            }
            AiTransportResponse(status, text)
        } catch (timeout: SocketTimeoutException) {
            throw AiTransportException(AiTransportFailureKind.TIMEOUT, timeout)
        } catch (ssl: SSLException) {
            throw AiTransportException(AiTransportFailureKind.TLS, ssl)
        } catch (invalid: AiTransportException) {
            throw invalid
        } catch (io: IOException) {
            throw AiTransportException(AiTransportFailureKind.NETWORK, io)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxChars: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val maxBytes = maxChars * 4 + 1
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes.write(buffer, 0, count)
            if (bytes.size() > maxBytes) {
                throw AiTransportException(AiTransportFailureKind.RESPONSE_TOO_LARGE)
            }
        }
        val text = bytes.toString(Charsets.UTF_8.name())
        if (text.length > maxChars) throw AiTransportException(AiTransportFailureKind.RESPONSE_TOO_LARGE)
        return text
    }
}
