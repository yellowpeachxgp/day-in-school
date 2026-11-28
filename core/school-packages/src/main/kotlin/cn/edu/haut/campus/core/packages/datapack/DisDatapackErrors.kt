package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException

internal const val RUNTIME_DATAPACK_INVALID = "runtime_datapack_invalid"
internal const val RUNTIME_DATAPACK_EXACT_BYTES = "runtime_datapack_exact_canonical_bytes_required"
internal const val RUNTIME_DATAPACK_INVALID_UTF8 = "runtime_datapack_invalid_utf8"
internal const val RUNTIME_DATAPACK_INVALID_UNICODE = "runtime_datapack_invalid_unicode"
internal const val RUNTIME_DATAPACK_NON_FINITE_NUMBER = "runtime_datapack_non_finite_number"
internal const val RUNTIME_DATAPACK_SCHEMA_INVALID = "runtime_datapack_schema_invalid"
internal const val RUNTIME_DATAPACK_SIGNATURE_INVALID = "runtime_datapack_signature_invalid"
internal const val RUNTIME_DATAPACK_UNTRUSTED_SIGNER = "runtime_datapack_untrusted_signer"

internal fun datapackInvalid(
    message: String,
    runtimeCode: String = RUNTIME_DATAPACK_INVALID
): PackageException = PackageException(PackageError.MANIFEST_INVALID, message, runtimeCode)

internal fun datapackSignatureInvalid(
    message: String,
    runtimeCode: String = RUNTIME_DATAPACK_SIGNATURE_INVALID
): PackageException = PackageException(PackageError.SIGNATURE_INVALID, message, runtimeCode)
