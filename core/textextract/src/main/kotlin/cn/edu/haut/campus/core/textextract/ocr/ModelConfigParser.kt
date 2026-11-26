package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.textextract.TextExtractErrorKind
import cn.edu.haut.campus.core.textextract.TextExtractionException

/** 只解析 inference.yml 中 PostProcess.character_dict 的受限 YAML 子集，避免新增 YAML 依赖。 */
internal object ModelConfigParser {
    fun parseCharacterList(content: String, assetPath: String): List<String> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val postProcessLine = lines.indexOfFirst { it.trim() == "PostProcess:" }
        if (postProcessLine < 0) throw parseError("缺少 PostProcess: " + assetPath)
        val postProcessIndent = leadingSpaces(lines[postProcessLine])
        val dictLine = findCharacterDictLine(lines, postProcessLine + 1, postProcessIndent)
        if (dictLine < 0) throw parseError("缺少 character_dict: " + assetPath)
        val dictIndent = leadingSpaces(lines[dictLine])
        val characters = mutableListOf<String>()
        var index = dictLine + 1
        while (index < lines.size) {
            val line = lines[index]
            val indent = leadingSpaces(line)
            val contentAfterIndent = line.substring(indent)
            val trimmed = contentAfterIndent.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++
                continue
            }
            if (!contentAfterIndent.startsWith("-")) {
                if (indent <= dictIndent) break
                index++
                continue
            }
            characters.add(parseYamlListScalar(contentAfterIndent.substring(1)))
            index++
        }
        if (characters.isEmpty()) throw parseError("character_dict 为空: " + assetPath)
        return characters.toMutableList().apply {
            if (lastOrNull() != " ") add(" ")
        }
    }

    private fun findCharacterDictLine(lines: List<String>, startLine: Int, postProcessIndent: Int): Int {
        var index = startLine
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++
                continue
            }
            val indent = leadingSpaces(line)
            if (indent <= postProcessIndent) break
            if (trimmed == "character_dict:") return index
            index++
        }
        return -1
    }

    private fun parseYamlListScalar(rawValue: String): String {
        val value = rawValue.dropWhile { it == ' ' }
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return decodeDoubleQuoted(value.substring(1, value.length - 1))
        }
        return value
    }

    private fun decodeDoubleQuoted(value: String): String {
        val output = StringBuilder()
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    '"' -> output.append('"')
                    '\\' -> output.append('\\')
                    else -> output.append(value[index + 1])
                }
                index += 2
            } else {
                output.append(current)
                index++
            }
        }
        return output.toString()
    }

    private fun leadingSpaces(line: String): Int {
        val index = line.indexOfFirst { it != ' ' }
        return if (index < 0) line.length else index
    }

    private fun parseError(message: String): TextExtractionException {
        return TextExtractionException(TextExtractErrorKind.CONFIG_PARSE_FAILED, message)
    }
}
