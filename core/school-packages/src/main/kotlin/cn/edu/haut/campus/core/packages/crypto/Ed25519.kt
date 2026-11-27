package cn.edu.haut.campus.core.packages.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519（RFC 8032）纯 Kotlin/JVM 验签实现。
 *
 * Android 12/12L（API 31/32）没有平台 Ed25519 Signature/EdEC 密钥类；本对象只使用
 * 所有受支持 Android 版本都可用的 BigInteger 与 SHA-512，因此签名安装链不依赖
 * API 33。这里只验签、不签名，私钥永远不会进入宿主。
 *
 * 实现边界：标量与点编码都来自公开签名数据或宿主信任根，不包含秘密，因此未采用
 * 恒定时间实现；信任根解析、长度与 canonical 编码检查全部 fail closed。
 */
object Ed25519 {

    const val PUBLIC_KEY_BYTES: Int = 32

    const val SIGNATURE_BYTES: Int = 64

    private val ZERO: BigInteger = BigInteger.ZERO
    private val ONE: BigInteger = BigInteger.ONE
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = ONE.shiftLeft(252).add(
        BigInteger("27742317777372353535851937790883648493")
    )
    private val D: BigInteger = BigInteger("-121665")
        .multiply(BigInteger("121666").modInverse(P))
        .mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(
        P.subtract(ONE).divide(BigInteger.valueOf(4)),
        P
    )
    private val BASE_COMPRESSED: ByteArray = byteArrayOf(
        0x58, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
        0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
        0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
        0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66
    )

    /** Extended twisted Edwards point: x=X/Z, y=Y/Z, xy=T/Z. */
    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    )

    private val NEUTRAL = Point(ZERO, ONE, ONE, ZERO)
    private val BASE_POINT: Point by lazy {
        decodePoint(BASE_COMPRESSED, rejectSmallOrder = true)
            ?: error("Ed25519 base point encoding is invalid")
    }

    /** Verify an RFC 8032 Ed25519 signature using a raw 32-byte public key. */
    fun verify(publicKeyRaw: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKeyRaw.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        return try {
            val publicKey = decodePoint(publicKeyRaw.copyOf(), rejectSmallOrder = true) ?: return false
            val encodedR = signature.copyOfRange(0, 32)
            val r = decodePoint(encodedR, rejectSmallOrder = true) ?: return false
            val s = littleEndianUnsigned(signature.copyOfRange(32, 64))
            if (s >= L) return false

            val digestInput = ByteArray(encodedR.size + publicKeyRaw.size + payload.size)
            System.arraycopy(encodedR, 0, digestInput, 0, encodedR.size)
            System.arraycopy(publicKeyRaw, 0, digestInput, encodedR.size, publicKeyRaw.size)
            System.arraycopy(payload, 0, digestInput, encodedR.size + publicKeyRaw.size, payload.size)
            val hash = MessageDigest.getInstance("SHA-512").digest(digestInput)
            val h = littleEndianUnsigned(hash).mod(L)

            val left = scalarMultiply(BASE_POINT, s)
            val right = add(r, scalarMultiply(publicKey, h))
            sameProjectivePoint(left, right)
        } catch (_: Exception) {
            false
        }
    }

    private fun add(p: Point, q: Point): Point {
        val a = mul(p.x, q.x)
        val b = mul(p.y, q.y)
        val c = mul(D, mul(p.t, q.t))
        val d = mul(p.z, q.z)
        val e = sub(mul(addMod(p.x, p.y), addMod(q.x, q.y)), addMod(a, b))
        val f = sub(d, c)
        val g = addMod(d, c)
        // Edwards a=-1: y numerator is y1*y2 - a*x1*x2 = y1*y2 + x1*x2.
        val h = addMod(b, a)
        return Point(
            x = mul(e, f),
            y = mul(g, h),
            z = mul(f, g),
            t = mul(e, h)
        )
    }

    private fun scalarMultiply(point: Point, scalar: BigInteger): Point {
        var result = NEUTRAL
        var addend = point
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    private fun decodePoint(encoded: ByteArray, rejectSmallOrder: Boolean): Point? {
        if (encoded.size != 32) return null
        val encodedY = encoded.copyOf()
        val xIsOdd = (encodedY[31].toInt() and 0x80) != 0
        encodedY[31] = (encodedY[31].toInt() and 0x7f).toByte()
        val y = littleEndianUnsigned(encodedY)
        if (y >= P) return null

        val yy = mul(y, y)
        val u = sub(yy, ONE)
        val v = addMod(mul(D, yy), ONE)
        if (v == ZERO) return null
        val ratio = try {
            mul(u, v.modInverse(P))
        } catch (_: ArithmeticException) {
            return null
        }
        var x = ratio.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (mul(x, x) != ratio) {
            x = mul(x, SQRT_M1)
        }
        if (mul(x, x) != ratio) return null
        if (x == ZERO && xIsOdd) return null
        if ((x.testBit(0)) != xIsOdd) x = P.subtract(x).mod(P)

        val point = Point(x, y, ONE, mul(x, y))
        if (!isOnCurve(point)) return null
        if (rejectSmallOrder && isSmallOrder(point)) return null
        return point
    }

    private fun isOnCurve(point: Point): Boolean {
        val xx = mul(point.x, point.x)
        val yy = mul(point.y, point.y)
        val xy2 = mul(mul(point.x, point.y), mul(point.x, point.y))
        val left = sub(yy, xx)
        val right = addMod(ONE, mul(D, xy2))
        return left == right
    }

    private fun isSmallOrder(point: Point): Boolean =
        sameProjectivePoint(scalarMultiply(point, BigInteger.valueOf(8)), NEUTRAL)

    private fun sameProjectivePoint(p: Point, q: Point): Boolean =
        mul(p.x, q.z) == mul(q.x, p.z) && mul(p.y, q.z) == mul(q.y, p.z)

    private fun littleEndianUnsigned(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun addMod(a: BigInteger, b: BigInteger): BigInteger = a.add(b).mod(P)

    private fun sub(a: BigInteger, b: BigInteger): BigInteger = a.subtract(b).mod(P)

    private fun mul(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(P)
}
