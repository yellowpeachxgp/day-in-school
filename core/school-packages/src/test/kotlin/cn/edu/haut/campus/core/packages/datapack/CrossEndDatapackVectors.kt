package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/** Byte-identical Android copy of the Cloud cross-end regression facts; the digest pins drift. */
internal object CrossEndDatapackVectors {
    const val SHA256 = "fd717d957fda21f532a73be28bb5e3aaed679b86d3eab286c7d5060248fa49a1"

    fun bytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/cross-end-vectors.json")).use { it.readBytes() }

    fun document(): JsonValue {
        val bytes = bytes()
        check(Digests.sha256Hex(bytes) == SHA256) { "cross-end datapack vector fixture digest drifted" }
        return Json.parse(String(bytes, Charsets.UTF_8))
    }
}
