package cn.edu.haut.campus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.haut.campus.core.designsystem.LauncherIconAliasReconciler

/**
 * APK 覆盖升级完成广播接收器：即使用户升级后尚未手动点开应用，也在 `MY_PACKAGE_REPLACED` 时立即收敛互斥桌面图标别名。
 */
class LauncherIconUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LauncherIconAliasReconciler.reconcile(context.applicationContext)
        }
    }
}
