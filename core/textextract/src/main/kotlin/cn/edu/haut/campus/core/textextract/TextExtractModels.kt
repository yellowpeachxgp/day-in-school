package cn.edu.haut.campus.core.textextract

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import java.security.MessageDigest
import java.time.Instant

/** 文本抽取来源。PDF 首版只抽取已嵌入文本，不把 PDF 页面隐式渲染成图片。 */
enum class ExtractionSourceKind {
    IMAGE_OCR,
    PDF_EMBEDDED_TEXT
}

/**
 * 用户确认工作流状态。OCR/PDF 输出初始一律是 UNCONFIRMED；
 * 后续收件箱或知识空间只有在用户预览、修正并确认后才能写成事实数据。
 */
enum class ExtractionConfirmationState {
    UNCONFIRMED,
    USER_CONFIRMED
}

enum class TextCoordinateSpace {
    /** x/y 使用原图像素，原点在左上角；OCR 多边形可旋转。 */
    IMAGE_PIXEL_TOP_LEFT,

    /** x/y 使用 PDF point；原点位于显示页左上角，页旋转已折算，四边形可随文本方向旋转。 */
    PDF_POINT_TOP_LEFT
}

data class TextPoint(
    val x: Float,
    val y: Float
)

/** 四点多边形；OCR 保留旋转四边形，PDF 也可按文本方向输出旋转四边形。 */
data class TextBoundingBox(
    val points: List<TextPoint>
) {
    init {
        require(points.size == 4) { "文本坐标必须恰好包含四个点" }
    }

    val minX: Float get() = points.minOf { it.x }
    val minY: Float get() = points.minOf { it.y }
    val maxX: Float get() = points.maxOf { it.x }
    val maxY: Float get() = points.maxOf { it.y }
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY

    companion object {
        fun axisAligned(left: Float, top: Float, right: Float, bottom: Float): TextBoundingBox {
            return TextBoundingBox(
                listOf(
                    TextPoint(left, top),
                    TextPoint(right, top),
                    TextPoint(right, bottom),
                    TextPoint(left, bottom)
                )
            )
        }
    }
}

data class ExtractedTextLine(
    val lineIndex: Int,
    val pageIndex: Int,
    val text: String,
    /** OCR 为 0..1 的逐行置信度；PDF 嵌入文本不使用伪造置信度，因此为 null。 */
    val confidence: Float?,
    val box: TextBoundingBox,
    val coordinateSpace: TextCoordinateSpace
)

data class ExtractionEngineInfo(
    val engineName: String,
    val engineVersion: String,
    val modelName: String? = null,
    val modelVersion: String? = null,
    /** 模型组件名到 SHA-256 的固定映射，例如 det/rec，避免把多个哈希拼成不可验证字符串。 */
    val modelArtifactsSha256: Map<String, String> = emptyMap()
)

data class ExtractionMetadata(
    val engine: ExtractionEngineInfo,
    val extractedAt: Instant,
    val durationMs: Long,
    val sourceWidth: Float? = null,
    val sourceHeight: Float? = null,
    val pageCount: Int? = null,
    val warnings: List<String> = emptyList(),
    val requiresUserConfirmation: Boolean = true
)

/** 上层输入的账号上下文；不在本模块另造 Profile/Scope 类型。 */
data class TextExtractionContext(
    val scope: SchoolAccountScope,
    val sourceRecordId: String? = null,
    val extractedAt: Instant? = null
) {
    companion object {
        /** 测试与预览使用的不透明合成身份，不写入真实学号。 */
        fun synthetic(): TextExtractionContext {
            return TextExtractionContext(
                scope = SchoolAccountScope(
                    schoolId = SchoolId("synthetic-school"),
                    accountId = AccountId("synthetic-account"),
                    profileId = ProfileId("synthetic-profile")
                ),
                sourceRecordId = "synthetic-input"
            )
        }
    }
}

