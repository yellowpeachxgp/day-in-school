package cn.edu.haut.campus.core.packages.permission

import java.net.URI
import java.util.Locale

/** 学校包允许发起的网络方法。写能力单独受用途约束，不做隐含放大。 */
enum class PermissionMethod { GET, POST }

/**
 * 请求用途。WRITE 表示会改变学校侧状态的操作，必须显式声明并单独授权。
 * 与 core:academic-protocols 的 RequestPurpose 语义一致，但本模块不依赖协议实现，
 * 避免把安装层耦合到某个教务适配器。
 */
enum class PermissionPurpose { READ, AUTH, WRITE }

/**
 * 单条网络授权：一个来源 + 一个路径前缀 + 允许的方法/用途集合。
 *
 * 匹配是"默认拒绝"的：协议必须是 HTTPS、主机与端口必须完全一致（不做子域通配）、
 * 路径前缀必须按完整路径段比较（/app 不会匹配 /application）、带用户信息或片段的
 * 地址一律不匹配。UNVERIFIED：这些规则是本模块自定的收紧策略，未经真实学校流量验证。
 */
data class PermissionScope(
    val origin: String,
    val pathPrefix: String,
    val methods: Set<PermissionMethod>,
    val purposes: Set<PermissionPurpose>
) : Comparable<PermissionScope> {

    init {
        require(methods.isNotEmpty()) { "网络授权必须声明至少一个方法" }
        require(purposes.isNotEmpty()) { "网络授权必须声明至少一个用途" }
        require(!(PermissionPurpose.WRITE in purposes && PermissionMethod.POST !in methods)) {
            "写用途必须同时声明 POST"
        }
        require(origin.length in 8..200) { "来源长度非法" }
        val parsed = try {
            URI(origin)
        } catch (_: Exception) {
            throw IllegalArgumentException("来源不是合法 URI")
        }
        require(parsed.scheme == "https") { "来源必须使用 HTTPS" }
        require(!parsed.host.isNullOrBlank()) { "来源必须包含主机" }
        require(parsed.rawUserInfo == null) { "来源不能包含用户信息" }
        require(parsed.rawQuery == null && parsed.rawFragment == null) { "来源不能包含查询或片段" }
        require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") { "来源不能包含路径" }
        requireValidPathPrefix(pathPrefix)
    }

    private val parsedOrigin: URI = URI(origin)

    /** 规范化主机名；来源大小写不敏感。 */
    val host: String = parsedOrigin.host.lowercase(Locale.ROOT)

    /** 显式端口，未写出时为 443。 */
    val port: Int = if (parsedOrigin.port == -1) 443 else parsedOrigin.port

    /** 判断一次请求是否落在本条授权内。 */
    fun matches(method: PermissionMethod, url: String, purpose: PermissionPurpose): Boolean {
        if (method !in methods || purpose !in purposes) return false
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https") return false
        if (uri.rawUserInfo != null || uri.rawFragment != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host != this.host) return false
        val port = if (uri.port == -1) 443 else uri.port
        if (port != this.port) return false
        // URI.path 是百分号解码后的结果，因此 %2e%2e、%2f、%5c 都会在这里还原成
        // . / .. / \ 并被下面的检查拦住，不能靠编码绕过路径前缀。
        val path = uri.path.orEmpty()
        if (path.any { it == '\\' } || path.any { it.isISOControl() }) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        return pathAllowed(path)
    }

    /**
     * 本条授权是否覆盖另一条授权。用于"权限扩大需要重新授权"的判定：
     * 只有来源/端口相同、方法集合与用途集合都是超集、路径前缀更宽时才覆盖。
     */
    fun covers(other: PermissionScope): Boolean =
        host == other.host &&
            port == other.port &&
            methods.containsAll(other.methods) &&
            purposes.containsAll(other.purposes) &&
            prefixCovers(pathPrefix, other.pathPrefix)

    /** 稳定排序键，同时用作权限集合的去重键。 */
    fun signature(): String = buildString {
        append(origin.lowercase(Locale.ROOT))
        append(' ')
        append(pathPrefix)
        append(' ')
        append(methods.map { it.name }.sorted().joinToString(","))
        append(' ')
        append(purposes.map { it.name }.sorted().joinToString(","))
    }

    override fun compareTo(other: PermissionScope): Int = signature().compareTo(other.signature())

    override fun toString(): String = "PermissionScope(" + signature() + ")"

    private fun pathAllowed(path: String): Boolean {
        val prefix = pathPrefix.trimEnd('/')
        if (prefix.isEmpty()) return true
        return path == prefix || path.startsWith(prefix + "/")
    }

    companion object {
        private fun requireValidPathPrefix(value: String) {
            require(value.length in 1..200) { "路径前缀长度非法" }
            require(value.startsWith("/")) { "路径前缀必须以 / 开头" }
            if (value == "/") return
            val body = value.removeSuffix("/").split('/').drop(1)
            require(body.isNotEmpty()) { "路径前缀非法" }
            body.forEach { segment ->
                require(segment.isNotEmpty() && segment != "." && segment != "..") {
                    "路径前缀不能包含空段、. 或 .."
                }
                require(segment.length <= 64) { "路径前缀单段过长" }
                require(segment.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~" }) {
                    "路径前缀包含非法字符"
                }
            }
        }

        private fun prefixCovers(broad: String, narrow: String): Boolean {
            val wide = broad.trimEnd('/')
            val tight = narrow.trimEnd('/')
            if (wide.isEmpty()) return true
            return tight == wide || tight.startsWith(wide + "/")
        }
    }
}

