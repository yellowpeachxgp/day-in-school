package cn.edu.haut.campus.core.textextract

import cn.edu.haut.campus.core.textextract.internal.FPoint
import cn.edu.haut.campus.core.textextract.internal.Geometry
import cn.edu.haut.campus.core.textextract.ocr.CtcDecoder
import cn.edu.haut.campus.core.textextract.ocr.DbPostProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPostProcessTest {
    @Test
    fun ctcDecoderRemovesRepeatedLabelsAndBlank() {
        val shape = longArrayOf(1, 4, 4)
        val output = FloatArray(4 * 4) { 0.01f }
        fun put(time: Int, clazz: Int, probability: Float) {
            output[time * 4 + clazz] = probability
        }
        put(0, 1, 0.91f)
        put(1, 1, 0.92f)
        put(2, 0, 0.99f)
        put(3, 2, 0.81f)

        val decoded = CtcDecoder.decode(output, shape, listOf("甲", "乙", "丙"))
        assertEquals(1, decoded.size)
        assertEquals("甲乙", decoded[0].first)
        assertEquals(0.86f, decoded[0].second, 0.001f)
    }

    @Test
    fun minimumAreaRectRecoversAxisAlignedRectangle() {
        val rect = Geometry.minimumAreaRect(
            listOf(FPoint(2.0, 2.0), FPoint(10.0, 2.0), FPoint(10.0, 6.0), FPoint(2.0, 6.0))
        )
        assertEquals(8.0, rect.width, 0.001)
        assertEquals(4.0, rect.height, 0.001)
        val ordered = Geometry.orderQuadPoints(rect.corners)
        assertEquals(2.0, ordered[0].x, 0.001)
        assertEquals(2.0, ordered[0].y, 0.001)
    }

    @Test
    fun dbPostProcessorFindsSyntheticTextBlobWithExpandedBox() {
        val width = 64
        val height = 32
        val pred = FloatArray(width * height) { 0.01f }
        for (y in 8..15) {
            for (x in 10..42) {
                pred[y * width + x] = 0.94f
            }
        }
        val boxes = DbPostProcessor.process(
            pred = pred,
            predShape = longArrayOf(1, 1, height.toLong(), width.toLong()),
            thresh = 0.2f,
            boxThresh = 0.4f,
            unclipRatio = 1.4f,
            maxCandidates = 100,
            useDilation = false,
            scoreMode = "fast",
            originalWidth = width,
            originalHeight = height
        )
        assertEquals(1, boxes.size)
        val box = boxes[0]
        assertTrue(box.minOf { it.x } < 10.0)
        assertTrue(box.maxOf { it.x } > 42.0)
        assertTrue(box.minOf { it.y } < 8.0)
        assertTrue(box.maxOf { it.y } > 15.0)
    }
}
