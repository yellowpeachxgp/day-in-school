package cn.edu.haut.campus.core.textextract.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cn.edu.haut.campus.core.textextract.ArgbImage
import cn.edu.haut.campus.core.textextract.DefaultTextExtractService
import cn.edu.haut.campus.core.textextract.TextExtractErrorKind
import cn.edu.haut.campus.core.textextract.TextExtractHashes
import cn.edu.haut.campus.core.textextract.TextExtractionException
import cn.edu.haut.campus.core.textextract.ocr.ModelConfigParser
import cn.edu.haut.campus.core.textextract.ocr.PaddleOcrConfig
import cn.edu.haut.campus.core.textextract.ocr.PaddleOcrOnnxEngine
import cn.edu.haut.campus.core.textextract.pdf.PdfBoxTextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

fun Bitmap.toArgbImage(): ArgbImage {
    require(width > 0 && height > 0) { "Bitmap 尺寸必须为正数" }
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return ArgbImage(width, height, pixels)
}

fun ByteArray.decodeArgbImage(): ArgbImage {
    val bitmap = BitmapFactory.decodeByteArray(this, 0, size)
        ?: throw TextExtractionException(TextExtractErrorKind.INVALID_INPUT, "图片字节无法解码")
    return bitmap.toArgbImage()
}

object AndroidTextExtractors {
    /** 从模块 assets 固定加载模型；SHA-256 不匹配时 fail-closed，不在首次使用时联网下载。 */
    fun create(context: Context, config: PaddleOcrConfig = PaddleOcrConfig()): DefaultTextExtractService {
        val appContext = context.applicationContext
        val detBytes = readAsset(appContext, PaddleOcrConfig.DET_ASSET_PATH)
        val recBytes = readAsset(appContext, PaddleOcrConfig.REC_ASSET_PATH)
        verifyHash(detBytes, PaddleOcrConfig.DET_SHA256, PaddleOcrConfig.DET_ASSET_PATH)
        verifyHash(recBytes, PaddleOcrConfig.REC_SHA256, PaddleOcrConfig.REC_ASSET_PATH)
        val recConfig = readAsset(appContext, PaddleOcrConfig.REC_CONFIG_ASSET_PATH).toString(Charsets.UTF_8)
        val characters = ModelConfigParser.parseCharacterList(recConfig, PaddleOcrConfig.REC_CONFIG_ASSET_PATH)
        val ocr = PaddleOcrOnnxEngine(detBytes, recBytes, characters, config)
        val pdf = PdfBoxTextExtractor {
            PDFBoxResourceLoader.init(appContext)
        }
        return DefaultTextExtractService(ocr, pdf)
    }

    private fun readAsset(context: Context, path: String): ByteArray {
        return try {
            context.assets.open(path).use { it.readBytes() }
        } catch (error: Throwable) {
            throw TextExtractionException(TextExtractErrorKind.MODEL_NOT_FOUND, "模型或配置资源缺失: " + path, error)
        }
    }

    private fun verifyHash(bytes: ByteArray, expected: String, path: String) {
        val actual = TextExtractHashes.sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw TextExtractionException(
                TextExtractErrorKind.MODEL_INTEGRITY_MISMATCH,
                "模型 SHA-256 不匹配: " + path
            )
        }
    }
}
