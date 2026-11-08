package com.rod.hex

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Ajouter un Wi-Fi dans un nouveau lieu : ouvre le panneau Wi-Fi d'Android par-dessus Hex.
 * En mode kiosque, les Réglages sont autorisés le temps de la connexion, puis de nouveau bloqués.
 */
object WifiPanel {
    private const val SETTINGS_PKG = "com.android.settings"
    @Volatile var open = false
        private set

    fun show(activity: Activity) {
        if (Kiosk.isOwner(activity)) {
            try {
                val dpm = activity.getSystemService(DevicePolicyManager::class.java)!!
                dpm.setLockTaskPackages(Kiosk.admin(activity), arrayOf(activity.packageName, SETTINGS_PKG))
            } catch (e: Exception) { Log.w("HexWifi", "lock task", e) }
        }
        open = true
        val panel = Intent(Settings.Panel.ACTION_WIFI)
        try {
            activity.startActivity(panel)
        } catch (e: Exception) {
            try { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { open = false }
        }
    }

    /** Au retour sur Hex : on referme la porte des Réglages. */
    fun closed(activity: Activity) {
        if (!open) return
        open = false
        if (Kiosk.isOwner(activity)) {
            try {
                val dpm = activity.getSystemService(DevicePolicyManager::class.java)!!
                dpm.setLockTaskPackages(Kiosk.admin(activity), arrayOf(activity.packageName))
            } catch (e: Exception) { Log.w("HexWifi", "lock task", e) }
        }
    }
}
