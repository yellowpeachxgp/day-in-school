package cn.edu.haut.campus.feature.settings.library.seat.ui

/** 微桃 → 🍑 整数/小数自适应显示（1.0 显示为 "1"，0.5 显示为 "0.5"） */
internal fun formatMicropeachPriceLabel(micropeach: Long): String {
    val whole = micropeach / 10_000L
    val rem = micropeach % 10_000L
    return if (rem == 0L) whole.toString() else (micropeach / 10_000.0).toString()
}
