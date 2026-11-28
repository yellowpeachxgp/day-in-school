package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.Digests
import java.time.LocalDate

data class DecodedDataPack(
    val pack: DisDatapack,
    val canonicalText: String,
    val canonicalBytes: ByteArray,
    val artifactSha256: String
) {
    override fun equals(other: Any?): Boolean =
        other is DecodedDataPack && pack == other.pack && canonicalText == other.canonicalText &&
            canonicalBytes.contentEquals(other.canonicalBytes) && artifactSha256 == other.artifactSha256

    override fun hashCode(): Int = 31 * pack.hashCode() + canonicalBytes.contentHashCode()
}

/**
 * W5-01 verify 的完整可复查结果。trustRoot 只含发布者公钥，不含私钥；
 * 下游必须能用它重新执行同一 Ed25519/trust-root 链，而不是只相信类型或摘要字段。
 */
data class VerifiedDataPack(
    val decoded: DecodedDataPack,
    val signatureVerification: DataPackSignatureVerification,
    val trustRoot: DataPackTrustRoot
) {
    val pack: DisDatapack get() = decoded.pack
}

/** Strict dis-datapack/v1 loader: exact bytes → RFC-8785 parse equality → schema shape → optional detached signature. */
object DisDatapackLoader {
    fun decode(artifactBytes: ByteArray): DecodedDataPack {
        val document = Rfc8785Json.parseExactCanonical(artifactBytes)
        val pack = DisDatapackValidator.validate(document.value)
        return DecodedDataPack(
            pack = pack,
            canonicalText = document.canonicalText,
            canonicalBytes = document.canonicalBytes.copyOf(),
            artifactSha256 = Digests.sha256Hex(document.canonicalBytes)
        )
    }

    fun verify(
        artifactBytes: ByteArray,
        detachedSignature: DetachedDataPackSignature,
        trustRoot: DataPackTrustRoot
    ): VerifiedDataPack {
        val decoded = decode(artifactBytes)
        val verification = DisDatapackSignature.verify(decoded.canonicalBytes, detachedSignature, trustRoot)
        return VerifiedDataPack(decoded, verification, trustRoot)
    }

    /** reviewBy 是最后可视为已复核的日期；只有严格晚于该日期才进入 REVIEW_DUE。 */
    fun freshness(pack: DisDatapack, today: LocalDate): DataPackFreshness =
        if (today.isAfter(LocalDate.parse(pack.reviewBy))) DataPackFreshness.REVIEW_DUE else DataPackFreshness.CURRENT
}
