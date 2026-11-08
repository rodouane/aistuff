package com.rod.hex

import android.content.Context
import android.content.SharedPreferences

/** Réglages de Hex : clés API, voix, budget, code de sortie du mode kiosque. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("hex", Context.MODE_PRIVATE)

    var claudeKey: String by str("claude_key")
    var picovoiceKey: String by str("picovoice_key")
    var ttsKey: String by str("tts_key")
    var ttsVoice: String by str("tts_voice", "fr-FR-Chirp3-HD-Charon")
    var model: String by str("model", "claude-haiku-5-5")
    var pin: String by str("pin", "2580")
    /** Voix Android choisie dans le menu caché (vide = automatique). */
    var androidVoice: String by str("android_voice")
    var voicePitch: Float
        get() = sp.getFloat("voice_pitch", 1f)
        set(v) = sp.edit().putFloat("voice_pitch", v).apply()
    var budgetCapEur: Float
        get() = sp.getFloat("budget_cap", 10f)
        set(v) = sp.edit().putFloat("budget_cap", v).apply()

    /** Mode kiosque voulu. Faux après une sortie de secours, jusqu'à « Revenir en mode kiosque ». */
    var kioskOn: Boolean
        get() = sp.getBoolean("kiosk_on", true)
        set(v) = sp.edit().putBoolean("kiosk_on", v).apply()
    /** Hex a déjà eu Internet au moins une fois. Avant ça, jamais de kiosque. */
    var everOnline: Boolean
        get() = sp.getBoolean("ever_online", false)
        set(v) = sp.edit().putBoolean("ever_online", v).apply()

    val isConfigured: Boolean get() = claudeKey.isNotBlank()

    private fun str(key: String, def: String = "") = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    fun raw(): SharedPreferences = sp
}
