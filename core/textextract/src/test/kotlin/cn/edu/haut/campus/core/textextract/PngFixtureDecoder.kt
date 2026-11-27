package cn.edu.haut.campus.core.textextract

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** 测试专用 PNG 解码器：支持本仓库合成夹具使用的 8-bit RGB/RGBA 非交错 PNG。 */
internal object PngFixtureDecoder {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun decode(bytes: ByteArray): ArgbImage {
        require(bytes.size > 8 && signature.indices.all { bytes[it] == signature[it] }) { "不是 PNG 文件" }
        var offset = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val compressed = ByteArrayOutputStream()
        while (offset + 12 <= bytes.size) {
            val length = readInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            require(dataEnd + 4 <= bytes.size) { "PNG chunk 越界" }
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, dataStart)
                    height = readInt(bytes, dataStart + 4)
                    bitDepth = bytes[dataStart + 8].toInt() and 0xff
                    colorType = bytes[dataStart + 9].toInt() and 0xff
                    interlace = bytes[dataStart + 12].toInt() and 0xff
                }
                "IDAT" -> compressed.write(bytes, dataStart, length)
                "IEND" -> break
            }
            offset = dataEnd + 4
        }
        require(width > 0 && height > 0) { "PNG 缺少有效 IHDR" }
        require(bitDepth == 8) { "测试 PNG 解码器只支持 8-bit" }
        require(colorType == 2 || colorType == 6) { "测试 PNG 解码器只支持 RGB/RGBA" }
        require(interlace == 0) { "测试 PNG 解码器不支持交错 PNG" }

        val channels = if (colorType == 6) 4 else 3
        val stride = width * channels
        val packed = ByteArray((stride + 1) * height)
        val inflater = Inflater()
        inflater.setInput(compressed.toByteArray())
        var written = 0
        while (written < packed.size && !inflater.finished()) {
            val count = inflater.inflate(packed, written, packed.size - written)
            if (count <= 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            written += count
        }
        inflater.end()
        require(written == packed.size) { "PNG 解压长度不足" }

        val raw = ByteArray(height * stride)
        for (y in 0 until height) {
            val rowStart = y * (stride + 1)
            val filter = packed[rowStart].toInt() and 0xff
            val outStart = y * stride
            for (x in 0 until stride) {
                val value = packed[rowStart + 1 + x].toInt() and 0xff
                val left = if (x >= channels) raw[outStart + x - channels].toInt() and 0xff else 0
                val up = if (y > 0) raw[outStart - stride + x].toInt() and 0xff else 0
                val upLeft = if (y > 0 && x >= channels) raw[outStart - stride + x - channels].toInt() and 0xff else 0
                val reconstructed = when (filter) {
                    0 -> value
                    1 -> value + left
                    2 -> value + up
                    3 -> value + ((left + up) / 2)
                    4 -> value + paeth(left, up, upLeft)
                    else -> throw IllegalArgumentException("未知 PNG filter: " + filter)
                }
                raw[outStart + x] = reconstructed.toByte()
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = y * stride + x * channels
                val r = raw[base].toInt() and 0xff
                val g = raw[base + 1].toInt() and 0xff
                val b = raw[base + 2].toInt() and 0xff
                val a = if (channels == 4) raw[base + 3].toInt() and 0xff else 0xff
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ArgbImage(width, height, pixels)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }
}
