package cn.edu.haut.campus.core.textextract

import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.textextract.ocr.PaddleOcrConfig
import cn.edu.haut.campus.core.textextract.ocr.PaddleOcrOnnxEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleOcrOnnxEngineTest {
    @Test
    fun syntheticChineseScreenshotExtractsLinesWithTraceableCoordinates() = runBlocking {
        val engine = PaddleOcrOnnxEngine.fromModelBytes(
            detModelBytes = FixtureLoader.detModelBytes(),
            recModelBytes = FixtureLoader.recModelBytes(),
            recConfigYaml = FixtureLoader.recConfigYaml(),
            config = PaddleOcrConfig(numThreads = 2)
        )
        try {
            val result = engine.extractImage(
                ImageExtractionRequest(
                    image = FixtureLoader.pngAsArgbImage("synthetic_notice.png"),
                    context = TextExtractionContext.synthetic()
                )
            )

            val compactText = result.plainText.replace(" ", "")
            assertTrue("合成截图至少应抽出 4 行，实际: " + result.plainText, result.lines.size >= 4)
            assertTrue(compactText.contains("课程调整"))
            assertTrue(compactText.contains("数据结构"))
            assertTrue(compactText.contains("莲花街校区"))
            assertTrue(compactText.contains("2026年12月2日"))

            val title = result.lines.first()
            assertEquals(TextCoordinateSpace.IMAGE_PIXEL_TOP_LEFT, title.coordinateSpace)
            assertTrue(title.confidence ?: 0f > 0.5f)
            assertTrue(title.box.minX >= 30f)
            assertTrue(title.box.minY >= 30f)
            assertTrue(title.box.width > 100f)
            assertTrue(title.box.height > 20f)
            assertTrue(result.lines.all { it.box.points.size == 4 })
            assertTrue(result.sourceHashSha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(VerificationLevel.MOCK_TESTED, result.provenance.trust)
            assertNotEquals(VerificationLevel.DEVICE_TESTED, result.provenance.trust)
            assertEquals(ExtractionConfirmationState.UNCONFIRMED, result.confirmationState)
            assertTrue(result.metadata.requiresUserConfirmation)
            assertEquals(PaddleOcrConfig.DET_SHA256, result.metadata.engine.modelArtifactsSha256["det"])
            assertEquals(PaddleOcrConfig.REC_SHA256, result.metadata.engine.modelArtifactsSha256["rec"])
        } finally {
            engine.close()
        }
    }

    @Test
    fun corruptedModelBytesFailIntegrityBeforeInference() {
        val corruptedDet = FixtureLoader.detModelBytes()
        corruptedDet[0] = (corruptedDet[0].toInt() xor 0x01).toByte()
        try {
            PaddleOcrOnnxEngine.fromModelBytes(
                detModelBytes = corruptedDet,
                recModelBytes = FixtureLoader.recModelBytes(),
                recConfigYaml = FixtureLoader.recConfigYaml(),
                config = PaddleOcrConfig(numThreads = 1)
            )
            org.junit.Assert.fail("被篡改的检测模型不应加载")
        } catch (error: TextExtractionException) {
            assertEquals(TextExtractErrorKind.MODEL_INTEGRITY_MISMATCH, error.kind)
        }

        val corruptedRec = FixtureLoader.recModelBytes()
        corruptedRec[0] = (corruptedRec[0].toInt() xor 0x01).toByte()
        try {
            PaddleOcrOnnxEngine.fromModelBytes(
                detModelBytes = FixtureLoader.detModelBytes(),
                recModelBytes = corruptedRec,
                recConfigYaml = FixtureLoader.recConfigYaml(),
                config = PaddleOcrConfig(numThreads = 1)
            )
            org.junit.Assert.fail("被篡改的识别模型不应加载")
        } catch (error: TextExtractionException) {
            assertEquals(TextExtractErrorKind.MODEL_INTEGRITY_MISMATCH, error.kind)
        }
    }
}
