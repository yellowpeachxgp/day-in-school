package cn.edu.haut.campus.core.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import cn.edu.haut.campus.core.model.Building

object CampusMapNavigator {

    /**
     * 调起外部地图步行导航或系统地理位置查看器。
     * 遵循 PRD 第 6 节：首版允许外部地图步行导航跳转；室外定位为楼级参考。
     */
    fun launchWalkingNavigation(context: Context, building: Building) {
        val label = Uri.encode("河南工业大学莲花街校区 ${building.name}")
        // 优先尝试标准 geo URI，兼容高德、百度、Google Maps 与系统地图选择器
        val geoUri = Uri.parse(
            "geo:${building.latitude},${building.longitude}?q=${building.latitude},${building.longitude}($label)"
        )
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val packageManager = context.packageManager
        if (geoIntent.resolveActivity(packageManager) != null) {
            context.startActivity(geoIntent)
            return
        }

        // 回退到高德网页步行导航链接
        val webUri = Uri.parse(
            "https://uri.amap.com/marker?position=${building.longitude},${building.latitude}&name=$label&callnative=1"
        )
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            Toast.makeText(
                context,
                "目标建筑：${building.name} (${building.latitude}, ${building.longitude})",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
