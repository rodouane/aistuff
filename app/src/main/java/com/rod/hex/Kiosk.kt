package com.rod.hex

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

class HexAdminReceiver : DeviceAdminReceiver()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HexService.start(context)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Mode kiosque : quand Hex est « propriétaire de l'appareil » (adb dpm set-device-owner),
 * il devient l'écran d'accueil permanent, bloque les autres apps et la barre d'état.
 */
object Kiosk {
    private const val TAG = "HexKiosk"

    fun admin(ctx: Context) = ComponentName(ctx, HexAdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)!!

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Applique toutes les règles du kiosque. Sans effet si Hex n'est pas propriétaire. */
    fun apply(activity: Activity) {
        val ctx = activity
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName))
            // Menu du bouton Power gardé : on peut toujours redémarrer ou éteindre.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)

            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))

            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            // Écran allumé tant que le téléphone est branché (secteur, USB, sans fil)
            val plugged = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())

            for (p in listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS,
            )) {
                dpm.setPermissionGrantState(admin, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "règles kiosque partielles", e)
        }
        try {
            activity.startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask", e)
        }
    }

    /**
     * Le kiosque ne s'active que si tout va bien : clé Claude présente, Internet déjà vu une fois,
     * et pas de sortie de secours demandée. Sinon le téléphone reste un Android normal.
     */
    fun sync(activity: Activity) {
        val p = HexApp.of(activity).prefs
        if (p.kioskOn && p.isConfigured && p.everOnline) apply(activity) else release(activity)
    }

    /** Retire complètement Hex comme propriétaire : le téléphone redevient normal et Hex désinstallable. */
    fun removeOwner(activity: Activity) {
        release(activity)
        if (!isOwner(activity)) return
        try { dpm(activity).clearDeviceOwnerApp(activity.packageName) } catch (e: Exception) { Log.w(TAG, "clearDeviceOwner", e) }
    }

    /** Rend la main à Android (sortie de secours). */
    fun release(activity: Activity) {
        try { activity.stopLockTask() } catch (_: Exception) {}
        if (!isOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
        } catch (e: Exception) {
            Log.w(TAG, "release", e)
        }
    }
}
