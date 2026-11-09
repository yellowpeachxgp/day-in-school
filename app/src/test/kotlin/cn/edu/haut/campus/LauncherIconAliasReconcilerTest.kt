package cn.edu.haut.campus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import cn.edu.haut.campus.core.designsystem.LauncherIconAliasReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LauncherIconAliasReconcilerTest {

    private fun activeOurLauncherAliases(context: Context): List<String> {
        val pm = context.packageManager
        val pkg = context.packageName
        val defaultComp = ComponentName(pkg, LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME)
        val animeComp = ComponentName(pkg, LauncherIconAliasReconciler.ANIME_ALIAS_CLASS_NAME)
        val defaultState = pm.getComponentEnabledSetting(defaultComp)
        val animeState = pm.getComponentEnabledSetting(animeComp)
        // Manifest defaults: MainActivityDefaultIconAlias=true, MainActivityAnimeIconAlias=false
        val active = mutableListOf<String>()
        if (defaultState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            defaultState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        ) {
            active += defaultComp.className
        }
        if (animeState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            active += animeComp.className
        }
        return active
    }

    @Test
    fun upgradeFromLegacyAnimeEnabledNormalizesToSingleLauncher() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val pkg = context.packageName
        val defaultAlias = ComponentName(pkg, LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME)
        val animeAlias = ComponentName(pkg, LauncherIconAliasReconciler.ANIME_ALIAS_CLASS_NAME)
        val prefs = context.getSharedPreferences("launcher_icon_alias_reconciler", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // 1) Simulate old version where user had enabled MainActivityAnimeIconAlias (ENABLED),
        //    and upgraded to new APK where MainActivityDefaultIconAlias has manifest default=true (DEFAULT):
        pm.setComponentEnabledSetting(
            defaultAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            animeAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        // Before reconciliation, BOTH launcher aliases are effectively active!
        assertEquals(2, activeOurLauncherAliases(context).size)

        // Trigger MY_PACKAGE_REPLACED upgrade broadcast receiver
        LauncherIconUpgradeReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        // After upgrade reconciliation: ONLY MainActivityAnimeIconAlias remains active!
        assertEquals(
            listOf(LauncherIconAliasReconciler.ANIME_ALIAS_CLASS_NAME),
            activeOurLauncherAliases(context)
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(defaultAlias)
        )
    }

    @Test
    fun freshInstallDefaultUpgradeAndInterruptedToggleAlwaysYieldSingleLauncher() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val pkg = context.packageName
        val defaultAlias = ComponentName(pkg, LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME)
        val animeAlias = ComponentName(pkg, LauncherIconAliasReconciler.ANIME_ALIAS_CLASS_NAME)
        val prefs = context.getSharedPreferences("launcher_icon_alias_reconciler", Context.MODE_PRIVATE)

        // 1) Fresh install: both COMPONENT_ENABLED_STATE_DEFAULT -> single default launcher
        prefs.edit().clear().commit()
        pm.setComponentEnabledSetting(defaultAlias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(animeAlias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        assertFalse(LauncherIconAliasReconciler.reconcile(context))
        assertEquals(
            listOf(LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME),
            activeOurLauncherAliases(context)
        )

        // 2) Default-icon upgrade: anime explicitly DISABLED, default DEFAULT -> single default launcher
        prefs.edit().clear().commit()
        pm.setComponentEnabledSetting(defaultAlias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(animeAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertFalse(LauncherIconAliasReconciler.reconcile(context))
        assertEquals(
            listOf(LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME),
            activeOurLauncherAliases(context)
        )

        // 3) Interrupted toggle (anime -> default crashed after enabling default before disabling anime!)
        LauncherIconAliasReconciler.applyUserIconSelection(context, enableAnimeIcon = true)
        assertEquals(listOf(LauncherIconAliasReconciler.ANIME_ALIAS_CLASS_NAME), activeOurLauncherAliases(context))
        prefs.edit().putString("desired_style", LauncherIconAliasReconciler.STYLE_DEFAULT).commit()
        pm.setComponentEnabledSetting(defaultAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(animeAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        assertEquals(2, activeOurLauncherAliases(context).size)

        assertFalse(LauncherIconAliasReconciler.reconcile(context))
        assertEquals(
            listOf(LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME),
            activeOurLauncherAliases(context)
        )

        // 4) Corrupted zero-launcher state (both DISABLED) -> recovers to single active launcher!
        prefs.edit().clear().commit()
        pm.setComponentEnabledSetting(defaultAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(animeAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertEquals(0, activeOurLauncherAliases(context).size)

        LauncherIconAliasReconciler.reconcile(context)
        assertEquals(
            listOf(LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME),
            activeOurLauncherAliases(context)
        )

        // Verify PackageManager launcher query also resolves exported activity-alias entry
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        assertTrue(resolved.size <= 1)
    }
}
