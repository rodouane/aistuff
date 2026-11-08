package com.rod.hex

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Détection de « Hey Hex » sur le téléphone, gratuite et sans compte (Vosk, logiciel libre).
 * Au premier démarrage, Hex télécharge un petit modèle vocal anglais (environ 40 Mo) ;
 * ensuite tout marche hors ligne et rien n'est envoyé tant que Hex dort.
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {
    private val tag = "HexWake"
    private val io = Executors.newSingleThreadExecutor()
    private val modelDir = File(context.filesDir, "vosk-en")
    private val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    @Volatile private var wanted = false
    @Volatile private var loading = false
    private var lastWake = 0L

    val label = "HEY HEX"
    val available: Boolean get() = model != null

    fun start() {
        wanted = true
        if (model == null) { load(); return }
        if (service != null) return
        try {
            val rec = recognizer ?: Recognizer(model, 16000f, "[\"hey hex\", \"[unk]\"]").also { recognizer = it }
            rec.reset()
            val s = SpeechService(rec, 16000f)
            s.startListening(listener)
            service = s
        } catch (e: Exception) {
            Log.w(tag, "start", e)
        }
    }

    fun stop() {
        wanted = false
        service?.let { try { it.stop(); it.shutdown() } catch (_: Exception) {} }
        service = null
    }

    fun release() {
        stop()
        recognizer?.close(); recognizer = null
        model?.close(); model = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String) = check(hypothesis, "text")
        override fun onError(exception: Exception) { Log.w(tag, "vosk", exception) }
        override fun onTimeout() {}
    }

    private fun check(json: String, key: String) {
        val text = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        if (!text.contains("hey hex")) return
        val now = System.currentTimeMillis()
        if (now - lastWake < 2000) return
        lastWake = now
        recognizer?.reset()
        onWake()
    }

    /** Charge le modèle, en le téléchargeant d'abord si besoin. */
    private fun load() {
        if (loading) return
        loading = true
        io.execute {
            try {
                val ready = File(modelDir, "ready")
                if (!ready.exists()) download()
                val m = Model(modelDir.absolutePath)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    model = m
                    loading = false
                    if (wanted) start()
                }
            } catch (e: Exception) {
                Log.w(tag, "modèle Vosk", e)
                loading = false
            }
        }
    }

    private fun download() {
        if (!HexApp.of(context).net.online) throw IllegalStateException("hors ligne")
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        val c = URL(modelUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        ZipInputStream(c.inputStream.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // retire le dossier racine du zip (vosk-model-small-en-us-0.15/...)
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(modelDir, rel)
                if (!out.canonicalPath.startsWith(modelDir.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        File(modelDir, "ready").writeText("ok")
    }
}
