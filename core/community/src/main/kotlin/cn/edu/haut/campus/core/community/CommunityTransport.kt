package cn.edu.haut.campus.core.community

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class CommunityHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

data class CommunityHttpResponse(
    val statusCode: Int,
    val body: String,
)

interface CommunityTransport {
    suspend fun execute(request: CommunityHttpRequest): CommunityHttpResponse
}

/** 默认 HTTPS JSON 传输。OkHttp 用于覆盖 Android HttpURLConnection 不支持的 PATCH 语义。 */
class OkHttpCommunityTransport(
    connectTimeoutMillis: Long = 10_000,
    readTimeoutMillis: Long = 20_000,
    private val maxResponseBytes: Int = 1024 * 1024,
) : CommunityTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun execute(request: CommunityHttpRequest): CommunityHttpResponse = withContext(Dispatchers.IO) {
        val requestBody = when {
            request.body != null -> request.body.toRequestBody(JSON_MEDIA_TYPE)
            request.method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }
        val builder = Request.Builder()
            .url(request.url)
            .method(request.method, requestBody)
            .header("Accept", "application/json")
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            val bodyText = response.body?.let { body ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > maxResponseBytes) {
                            throw IOException("community response exceeds " + maxResponseBytes + " bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            CommunityHttpResponse(statusCode = response.code, body = bodyText)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
