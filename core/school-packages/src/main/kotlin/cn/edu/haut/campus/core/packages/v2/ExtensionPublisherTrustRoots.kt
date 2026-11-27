package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry

/** Host-versioned publisher trust roots; never network-updated and never read from plugin packages. */
object ExtensionPublisherTrustRoots {
    const val OFFICIAL_EXAMPLES_KEY_ID = "dis-official-examples-2026"
    private const val OFFICIAL_EXAMPLES_SPKI_BASE64 =
        "MCowBQYDK2VwAyEArHKa2jgA8ALyo4xS8gB7jJs8HNS9LJy670FXblTj8XA="

    fun officialExamplesRegistry(
        additionalKeys: Map<String, ByteArray> = emptyMap()
    ): PublisherKeyRegistry = PublisherKeyRegistry(
        mapOf(OFFICIAL_EXAMPLES_KEY_ID to ExtensionPublicKeyCodec.spkiBase64ToRaw(OFFICIAL_EXAMPLES_SPKI_BASE64)) + additionalKeys
    )
}
