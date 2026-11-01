package cn.edu.haut.campus.core.protocol.qz

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * UNVERIFIED（未验证）：新版强智登录页在部分部署上会下发 RSA 公钥，
 * 由前端把 "学号%%密码" 加密后作为 encoded 参数提交。
 * 这里按该公开形态实现，未在任何真实学校站点验证，也没有校对该字段在全部部署上的名称。
 *
 * 安全约束：明文只在一次性字节数组里存在，加密后立即清零；异常信息不包含学号或密码。
 */
internal class QzLoginPayload(
    val submitAction: String?,
    val usernameField: String,
    val passwordField: String,
    val encodedField: String,
    val publicKey: QzRsaPublicKey?,
    val staticFields: Map<String, String>
) {

    /** 构造登录表单体；超出 RSA 明文上限时返回 null，由调用方映射为认证失败而不是崩溃。 */
    fun buildBody(studentId: String, password: String): String? {
        val fields = LinkedHashMap<String, String>()
        fields.putAll(staticFields)
        fields[usernameField] = studentId
        fields[passwordField] = password
        val key = publicKey
        if (key != null) {
            val plain = (studentId + QZ_SEPARATOR + password).toByteArray(Charsets.UTF_8)
            try {
                if (plain.size > key.maxPlainBytes) return null
                val encrypted = key.encrypt(plain)
                fields[encodedField] = Base64.getEncoder().encodeToString(encrypted)
            } catch (_: Exception) {
                return null
            } finally {
                plain.fill(0)
            }
        }
        return fields.entries.joinToString("&") { entry ->
            QzTextCodec.percentEncode(entry.key) + "=" + QzTextCodec.percentEncode(entry.value)
        }
    }

    companion object {
        /** 强智前端使用的学号/密码分隔符。 */
        const val QZ_SEPARATOR: String = "%%"
    }
}

internal object QzLoginPage {

    private val USERNAME_ALIASES = listOf("j_username", "username", "userName", "account", "xh")
    private val PASSWORD_ALIASES = listOf("j_password", "password", "pwd", "mm")
    private val KEY_NAMES = setOf("pubkey", "publickey", "rsakey", "pwdkey")

    /** 返回 null 表示这不是可识别的登录页（可能是登录过期页或改版页面）。 */
    fun parse(html: String): QzLoginPayload? {
        val inputs = QzHtml.inputs(html)
        val passwordInput = inputs.firstOrNull { it["type"]?.equals("password", ignoreCase = true) == true }
            ?: return null
        val passwordField = passwordInput["name"]?.trim().orEmpty()
        if (passwordField.isEmpty()) return null

        val usernameInput = inputs.firstOrNull { input ->
            val type = input["type"]?.lowercase()
            val name = input["name"]?.trim()
            type != "hidden" && type != "password" && name != null && name in USERNAME_ALIASES
        } ?: inputs.firstOrNull { input ->
            val type = input["type"]?.lowercase()
            val name = input["name"]?.trim()
            type != "hidden" && type != "password" && type != "submit" && !name.isNullOrEmpty()
        }
        val usernameField = usernameInput?.get("name")?.trim().orEmpty().ifEmpty { "j_username" }

        val keyInput = inputs.firstOrNull { input ->
            val name = input["name"]?.lowercase()
            name != null && name in KEY_NAMES
        }
        val publicKey = keyInput?.let { input ->
            val modulus = input["data-modulus"] ?: input["modulus"]
            val exponent = input["data-exponent"] ?: input["exponent"]
            if (modulus != null && exponent != null) {
                QzRsaPublicKey.of(modulus, exponent)
            } else {
                val raw = input["value"].orEmpty()
                val separator = raw.indexOf(':')
                if (separator > 0) QzRsaPublicKey.of(raw.substring(0, separator), raw.substring(separator + 1)) else null
            }
        }
        val encodedField = inputs.firstOrNull { it["name"] == "encoded" }?.get("name") ?: "encoded"
        val staticFields = LinkedHashMap<String, String>()
        inputs.forEach { input ->
            if (input["type"]?.equals("hidden", ignoreCase = true) != true) return@forEach
            val name = input["name"]?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            if (name == usernameField || name == passwordField || name == encodedField) return@forEach
            if (name.lowercase() in KEY_NAMES) return@forEach
            staticFields.putIfAbsent(name, input["value"].orEmpty())
        }
        return QzLoginPayload(
            submitAction = QzHtml.formAction(html),
            usernameField = usernameField,
            passwordField = passwordField,
            encodedField = encodedField,
            publicKey = publicKey,
            staticFields = staticFields
        )
    }
}

/** 最小 RSA 公钥封装：只做 PKCS#1 v1.5 加密，不持有任何私钥或会话状态。 */
internal class QzRsaPublicKey private constructor(
    private val key: PublicKey,
    private val modulusBytes: Int
) {

    val maxPlainBytes: Int get() = modulusBytes - 11

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plain)
    }

    companion object {
        fun of(modulusHex: String?, exponentHex: String?): QzRsaPublicKey? {
            val modulus = parseHex(modulusHex) ?: return null
            val exponent = parseHex(exponentHex) ?: return null
            if (modulus.size < 64 || exponent.isEmpty()) return null
            val publicKey = runCatching {
                KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(BigInteger(1, modulus), BigInteger(1, exponent))
                )
            }.getOrNull() ?: return null
            return QzRsaPublicKey(publicKey, modulus.size)
        }

        private fun parseHex(raw: String?): ByteArray? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().removePrefix("0x").replace(" ", "").replace(":", "")
            if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
            if (cleaned.any { it !in "0123456789abcdefABCDEF" }) return null
            val bytes = ByteArray(cleaned.length / 2)
            for (index in bytes.indices) {
                val high = Character.digit(cleaned[index * 2], 16)
                val low = Character.digit(cleaned[index * 2 + 1], 16)
                if (high < 0 || low < 0) return null
                bytes[index] = ((high shl 4) or low).toByte()
            }
            return bytes
        }
    }
}
