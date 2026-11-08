package com.rod.hex

import android.app.Activity
import android.app.AlertDialog
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/** Ajouter un Wi-Fi dans un nouveau lieu : liste des réseaux autour, puis le mot de passe. */
object WifiPanel {
    fun show(activity: Activity) {
        val wm = activity.applicationContext.getSystemService(WifiManager::class.java)!!
        if (!wm.isWifiEnabled) {
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = true
        }
        @Suppress("DEPRECATION")
        wm.startScan()
        Toast.makeText(activity, "Recherche des réseaux…", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ list(activity, wm) }, 2500)
    }

    private fun list(activity: Activity, wm: WifiManager) {
        val results: List<ScanResult> = try { wm.scanResults } catch (_: SecurityException) { emptyList() }
        @Suppress("DEPRECATION")
        val ssids = results.map { it.SSID }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        if (ssids.isEmpty()) {
            Toast.makeText(activity, "Aucun réseau trouvé. Réessaie.", Toast.LENGTH_LONG).show(); return
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nouveau Wi-Fi")
            .setItems(ssids.toTypedArray()) { _, i -> askPassword(activity, wm, ssids[i]) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun askPassword(activity: Activity, wm: WifiManager, ssid: String) {
        val field = EditText(activity).apply {
            hint = "Mot de passe (vide si réseau ouvert)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(ssid).setView(field)
            .setPositiveButton("Connecter") { _, _ -> connect(activity, wm, ssid, field.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun connect(activity: Activity, wm: WifiManager, ssid: String, pass: String) {
        val ok = if (Kiosk.isOwner(activity)) {
            // Propriétaire de l'appareil : Android autorise encore l'ajout direct d'un réseau
            @Suppress("DEPRECATION")
            val conf = WifiConfiguration().apply {
                SSID = "\"" + ssid + "\""
                if (pass.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                else preSharedKey = "\"" + pass + "\""
            }
            @Suppress("DEPRECATION")
            val id = wm.addNetwork(conf)
            @Suppress("DEPRECATION")
            id != -1 && wm.enableNetwork(id, true)
        } else {
            val s = WifiNetworkSuggestion.Builder().setSsid(ssid).apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
            wm.addNetworkSuggestions(listOf(s)) == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        }
        Toast.makeText(activity, if (ok) "Connexion à $ssid…" else "Impossible d'ajouter $ssid", Toast.LENGTH_LONG).show()
    }
}
