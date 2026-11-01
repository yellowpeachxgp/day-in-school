package cn.edu.haut.campus.core.packages.crypto

/**
 * 宿主内置的发布者信任清单：发布者 ID → Ed25519 原始公钥（32 字节）。
 *
 * 清单里的 publisher 必须在这里出现，否则包不可信。信任清单由宿主版本随包发布，
 * 不从网络更新、也不从已安装包读取——否则信任根就变成了被验证对象自己。
 */
class PublisherKeyRegistry(keys: Map<String, ByteArray>) {

    private val trusted: Map<String, ByteArray>

    init {
        require(keys.isNotEmpty()) { "发布者信任清单不能为空" }
        trusted = keys.mapValues { (publisher, raw) ->
            require(publisher.isNotBlank()) { "发布者 ID 不能为空" }
            require(raw.size == Ed25519.PUBLIC_KEY_BYTES) { "发布者公钥必须是 32 字节：" + publisher }
            raw.copyOf()
        }
    }

    val publishers: Set<String> get() = trusted.keys.toSet()

    fun keyFor(publisher: String): ByteArray? = trusted[publisher]?.copyOf()
}
