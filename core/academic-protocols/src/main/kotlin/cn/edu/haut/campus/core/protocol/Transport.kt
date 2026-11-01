package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicError
import java.net.URI
import java.time.Instant
import java.util.Locale

enum class HttpMethod { GET, POST }

enum class RequestPurpose { READ, AUTH, WRITE }

data class GatewayRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val purpose: RequestPurpose = RequestPurpose.READ,
    val contentType: String? = null
) {
    init {
        require(url.startsWith("https://")) { "教务请求必须使用 HTTPS" }
        require(purpose != RequestPurpose.WRITE || method == HttpMethod.POST) { "写操作必须使用 POST" }
    }
}

data class GatewayResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val finalUrl: String = ""
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val isSuccess: Boolean get() = status in 200..299
    val isRedirect: Boolean get() = status in 300..399
    val isUnauthorized: Boolean get() = status in setOf(401, 403)
}

/** 传输层只抛结构化错误，绝不在消息或堆栈中携带 Cookie、密码或原始响应正文。 */
class TransportFailure(val error: AcademicError, message: String) : IllegalStateException(message)

interface GatewayTransport {
    suspend fun execute(request: GatewayRequest): GatewayResponse
}

/**
 * 每一次跳转都必须通过的来源策略：仅 HTTPS、仅允许主机、禁止用户信息、禁止片段、
 * 禁止反斜杠与控制字符。登录凭据 POST 只能是显式声明的认证入口。
 */
class GatewayUrlPolicy(
    allowedHosts: Set<String>,
    private val authPaths: Set<String> = emptySet(),
    private val authHost: String? = null
) {
    private val hosts: Set<String> = allowedHosts.map { it.lowercase(Locale.ROOT) }.toSet()

    init {
        require(hosts.isNotEmpty()) { "URL 策略必须声明至少一个来源主机" }
    }

    fun validate(raw: String, purpose: RequestPurpose = RequestPurpose.READ): URI {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw refuse()
        }
        if (uri.scheme != "https") throw refuse()
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw refuse()
        if (host !in hosts) throw refuse()
        if (uri.rawUserInfo != null || uri.rawFragment != null) throw refuse()
        if (raw.any { it.isISOControl() } || '\\' in raw) throw refuse()
        if (uri.port != -1 && uri.port != 443 && uri.port != 8443) throw refuse()
        if (purpose == RequestPurpose.AUTH) {
            val expectedHost = authHost?.lowercase(Locale.ROOT)
            if (expectedHost != null && host != expectedHost) throw refuse()
            if (authPaths.isNotEmpty()) {
                val path = uri.path.orEmpty()
                if (authPaths.none { path == it || path.startsWith("$it;") }) throw refuse()
            }
        }
        return uri
    }

    fun resolve(currentUrl: String, location: String, purpose: RequestPurpose = RequestPurpose.READ): String {
        val base = validate(currentUrl, purpose)
        val target = try {
            val relative = URI(location.trim())
            if (relative.rawFragment != null) throw refuse()
            if (!relative.isAbsolute && relative.rawAuthority == null &&
                relative.rawPath.isEmpty() && relative.rawQuery != null
            ) {
                currentUrl.substringBefore('?') + "?" + relative.rawQuery
            } else {
                base.resolve(relative).toString()
            }
        } catch (_: TransportFailure) {
            throw refuse()
        } catch (_: Exception) {
            throw refuse()
        }
        return validate(target, purpose).toString()
    }

    fun allows(url: String): Boolean = runCatching { validate(url) }.isSuccess

    private fun refuse(): Nothing =
        throw TransportFailure(AcademicError.UNTRUSTED_REDIRECT, "学校认证跳转到了未授权地址，已停止请求")
}

/**
 * 只保存 host-only Cookie 的会话容器：不实现域级通配，每条 Cookie 绑定创建它的主机与路径前缀，
 * 避免跨校域名或子域泄漏；切换账号时由调用方 clear()。
 */
class ProtocolCookieJar(private val allowedHosts: Set<String>) {
    data class Cookie(
        val name: String,
        val value: String,
        val host: String,
        val path: String,
        val secure: Boolean,
        val expiresAt: Instant?
    )

    private val cookies = linkedMapOf<String, Cookie>()
    private val normalizedHosts = allowedHosts.map { it.lowercase(Locale.ROOT) }.toSet()

    fun store(requestUrl: String, setCookieHeaders: List<String>) {
        val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return
        val host = uri.host?.lowercase(Locale.ROOT) ?: return
        if (host !in normalizedHosts) return
        setCookieHeaders.forEach { header ->
            val cookie = parse(header, host, uri.path.orEmpty()) ?: return@forEach
            cookies[cookie.host + "|" + cookie.path + "|" + cookie.name] = cookie
        }
    }

    fun headerFor(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in normalizedHosts) return null
        val path = uri.path.orEmpty()
        val now = Instant.now()
        val matching = cookies.values.filter { cookie ->
            cookie.host == host &&
                (path == cookie.path || path.startsWith(cookie.path.trimEnd('/') + "/")) &&
                (cookie.expiresAt == null || cookie.expiresAt.isAfter(now))
        }
        if (matching.isEmpty()) return null
        return matching.joinToString("; ") { it.name + "=" + it.value }
    }

    fun asHeaderMap(url: String): Map<String, String> =
        headerFor(url)?.let { mapOf("Cookie" to it) } ?: emptyMap()

    fun clear() = cookies.clear()

    fun size(): Int = cookies.size

    private fun parse(header: String, host: String, requestPath: String): Cookie? {
        val parts = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val nameValue = parts.first()
        val separator = nameValue.indexOf('=')
        if (separator <= 0) return null
        val name = nameValue.substring(0, separator).trim()
        val value = nameValue.substring(separator + 1).trim()
        if (name.isEmpty()) return null
        var path = requestPath.substringBeforeLast('/', "") + "/"
        var secure = false
        var expires: Instant? = null
        for (attribute in parts.drop(1)) {
            val key = attribute.substringBefore('=')
            val rawValue = if (attribute.contains('=')) attribute.substringAfter('=') else ""
            when (key.lowercase(Locale.ROOT)) {
                "path" -> if (rawValue.startsWith("/")) path = rawValue
                "secure" -> secure = true
                "max-age" -> rawValue.toLongOrNull()?.let { expires = Instant.now().plusSeconds(it) }
                "domain" -> return null
            }
        }
        return Cookie(name, value, host, path, secure, expires)
    }
}
