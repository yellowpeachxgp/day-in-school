/*
 * OCR pipeline semantics adapted from PaddleOCR deploy/ppocr-android.
 * Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; see core/textextract/licenses/Apache-2.0.txt.
 * This implementation replaces OpenCV/Mat operations with auditable pure Kotlin code.
 */

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
        val dictValue = lines[dictLine].trim().substringAfter("character_dict:").trim()
        val characters = mutableListOf<String>()
        if (dictValue.startsWith("[")) {
            characters.addAll(parseFlowCharacterList(lines, dictLine, assetPath))
        } else {
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
            if (trimmed.startsWith("character_dict:")) return index
            index++
        }
        return -1
    }

    /** 解析合法 YAML flow list 的受限子集；字符串不在行内换行，标量以逗号分隔。 */
    private fun parseFlowCharacterList(lines: List<String>, dictLine: Int, assetPath: String): List<String> {
        val characters = mutableListOf<String>()
        val token = StringBuilder()
        var inDoubleQuoted = false
        var inSingleQuoted = false
        var escaping = false
        var closed = false

        fun finishToken() {
            val raw = token.toString().trim()
            if (raw.isNotEmpty()) characters.add(parseYamlListScalar(raw))
            token.clear()
        }

        outer@ for (lineIndex in dictLine until lines.size) {
            val line = if (lineIndex == dictLine) {
                lines[lineIndex].trim().substringAfter("character_dict:").trim()
            } else {
                lines[lineIndex]
            }
            var position = 0
            if (lineIndex == dictLine) {
                if (!line.startsWith("[")) throw parseError("character_dict flow 缺少左括号: " + assetPath)
                position = 1
            }
            while (position < line.length) {
                val current = line[position]
                if (inDoubleQuoted) {
                    token.append(current)
                    if (escaping) {
                        escaping = false
                    } else if (current == '\\') {
                        escaping = true
                    } else if (current == '"') {
                        inDoubleQuoted = false
                    }
                } else if (inSingleQuoted) {
                    token.append(current)
                    if (current == '\'') {
                        if (position + 1 < line.length && line[position + 1] == '\'') {
                            token.append(line[position + 1])
                            position++
                        } else {
                            inSingleQuoted = false
                        }
                    }
                } else {
                    when (current) {
                        '"' -> {
                            inDoubleQuoted = true
                            token.append(current)
                        }
                        '\'' -> {
                            inSingleQuoted = true
                            token.append(current)
                        }
                        ',' -> finishToken()
                        ']' -> {
                            finishToken()
                            closed = true
                            break@outer
                        }
                        else -> if (!current.isWhitespace()) token.append(current)
                    }
                }
                position++
            }
        }
        if (!closed) throw parseError("character_dict flow 未闭合: " + assetPath)
        return characters
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