data class ExtractedText(
    val sourceKind: ExtractionSourceKind,
    /** 对规范化输入字节或像素计算的 SHA-256，用于原件回溯与去重；不等于业务确认。 */
    val sourceHashSha256: String,
    val lines: List<ExtractedTextLine>,
    val plainText: String,
    val provenance: DataProvenance,
    val metadata: ExtractionMetadata,
    val confirmationState: ExtractionConfirmationState = ExtractionConfirmationState.UNCONFIRMED
)

/** 与 Android Bitmap 解耦的 ARGB 像素图，便于 JVM 单测直接驱动同一 OCR pipeline。 */
class ArgbImage(
    val width: Int,
    val height: Int,
    /** 每像素 0xAARRGGBB，行优先。 */
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "图片尺寸必须为正数" }
        require(pixels.size == width * height) { "像素数组大小与图片尺寸不一致" }
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun canonicalBytes(): ByteArray {
        val out = ByteArray(width * height * 4 + 8)
        writeInt(out, 0, width)
        writeInt(out, 4, height)
        var offset = 8
        for (pixel in pixels) {
            out[offset++] = (pixel ushr 24).toByte()
            out[offset++] = (pixel ushr 16).toByte()
            out[offset++] = (pixel ushr 8).toByte()
            out[offset++] = pixel.toByte()
        }
        return out
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

data class ImageExtractionRequest(
    val image: ArgbImage,
    val context: TextExtractionContext,
    /** 若调用方保存了原始图片字节，可传入原始字节哈希；缺省时使用规范化像素哈希。 */
    val sourceHashSha256: String? = null
) {
    init {
        if (sourceHashSha256 != null && !sourceHashSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw TextExtractionException(TextExtractErrorKind.INVALID_INPUT, "图片 sourceHashSha256 必须是 SHA-256 hex")
        }
    }
}

data class PdfExtractionRequest(
    val pdfBytes: ByteArray,
    val context: TextExtractionContext
) {
    init {
        if (pdfBytes.isEmpty()) {
            throw TextExtractionException(TextExtractErrorKind.INVALID_INPUT, "PDF 输入不能为空")
        }
    }
}

object TextExtractHashes {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hex(digest)
    }

    /** 与 canonicalBytes 相同的字节序列，但分块流式计算，避免大图再复制一份 4 B/px 哈希缓冲。 */
    fun sha256Hex(image: ArgbImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var offset = 0

        fun flush() {
            if (offset > 0) {
                digest.update(buffer, 0, offset)
                offset = 0
            }
        }
        fun put(value: Byte) {
            if (offset == buffer.size) flush()
            buffer[offset++] = value
        }
        fun putInt(value: Int) {
            put((value ushr 24).toByte())
            put((value ushr 16).toByte())
            put((value ushr 8).toByte())
            put(value.toByte())
        }

        putInt(image.width)
        putInt(image.height)
        for (pixel in image.pixels) {
            put((pixel ushr 24).toByte())
            put((pixel ushr 16).toByte())
            put((pixel ushr 8).toByte())
            put(pixel.toByte())
        }
        flush()
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String {
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

internal fun extractionProvenance(
    context: TextExtractionContext,
    sourceSystem: String,
    sourceHashSha256: String,
    extractedAt: Instant,
    completeness: SnapshotCompleteness
): DataProvenance {
    return DataProvenance(
        providerId = "local-textextract",
        schoolId = context.scope.schoolId,
        accountId = context.scope.accountId,
        sourceSystem = sourceSystem,
        sourceRecordId = context.sourceRecordId,
        fetchedAt = extractedAt,
        completeness = completeness,
        // 共享 VerificationLevel 当前没有 UNVERIFIED/DERIVED 值；本模块绝不标 DEVICE_TESTED。
        // MOCK_TESTED 只表示合成夹具链路证据，逐行内容的业务可信度由 confirmationState=UNCONFIRMED 保护。
        trust = VerificationLevel.MOCK_TESTED,
        schemaVersion = 1,
        contentRevision = sourceHashSha256
    )
}
