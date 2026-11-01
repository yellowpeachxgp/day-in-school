package cn.edu.haut.campus.core.protocol.zf

import java.util.Locale

/**
 * 最小 HTML 表格模型（UNVERIFIED）。
 *
 * 旧正方课表用 rowspan/colspan 表示跨节次、跨星期的同一门课，
 * 因此表格必须先展开成矩形网格，第 c 列才始终对应同一个“星期”。
 * 只按合成夹具与公开页面形态设计，未与任何真实学校页面核对。
 */
internal data class ZfHtmlCell(
    val text: String,
    val rowSpan: Int = 1,
    val colSpan: Int = 1
)

internal class ZfHtmlTable(val rows: List<List<ZfHtmlCell>>) {

    val rowCount: Int get() = rows.size


    /**
     * 展开为矩形网格。跨行/跨列的位置重复写入同一单元格文本，
     * 被跨过的空位补空字符串；行列不足时按需补齐，保证调用方可以安全用下标访问。
     */
    fun expand(): List<List<String>> {
        val values = mutableListOf<MutableList<String>>()
        val filled = mutableListOf<MutableList<Boolean>>()

        fun ensure(row: Int, column: Int) {
            while (values.size <= row) {
                values.add(mutableListOf())
                filled.add(mutableListOf())
            }
            while (values[row].size <= column) {
                values[row].add("")
                filled[row].add(false)
            }
        }

        rows.forEachIndexed { rowIndex, cells ->
            ensure(rowIndex, 0)
            var column = 0
            for (cell in cells) {
                while (column < filled[rowIndex].size && filled[rowIndex][column]) column++
                val rowSpan = cell.rowSpan.coerceIn(1, MAX_SPAN)
                val colSpan = cell.colSpan.coerceIn(1, MAX_SPAN)
                for (offsetRow in 0 until rowSpan) {
                    for (offsetColumn in 0 until colSpan) {
                        val targetRow = rowIndex + offsetRow
                        val targetColumn = column + offsetColumn
                        ensure(targetRow, targetColumn)
                        if (!filled[targetRow][targetColumn]) {
                            values[targetRow][targetColumn] = cell.text
                            filled[targetRow][targetColumn] = true
                        }
                    }
                }
                column += colSpan
            }
        }
        return values
    }

    private companion object {
        /** 防止恶意或错误的 span 值撑爆内存。 */
        const val MAX_SPAN = 64
    }
}
