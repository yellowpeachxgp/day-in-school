package cn.edu.haut.campus.core.textextract

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal object FixtureLoader {
    fun bytes(name: String): ByteArray {
        return requireNotNull(javaClass.getResourceAsStream("/fixtures/" + name)) { "缺少测试夹具: " + name }
            .use { it.readBytes() }
    }

    fun pngAsArgbImage(name: String): ArgbImage {
        val bytes = bytes(name)
        val buffered = ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("PNG 测试夹具无法解码: " + name)
        val pixels = IntArray(buffered.width * buffered.height)
        buffered.getRGB(0, 0, buffered.width, buffered.height, pixels, 0, buffered.width)
        return ArgbImage(buffered.width, buffered.height, pixels)
    }

    fun detModelBytes(): ByteArray = java.io.File("src/main/assets/textextract/models/ppocrv6_tiny_det.onnx").readBytes()

    fun recModelBytes(): ByteArray = java.io.File("src/main/assets/textextract/models/ppocrv6_tiny_rec.onnx").readBytes()

    fun recConfigYaml(): String = java.io.File("src/main/assets/textextract/models/ppocrv6_tiny_rec.yml").readText()
}
