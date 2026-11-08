package com.rod.hex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * La voix de Hex. Phrase par phrase : voix réaliste Google Cloud (Chirp 3 HD) quand on est
 * en ligne et qu'une clé est fournie, sinon la voix d'Android. Publie le niveau sonore pour
 * l'animation à l'écran et les Glyph.
 */
class Voice(private val context: Context) {
    interface Listener {
        fun onStart()
        fun onLevel(level: Float)
        fun onDone()
    }

    private val tag = "HexVoice"
    private val app = HexApp.of(context)
    private val queue = LinkedBlockingQueue<Item>()
    private val generation = AtomicInteger(0)
    @Volatile private var listener: Listener? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var speaking = false
    private var pendingInTurn = AtomicInteger(0)
    private var endOfTurn = false

    private data class Item(val text: String, val gen: Int, val end: Boolean)

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                applyChoice()
                ttsReady = true
            }
        }
        Thread({ worker() }, "hex-voice").apply { isDaemon = true; start() }
    }

    /** Voix françaises disponibles sur le téléphone, celles hors ligne d'abord. */
    fun frenchVoices(): List<android.speech.tts.Voice> =
        (tts?.voices ?: emptySet()).filter { it.locale.language == "fr" }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != "FR" }, { it.name }))

    /** Applique la voix et le ton enregistrés (ou une voix masculine par défaut). */
    fun applyChoice() {
        val engine = tts ?: return
        val all = frenchVoices()
        val chosen = all.firstOrNull { it.name == app.prefs.androidVoice }
            ?: all.firstOrNull { !it.isNetworkConnectionRequired && it.name.contains("male", true) && !it.name.contains("female", true) }
        chosen?.let { engine.voice = it }
        engine.setPitch(app.prefs.voicePitch)
    }

    /** Fait écouter une voix et un ton sans les enregistrer. */
    fun preview(name: String, pitch: Float) {
        val engine = tts ?: return
        stop()
        frenchVoices().firstOrNull { it.name == name }?.let { engine.voice = it }
        engine.setPitch(pitch)
        engine.speak("Salut Rod, c'est Hex. Ça te va, cette voix ?", TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
    }

    fun begin(l: Listener) {
        listener = l
        endOfTurn = false
    }

    /** Ajoute une phrase à dire. */
    fun say(sentence: String) {
        val s = sentence.trim()
        if (s.isEmpty()) return
        pendingInTurn.incrementAndGet()
        queue.put(Item(s, generation.get(), false))
    }

    /** Signale que la réponse est complète : onDone partira après la dernière phrase. */
    fun finish() {
        queue.put(Item("", generation.get(), true))
    }

    /** Coupe la parole immédiatement. */
    fun stop() {
        generation.incrementAndGet()
        queue.clear()
        pendingInTurn.set(0)
        track?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        tts?.stop()
        speaking = false
    }

    val isSpeaking: Boolean get() = speaking

    private fun worker() {
        while (true) {
            val item = queue.take()
            if (item.gen != generation.get()) continue
            if (item.end) {
                val l = listener
                speaking = false
                l?.onLevel(0f)
                l?.onDone()
                continue
            }
            if (!speaking) { speaking = true; listener?.onStart() }
            val ok = if (app.net.online && app.prefs.ttsKey.isNotBlank()) cloud(item) else false
            if (!ok && item.gen == generation.get()) local(item)
            pendingInTurn.decrementAndGet()
        }
    }

    // ---------- Google Cloud Text-to-Speech ----------
    private fun cloud(item: Item): Boolean = try {
        val body = JSONObject()
            .put("input", JSONObject().put("text", item.text))
            .put("voice", JSONObject().put("languageCode", "fr-FR").put("name", app.prefs.ttsVoice))
            .put("audioConfig", JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", 24000))
        val c = URL("https://texttospeech.googleapis.com/v1/text:synthesize").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 6000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("X-Goog-Api-Key", app.prefs.ttsKey)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) {
            Log.w(tag, "TTS cloud ${c.responseCode}: " + (c.errorStream?.bufferedReader()?.readText() ?: ""))
            false
        } else {
            val json = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val wav = Base64.decode(json.getString("audioContent"), Base64.DEFAULT)
            play(wav, item.gen)
            true
        }
    } catch (e: Exception) {
        Log.w(tag, "TTS cloud", e); false
    }

    private fun play(wav: ByteArray, gen: Int) {
        val offset = if (wav.size > 44 && String(wav, 0, 4) == "RIFF") 44 else 0
        val rate = 24000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { track = it }
        t.play()
        val chunk = 2400 // 50 ms
        var i = offset
        while (i < wav.size && gen == generation.get()) {
            val n = minOf(chunk, wav.size - i)
            // niveau RMS du morceau pour l'animation
            var sum = 0.0
            var k = i
            while (k + 1 < i + n) {
                val s = ((wav[k + 1].toInt() shl 8) or (wav[k].toInt() and 0xff)).toShort().toDouble()
                sum += s * s; k += 2
            }
            val rms = sqrt(sum / (n / 2).coerceAtLeast(1)) / 6000.0
            listener?.onLevel(rms.toFloat().coerceIn(0f, 1f))
            t.write(wav, i, n)
            i += n
        }
        // laisse le tampon se vider
        if (gen == generation.get()) Thread.sleep(120)
    }

    // ---------- Voix Android (hors ligne) ----------
    private fun local(item: Item) {
        val engine = tts ?: return
        var waited = 0
        while (!ttsReady && waited < 3000) { Thread.sleep(100); waited += 100 }
        val lock = Object()
        var finished = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { synchronized(lock) { finished = true; lock.notifyAll() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { synchronized(lock) { finished = true; lock.notifyAll() } }
        })
        engine.speak(item.text, TextToSpeech.QUEUE_ADD, Bundle(), "hex-" + System.nanoTime())
        // niveau simulé pendant la lecture
        var t = 0
        while (true) {
            synchronized(lock) { if (finished) return@local }
            if (item.gen != generation.get()) return
            val lvl = (0.35 + 0.35 * kotlin.math.abs(kotlin.math.sin(t * 0.9)) + 0.2 * kotlin.math.sin(t * 0.37)).toFloat()
            listener?.onLevel(lvl.coerceIn(0f, 1f))
            Thread.sleep(60); t++
            if (t > 1000) return
        }
    }

    fun release() {
        stop()
        track?.release(); track = null
        tts?.shutdown(); tts = null
    }
}
