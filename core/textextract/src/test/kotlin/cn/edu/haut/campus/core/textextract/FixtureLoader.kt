package cn.edu.haut.campus.core.textextract


internal object FixtureLoader {
    fun bytes(name: String): ByteArray {
        return requireNotNull(javaClass.getResourceAsStream("/fixtures/" + name)) { "缺少测试夹具: " + name }
            .use { it.readBytes() }
    }

    fun pngAsArgbImage(name: String): ArgbImage {
        val bytes = bytes(name)
        return PngFixtureDecoder.decode(bytes)
    }

    fun detModelBytes(): ByteArray = java.io.File("src/main/assets/textextract/models/ppocrv6_tiny_det.onnx").readBytes()

    fun recModelBytes(): ByteArray = java.io.File("src/main/assets/textextract/models/ppocrv6_tiny_rec.onnx").readBytes()

    fun recConfigYaml(): String = java.io.File("src/main/assets/textextract/models/ppocrv6_tiny_rec.yml").readText()
}
