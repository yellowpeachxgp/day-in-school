package cn.edu.haut.campus.widget.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 小组件教室格式化回归：楼号与房间号绝不丢失（完整式/紧凑式），校区前缀剥离，
 * 隐私模式一律脱敏，空串与非常规格式有稳定兜底。
 */
class WidgetRoomFormatterTest {

    @Test
    fun fullRoom_buildingAndRoomNumberPreservedWithSpace() {
        assertEquals("5号教学楼 5204", WidgetRoomFormatter.formatFullRoom("莲花街校区5号教学楼5204"))
        assertEquals("5号教学楼 5204", WidgetRoomFormatter.formatFullRoom("河南工业大学莲花街校区5号教学楼5204"))
        assertEquals("32号楼", WidgetRoomFormatter.formatFullRoom("32号楼"))
    }

    @Test
    fun fullRoom_nonStandardFormatFallsBackToCleanedOriginal() {
        // 不匹配「楼宇+房号」正则的地点原样保留（剥离校区前缀后），绝不截断房号
        assertEquals("图书馆东配楼201", WidgetRoomFormatter.formatFullRoom("莲花街校区图书馆东配楼201"))
        assertEquals("待定教室", WidgetRoomFormatter.formatFullRoom("莲花街校区"))
        assertEquals("待定教室", WidgetRoomFormatter.formatFullRoom(""))
    }

    @Test
    fun fullRoom_specialBuildingsKeepRoomSuffix() {
        // 数字开头的房号插入空格分隔；字母开头的房号不匹配拆分正则，原样保留绝不截断
        assertEquals("钟楼报告厅 101", WidgetRoomFormatter.formatFullRoom("钟楼报告厅101"))
        assertEquals("钟楼报告厅A101", WidgetRoomFormatter.formatFullRoom("钟楼报告厅A101"))
        assertEquals("实验楼 3-204", WidgetRoomFormatter.formatFullRoom("实验楼3-204"))
    }

    @Test
    fun compactRoom_longFormCompressesButKeepsNumbers() {
        assertEquals("5教5204", WidgetRoomFormatter.formatCompactRoom("莲花街校区5号教学楼5204"))
        assertEquals("32教32204", WidgetRoomFormatter.formatCompactRoom("32号教学楼32204"))
    }

    @Test
    fun compactRoom_fallbackNeverDropsRoomDigits() {
        val compact = WidgetRoomFormatter.formatCompactRoom("莲花街校区8号教学楼W111")
        assertEquals("8教W111", compact)
        assertEquals("待定", WidgetRoomFormatter.formatCompactRoom(""))
    }

    @Test
    fun privacyMode_alwaysMasksRegardlessOfInput() {
        assertEquals("地点已隐藏", WidgetRoomFormatter.formatFullRoom("莲花街校区5号教学楼5204", privacyMode = true))
        assertEquals("校区", WidgetRoomFormatter.formatCompactRoom("莲花街校区5号教学楼5204", privacyMode = true))
    }
}
