package com.rod.hex

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Reçoit la configuration envoyée par le script d'installation :
 *   adb shell am start -n com.rod.hex/.SetupActivity --es claude ... --es picovoice ... --es tts ...
 */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = HexApp.of(this).prefs
        intent?.extras?.let { x ->
            x.getString("claude")?.takeIf { it.isNotBlank() }?.let { p.claudeKey = it.trim() }
            x.getString("picovoice")?.takeIf { it.isNotBlank() }?.let { p.picovoiceKey = it.trim() }
            x.getString("tts")?.takeIf { it.isNotBlank() }?.let { p.ttsKey = it.trim() }
            x.getString("voice")?.takeIf { it.isNotBlank() }?.let { p.ttsVoice = it.trim() }
            x.getString("model")?.takeIf { it.isNotBlank() }?.let { p.model = it.trim() }
            x.getString("pin")?.takeIf { it.isNotBlank() }?.let { p.pin = it.trim() }
            x.getString("budget")?.toFloatOrNull()?.let { p.budgetCapEur = it }
        }
        Log.i("HexSetup", "configuré : claude=${p.claudeKey.isNotBlank()} picovoice=${p.picovoiceKey.isNotBlank()} tts=${p.ttsKey.isNotBlank()}")
        // Redémarre Hex avec la nouvelle configuration
        stopService(Intent(this, HexService::class.java))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
