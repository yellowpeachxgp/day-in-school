package cn.edu.haut.campus.core.inbox

/** 规则抽取字段的粗粒度置信度；低置信度字段必须在确认页可逐项修正。 */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * 原文 UTF-16 字符区间，endOffset 为开区间。
 * line/page 只用于回溯高亮；字符区间是跨 OCR、PDF 与剪贴板适配器的稳定锚点。
 */
data class TextSpan(
    val startOffset: Int,
    val endOffset: Int,
    val lineIndex: Int? = null,
    val pageIndex: Int? = null
) {
    init {
        require(startOffset >= 0) { "TextSpan startOffset 不能为负" }
        require(endOffset > startOffset) { "TextSpan endOffset 必须大于 startOffset" }
        require(lineIndex == null || lineIndex >= 0) { "TextSpan lineIndex 不能为负" }
        require(pageIndex == null || pageIndex >= 0) { "TextSpan pageIndex 不能为负" }
    }
}

/** 面向解析器的一行纯文本及其字符区间；坐标矩形属于抽取层，不进入纯 JVM 解析内核。 */
data class ExtractedTextLine(
    val lineIndex: Int,
    val pageIndex: Int?,
    val startOffset: Int,
    val endOffset: Int,
    val text: String
) {
    init {
        require(lineIndex >= 0) { "抽取行号不能为负" }
        require(pageIndex == null || pageIndex >= 0) { "抽取页号不能为负" }
        require(startOffset >= 0) { "抽取行 startOffset 不能为负" }
        require(endOffset >= startOffset) { "抽取行 endOffset 不能小于 startOffset" }
        require(text.length == endOffset - startOffset) { "抽取行文本长度必须等于字符区间长度" }
    }
}

/**
 * 收件箱解析内核的最小抽取文本输入。
 *
 * W2-04 的 OCR/PDF 实现在 Android 模块中；本类型只保留纯 JVM 解析需要的 plainText、行号与
 * 字符区间，分享入口适配层在后续切片把 OCR/PDF 结果转成该结构。这样规则解析、AI 解析与
 * 指纹去重保持纯 JVM 可测，不把 android.jar 或 ML 运行时带入核心模块。
 */
data class ExtractedText(
    val plainText: String,
    val lines: List<ExtractedTextLine>
) {
    init {
        require(plainText.length <= MAX_TEXT_CHARS) { "抽取文本超过解析上限" }
        require(lines.isNotEmpty()) { "抽取文本至少要有一行（空文本也算一行）" }
        require(lines.map { it.lineIndex }.distinct().size == lines.size) { "抽取行号不能重复" }
        require(lines.zipWithNext().all { (left, right) -> left.lineIndex < right.lineIndex }) { "抽取行号必须递增" }
        lines.forEach { line ->
            require(line.endOffset <= plainText.length) { "抽取行超出 plainText 范围" }
            require(plainText.substring(line.startOffset, line.endOffset) == line.text) {
                "抽取行文本必须与 plainText 字符区间一致"
            }
        }
    }

    fun span(startOffset: Int, endOffset: Int): TextSpan {
        require(startOffset >= 0 && endOffset <= plainText.length && endOffset > startOffset) {
            "sourceSpan 超出抽取文本范围"
        }
        val line = lines.firstOrNull { startOffset >= it.startOffset && startOffset <= it.endOffset }
        return TextSpan(
            startOffset = startOffset,
            endOffset = endOffset,
            lineIndex = line?.lineIndex,
            pageIndex = line?.pageIndex
        )
    }

    companion object {
        const val MAX_TEXT_CHARS = 64 * 1024

        /** 从剪贴板/纯文本构造等距行索引；保留 CRLF 中 CR 之前的真实内容边界。 */
        fun fromPlainText(plainText: String): ExtractedText {
            require(plainText.length <= MAX_TEXT_CHARS) { "抽取文本超过解析上限" }
            val rows = ArrayList<ExtractedTextLine>()
            var lineStart = 0
            var lineIndex = 0
            var index = 0
            while (index < plainText.length) {
                val ch = plainText[index]
                if (ch == '\n' || ch == '\r') {
                    rows += ExtractedTextLine(
                        lineIndex = lineIndex,
                        pageIndex = null,
                        startOffset = lineStart,
                        endOffset = index,
                        text = plainText.substring(lineStart, index)
                    )
                    lineIndex += 1
                    if (ch == '\r' && index + 1 < plainText.length && plainText[index + 1] == '\n') {
                        index += 1
                    }
                    lineStart = index + 1
                }
                index += 1
            }
            if (lineStart < plainText.length || plainText.isEmpty()) {
                rows += ExtractedTextLine(
                    lineIndex = lineIndex,
                    pageIndex = null,
                    startOffset = lineStart,
                    endOffset = plainText.length,
                    text = plainText.substring(lineStart)
                )
            }
            return ExtractedText(plainText = plainText, lines = rows)
        }
    }
}
