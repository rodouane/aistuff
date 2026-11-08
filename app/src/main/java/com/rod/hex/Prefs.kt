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
    var budgetCapEur: Float
        get() = sp.getFloat("budget_cap", 10f)
        set(v) = sp.edit().putFloat("budget_cap", v).apply()

    val isConfigured: Boolean get() = claudeKey.isNotBlank()

    private fun str(key: String, def: String = "") = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    fun raw(): SharedPreferences = sp
}
