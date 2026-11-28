package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/** Byte-identical Android copy of the Cloud cross-end regression facts; the digest pins drift. */
internal object CrossEndDatapackVectors {
    const val SHA256 = "1884e0f8a9bd68abcc37dfa94a56210e44a3be4e0d3bbdaf0ce795caef2cb117"

    fun bytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/cross-end-vectors.json")).use { it.readBytes() }

    fun document(): JsonValue {
        val bytes = bytes()
        check(Digests.sha256Hex(bytes) == SHA256) { "cross-end datapack vector fixture digest drifted" }
        return Json.parse(String(bytes, Charsets.UTF_8))
    }
}