/** 授权集合；空集合表示没有任何网络权限。 */
data class PermissionSet(val scopes: Set<PermissionScope> = emptySet()) {

    init {
        require(scopes.map { it.signature() }.distinct().size == scopes.size) { "权限集合包含重复条目" }
    }

    val isEmpty: Boolean get() = scopes.isEmpty()

    fun allows(method: PermissionMethod, url: String, purpose: PermissionPurpose): Boolean =
        scopes.any { it.matches(method, url, purpose) }

    /** 本集合是否覆盖另一集合（逐条寻找更宽的授权）。 */
    fun covers(other: PermissionSet): Boolean =
        other.scopes.all { candidate -> scopes.any { it.covers(candidate) } }

    /** 相对旧集合新增的授权条目；用于向用户展示"这次多要了什么"。 */
    fun addedRelativeTo(older: PermissionSet): List<PermissionScope> =
        scopes.filter { candidate -> older.scopes.none { it.covers(candidate) } }.sorted()

    fun plus(extra: PermissionScope): PermissionSet = PermissionSet(scopes + extra)

    companion object {
        val EMPTY: PermissionSet = PermissionSet()

        fun of(vararg scopes: PermissionScope): PermissionSet = PermissionSet(scopes.toSet())
    }
}

/**
 * 清单里的一条 requiredPermissions。origins × paths 会展开为多条 [PermissionScope]，
 * 每条共享同一组methods/purposes；这样"多来源"不能靠一条记录顺手放大路径范围。
 */
data class NetworkPermission(
    val origins: Set<String>,
    val paths: Set<String>,
    val methods: Set<PermissionMethod>,
    val purposes: Set<PermissionPurpose>
) {
    init {
        require(origins.isNotEmpty()) { "网络授权必须声明至少一个来源" }
        require(paths.isNotEmpty()) { "网络授权必须声明至少一个路径前缀" }
        require(methods.isNotEmpty() && purposes.isNotEmpty()) { "网络授权必须声明方法与用途" }
    }

    fun scopes(): List<PermissionScope> = origins.sorted().flatMap { origin ->
        paths.sorted().map { path ->
            PermissionScope(origin = origin, pathPrefix = path, methods = methods, purposes = purposes)
        }
    }
}

/** 权限扩大判定：新集合不被旧集合覆盖即为扩大，必须重新授权。 */
object PermissionRules {

    fun needsReauthorization(old: PermissionSet, new: PermissionSet): Boolean = !old.covers(new)

    fun addedScopes(old: PermissionSet, new: PermissionSet): List<PermissionScope> = new.addedRelativeTo(old)
}
