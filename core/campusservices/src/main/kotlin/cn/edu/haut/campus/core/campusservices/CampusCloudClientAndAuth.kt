package cn.edu.haut.campus.core.campusservices

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class XpeachCloudAccountProfile(
    val cloudUserId: String,
    val ssoIssuer: String,
    val ssoSubject: String,
    val displayName: String,
    val peachBalanceMicropeach: Long,
    val holdSupported: Boolean = false,
    val linkedAtEpochMillis: Long
) {
    val formattedPeachBalance: String
        get() = "%.2f 🍑".format(peachBalanceMicropeach.toDouble() / 10_000.0)
}

data class PkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256"
)

data class MobileAuthTransactionStart(
    val loginTxn: String,
    val authorizeUrl: String,
    val pkce: PkcePair,
    val expiresAtEpochMillis: Long
)

object CampusCloudOidcHelper {
    const val DEFAULT_CLOUD_BASE_URL = "https://campus.yellowpeachxgp.com"
    const val DEFAULT_SSO_ISSUER = "https://sso.xpeach.codes/t/acme"
    const val APP_LINK_CALLBACK_PREFIX = "https://campus.yellowpeachxgp.com/app-link/auth/callback"

    private val secureRandom = SecureRandom()

    fun generatePkcePair(): PkcePair {
        val raw = ByteArray(32)
        secureRandom.nextBytes(raw)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return PkcePair(
            codeVerifier = verifier,
            codeChallenge = computeS256Challenge(verifier),
            codeChallengeMethod = "S256"
        )
    }

    fun computeS256Challenge(codeVerifier: String): String {
        require(codeVerifier.length in 43..128) {
            "PKCE code_verifier 长度必须在 43..128 之间"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildLocalOrRemoteTransaction(
        installEphemeralId: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): MobileAuthTransactionStart {
        require(installEphemeralId.length >= 8) { "install_ephemeral_id 长度不足" }
        val pkce = generatePkcePair()
        val txnBytes = ByteArray(16)
        secureRandom.nextBytes(txnBytes)
        val loginTxn = "txn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(txnBytes)
        val cleanBase = cloudBaseUrl.trimEnd('/')
        return MobileAuthTransactionStart(
            loginTxn = loginTxn,
            authorizeUrl = "$cleanBase/api/v1/auth/oidc/start?txn=$loginTxn&code_challenge=${pkce.codeChallenge}&code_challenge_method=S256",
            pkce = pkce,
            expiresAtEpochMillis = nowEpochMillis + 5 * 60 * 1000L
        )
    }

    fun buildDebitIdempotencyKey(orderId: String): String =
        "dis:reservation:$orderId:debit:v1"

    fun buildRefundIdempotencyKey(orderId: String): String =
        "dis:reservation:$orderId:refund:v1"
}
