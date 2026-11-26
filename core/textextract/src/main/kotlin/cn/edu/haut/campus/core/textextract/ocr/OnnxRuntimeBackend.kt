package cn.edu.haut.campus.core.textextract.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import cn.edu.haut.campus.core.textextract.TextExtractErrorKind
import cn.edu.haut.campus.core.textextract.TextExtractionException
import java.nio.FloatBuffer

internal class OnnxRuntimeBackend(
    detModelBytes: ByteArray,
    recModelBytes: ByteArray,
    numThreads: Int
) : AutoCloseable {
    private var environment: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName = "x"
    private var recInputName = "x"

    init {
        try {
            val env = OrtEnvironment.getEnvironment()
            environment = env
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(numThreads)
            }
            try {
                detSession = try {
                    env.createSession(detModelBytes, options)
                } catch (error: Throwable) {
                    throw TextExtractionException(TextExtractErrorKind.MODEL_LOAD_FAILED, "检测模型加载失败", error)
                }
                recSession = try {
                    env.createSession(recModelBytes, options)
                } catch (error: Throwable) {
                    try {
                        detSession?.close()
                    } catch (_: Throwable) {
                    }
                    detSession = null
                    throw TextExtractionException(TextExtractErrorKind.MODEL_LOAD_FAILED, "识别模型加载失败", error)
                }
                detInputName = detSession?.inputNames?.iterator()?.next()
                    ?: throw TextExtractionException(TextExtractErrorKind.MODEL_LOAD_FAILED, "检测模型缺少输入名")
                recInputName = recSession?.inputNames?.iterator()?.next()
                    ?: throw TextExtractionException(TextExtractErrorKind.MODEL_LOAD_FAILED, "识别模型缺少输入名")
            } finally {
                options.close()
            }
        } catch (error: Throwable) {
            close()
            if (error is TextExtractionException) throw error
            throw TextExtractionException(TextExtractErrorKind.MODEL_LOAD_FAILED, "ONNX Runtime 初始化失败", error)
        }
    }

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val env = environment ?: throw closedError()
        val session = detSession ?: throw closedError()
        return runSession(env, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val env = environment ?: throw closedError()
        val session = recSession ?: throw closedError()
        return runSession(env, session, recInputName, input, shape, "recognition")
    }

    override fun close() {
        try {
            detSession?.close()
        } catch (_: Throwable) {
        } finally {
            detSession = null
        }
        try {
            recSession?.close()
        } catch (_: Throwable) {
        } finally {
            recSession = null
        }
        environment = null
    }

    private fun runSession(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        stage: String
    ): Pair<FloatArray, LongArray> {
        val tensor = try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        } catch (error: Throwable) {
            throw inferenceError(stage, error)
        }
        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (error: Throwable) {
                throw inferenceError(stage, error)
            }
        } finally {
            tensor.close()
        }

        return try {
            try {
                val outputName = session.outputNames.iterator().next()
                val value = result.get(outputName).orElseThrow { IllegalStateException("模型没有输出张量") }
                val outputTensor = value as? OnnxTensor ?: error("模型输出不是 ONNX tensor")
                Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
            } catch (error: Throwable) {
                throw inferenceError(stage, error)
            }
        } finally {
            result.close()
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        duplicate.get(output)
        return output
    }

    private fun inferenceError(stage: String, cause: Throwable): TextExtractionException {
        return TextExtractionException(TextExtractErrorKind.OCR_INFERENCE_FAILED, "OCR 推理失败: " + stage, cause)
    }

    private fun closedError(): TextExtractionException {
        return TextExtractionException(TextExtractErrorKind.ENGINE_CLOSED, "OCR 引擎已关闭")
    }
}
