package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/** Byte-identical Android copy of the Cloud cross-end regression facts; the digest pins drift. */
internal object CrossEndDatapackVectors {
    const val SHA256 = "b82b419cd697de3914937ed08b82289c900f0e89494352bfc307da3c27b6c464"

    fun bytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/cross-end-vectors.json")).use { it.readBytes() }

    fun document(): JsonValue {
        val bytes = bytes()
        check(Digests.sha256Hex(bytes) == SHA256) { "cross-end datapack vector fixture digest drifted" }
        return Json.parse(String(bytes, Charsets.UTF_8))
    }
}
