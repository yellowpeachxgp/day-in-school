package cn.edu.haut.campus.core.textextract.ocr

/** PP-OCRv6 tiny 固定资产与可调推理参数。版本号只描述本模块封装的模型快照。 */
data class PaddleOcrConfig(
    val detImgMode: String = "BGR",
    val detLimitSideLen: Int = 64,
    val detLimitType: String = "min",
    val detMaxSideLimit: Int = 4000,
    val detThresh: Float = 0.2f,
    val detBoxThresh: Float = 0.4f,
    val detUnclipRatio: Float = 1.4f,
    val detMaxCandidates: Int = 3000,
    val detUseDilation: Boolean = false,
    val recScoreThresh: Float = 0.0f,
    val recBatchSize: Int = 1,
    val numThreads: Int = 4
) {
    init {
        require(detLimitSideLen > 0) { "检测边长必须为正数" }
        require(detMaxSideLimit >= detLimitSideLen) { "检测最大边长配置非法" }
        require(detMaxCandidates > 0) { "检测候选数必须为正数" }
        require(recBatchSize > 0) { "识别批大小必须为正数" }
        require(numThreads in 1..8) { "OCR 线程数必须在 1..8 内" }
    }

    companion object {
        const val MODEL_NAME = "PP-OCRv6_tiny"
        const val MODEL_VERSION = "paddleocr-hf-2026-06-18"
        const val DET_ASSET_PATH = "textextract/models/ppocrv6_tiny_det.onnx"
        const val DET_CONFIG_ASSET_PATH = "textextract/models/ppocrv6_tiny_det.yml"
        const val REC_ASSET_PATH = "textextract/models/ppocrv6_tiny_rec.onnx"
        const val REC_CONFIG_ASSET_PATH = "textextract/models/ppocrv6_tiny_rec.yml"
        const val DET_SHA256 = "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8"
        const val REC_SHA256 = "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6"
    }
}
