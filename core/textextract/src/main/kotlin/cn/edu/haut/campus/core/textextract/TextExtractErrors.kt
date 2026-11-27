package cn.edu.haut.campus.core.textextract

enum class TextExtractErrorKind {
    INVALID_INPUT,
    MODEL_NOT_FOUND,
    MODEL_INTEGRITY_MISMATCH,
    MODEL_LOAD_FAILED,
    CONFIG_PARSE_FAILED,
    OCR_INFERENCE_FAILED,
    INPUT_TOO_LARGE,
    PDF_INVALID,
    PDF_ENCRYPTED,
    PDF_ENCRYPTION_UNSUPPORTED,
    PDF_EXTRACTION_FAILED,
    ENGINE_CLOSED
}

class TextExtractionException(
    val kind: TextExtractErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
