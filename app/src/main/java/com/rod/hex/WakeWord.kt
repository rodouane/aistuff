package com.rod.hex

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Détection de « Hey Hex » sur le téléphone (Picovoice Porcupine), sans rien envoyer en ligne.
 * Mot sur mesure : fichier files/hey-hex.ppn (créé sur console.picovoice.ai, poussé par le script).
 * Sans ce fichier, Hex répond au mot intégré « Jarvis » en attendant.
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {
    private val tag = "HexWake"
    private var manager: PorcupineManager? = null
    private var running = false
    var label = "HEY HEX"
        private set

    private fun build(): PorcupineManager? {
        val key = HexApp.of(context).prefs.picovoiceKey
        if (key.isBlank()) return null
        return try {
            val custom = File(context.filesDir, "hey-hex.ppn")
            val b = PorcupineManager.Builder().setAccessKey(key).setSensitivity(0.6f)
                .setErrorCallback { e -> Log.w(tag, "porcupine", e) }
            if (custom.exists()) {
                b.setKeywordPath(custom.absolutePath); label = "HEY HEX"
            } else {
                b.setKeyword(Porcupine.BuiltInKeyword.JARVIS); label = "JARVIS"
            }
            b.build(context.applicationContext) { _ -> onWake() }
        } catch (e: Exception) {
            Log.w(tag, "init", e)
            null
        }
    }

    val available: Boolean get() = HexApp.of(context).prefs.picovoiceKey.isNotBlank()

    fun start() {
        if (running) return
        if (manager == null) manager = build()
        try {
            manager?.start(); running = manager != null
        } catch (e: Exception) { Log.w(tag, "start", e) }
    }

    fun stop() {
        if (!running) return
        try { manager?.stop() } catch (e: Exception) { Log.w(tag, "stop", e) }
        running = false
    }

    fun release() {
        stop()
        manager?.delete(); manager = null
    }
}
