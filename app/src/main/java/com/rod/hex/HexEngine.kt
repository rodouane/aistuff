package com.rod.hex

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Le chef d'orchestre de Hex : réveil, écoute, réflexion, parole, relance, mémoire.
 * Tout se passe sur le thread principal, sauf les appels réseau.
 */
class HexEngine(private val context: Context) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    interface Observer {
        fun onState(state: State)
        fun onLevel(level: Float) {}
        fun onTranscript(fromRod: Boolean, text: String, partial: Boolean) {}
    }

    private val tag = "HexEngine"
    private val app = HexApp.of(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val observers = CopyOnWriteArrayList<Observer>()

    private val brain = Brain(context)
    val voice = Voice(context)
    private val listener = Listener(context)
    val wake = WakeWord(context) { main.post { trigger() } }

    var state = State.IDLE
        private set
    private val session = mutableListOf<Brain.Turn>()
    private var followUp = false
    private var offlineQueued = false
    private val timers = mutableListOf<Runnable>()
    @Volatile private var turn = 0

    fun observe(o: Observer) { observers.add(o); o.onState(state) }
    fun unobserve(o: Observer) { observers.remove(o) }

    private fun setState(s: State) {
        state = s
        app.glyphs.set(
            when (s) {
                State.IDLE -> if (app.net.online) Glyphs.Mode.IDLE else Glyphs.Mode.OFFLINE
                State.LISTENING -> Glyphs.Mode.LISTEN
                State.THINKING -> Glyphs.Mode.THINK
                State.SPEAKING -> Glyphs.Mode.SPEAK
            }
        )
        observers.forEach { it.onState(s) }
    }

    fun start() {
        app.net.listen { online ->
            main.post {
                if (state == State.IDLE) setState(State.IDLE)
                if (online) offlineQueued = true
            }
        }
        setState(State.IDLE)
        wake.start()
        // Premier lancement : Hex se présente et lance la découverte
        if (!app.memory.onboarded && app.prefs.isConfigured) {
            main.postDelayed({ if (state == State.IDLE) greetFirstTime() }, 2500)
        }
    }

    private fun greetFirstTime() {
        speakLocal("Salut Rod. Moi c'est Hex. On fait connaissance ? Dis-moi déjà ce que tu fais dans la vie.")
        session.add(Brain.Turn(true, "(Rod vient d'allumer Hex pour la première fois.)"))
        session.add(Brain.Turn(false, "Salut Rod. Moi c'est Hex. On fait connaissance ? Dis-moi déjà ce que tu fais dans la vie."))
        followUp = true
    }

    /** Mot d'activation, bouton ou toucher : écoute, ou coupe la parole. */
    fun trigger() {
        when (state) {
            State.SPEAKING, State.THINKING -> { turn++; voice.stop(); startListening(false) }
            State.LISTENING -> { listener.cancel(); endSession() }
            State.IDLE -> startListening(false)
        }
    }

    private fun startListening(isFollowUp: Boolean) {
        wake.stop()
        if (!isFollowUp) beep()
        setState(State.LISTENING)
        // laisse le bip finir avant d'ouvrir le micro
        main.postDelayed({
            listener.listen(object : Listener.Callback {
                override fun onLevel(level: Float) { observers.forEach { it.onLevel(level) } }
                override fun onPartial(text: String) { observers.forEach { it.onTranscript(true, text, true) } }
                override fun onFinal(text: String) { main.post { handle(text) } }
                override fun onNothing() { main.post { endSession() } }
            }, offline = !app.net.online, followUp = isFollowUp)
        }, if (isFollowUp) 50 else 220)
    }

    /** Question tapée au clavier. */
    fun handleTyped(text: String) {
        if (text.isBlank()) return
        turn++; voice.stop(); listener.cancel(); wake.stop()
        handle(text)
    }

    private fun handle(text: String) {
        observers.forEach { it.onTranscript(true, text, false) }
        Offline.tryAnswer(text, ::startTimer)?.let { local ->
            session.add(Brain.Turn(true, text)); session.add(Brain.Turn(false, local))
            speakLocal(local); return
        }
        if (!app.net.online) {
            app.memory.addPendingQuestion(text)
            speakLocal("Je suis hors ligne, Rod. Je garde ta question et je te réponds dès que le Wi-Fi revient.")
            return
        }
        if (!app.prefs.isConfigured) {
            speakLocal("Il me manque ma clé Claude. Relance le script d'installation sur l'ordinateur.")
            return
        }
        session.add(Brain.Turn(true, text))
        setState(State.THINKING)
        val history = session.toList()
        val queued = offlineQueued
        offlineQueued = false
        voice.begin(voiceListener)
        val myTurn = ++turn
        io.execute {
            val say = { s: String -> if (myTurn == turn) voice.say(s) }
            val splitter = SentenceSplitter { s -> say(s) }
            try {
                val full = brain.reply(history, queued) { chunk -> splitter.feed(chunk) }
                splitter.flush()
                main.post { session.add(Brain.Turn(false, full)); observers.forEach { it.onTranscript(false, full, false) } }
                if (app.budget.nearCap && !app.budget.blocked) say("Au fait, on approche de ton budget du mois.")
            } catch (e: Brain.OutOfBudget) {
                say("On a atteint le budget du mois, Rod. Je reprends le mois prochain, ou tu peux relever le plafond.")
            } catch (e: Exception) {
                Log.w(tag, "reply", e)
                splitter.flush()
                say("J'ai un souci pour joindre mon cerveau. Réessaie dans un instant.")
            }
            if (myTurn == turn) voice.finish()
        }
    }

    private val voiceListener = object : Voice.Listener {
        override fun onStart() { main.post { if (state != State.LISTENING) setState(State.SPEAKING) } }
        override fun onLevel(level: Float) {
            app.glyphs.speechLevel = level
            observers.forEach { it.onLevel(level) }
        }
        override fun onDone() {
            main.post {
                if (state == State.SPEAKING || state == State.THINKING) {
                    // Relance : Hex écoute encore quelques secondes sans mot d'activation
                    startListening(true)
                }
            }
        }
    }

    private fun speakLocal(text: String) {
        voice.begin(voiceListener)
        setState(State.THINKING)
        voice.say(text)
        voice.finish()
        observers.forEach { it.onTranscript(false, text, false) }
    }

    private fun endSession() {
        listener.cancel()
        setState(State.IDLE)
        wake.start()
        val transcript = session.filter { !it.text.startsWith("(") }.toList()
        session.clear()
        if (transcript.any { it.fromRod } && app.net.online) {
            io.execute { brain.updateMemory(transcript) }
        }
    }

    private fun startTimer(minutes: Int, label: String) {
        val r = object : Runnable {
            override fun run() {
                timers.remove(this)
                app.glyphs.set(Glyphs.Mode.ALERT)
                repeat(3) { i -> main.postDelayed({ beep(long = true) }, i * 900L) }
                main.postDelayed({
                    voice.begin(voiceListener)
                    setState(State.THINKING)
                    voice.say("Rod, ton minuteur $label est terminé.")
                    voice.finish()
                }, 2800)
            }
        }
        timers.add(r)
        main.postDelayed(r, minutes * 60_000L)
    }

    private fun beep(long: Boolean = false) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            tg.startTone(if (long) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK, if (long) 600 else 120)
            main.postDelayed({ tg.release() }, 800)
        } catch (_: Exception) {}
    }

    fun release() {
        wake.release(); voice.release(); listener.cancel()
    }

    /** Découpe le texte qui arrive en phrases à dire une par une. */
    private class SentenceSplitter(private val emit: (String) -> Unit) {
        private val buf = StringBuilder()
        fun feed(chunk: String) {
            buf.append(chunk)
            var from = 0
            while (true) {
                var idx = -1
                for (i in from until buf.length) {
                    val c = buf[i]
                    if (c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') { idx = i; break }
                }
                if (idx < 0 || idx + 1 >= buf.length) break // attend la suite
                val next = buf[idx + 1]
                // évite de couper « 3.5 » ou « www.site » : il faut un espace après la ponctuation
                if (buf[idx] != '\n' && !next.isWhitespace()) { from = idx + 1; continue }
                val sentence = buf.substring(0, idx + 1)
                buf.delete(0, idx + 1)
                from = 0
                if (sentence.trim().length > 1) emit(sentence)
            }
        }
        fun flush() { if (buf.isNotBlank()) emit(buf.toString()); buf.clear() }
    }
}

/** Ce que Hex sait faire sans Internet : l'heure, la date, des minuteurs. */
object Offline {
    private val timerRe = Regex("""minuteur\s+(?:de\s+)?(\d+)\s*(min|minute|minutes|h|heure|heures)""", RegexOption.IGNORE_CASE)

    fun tryAnswer(text: String, startTimer: (Int, String) -> Unit): String? {
        val t = text.lowercase()
        timerRe.find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val minutes = if (m.groupValues[2].startsWith("h")) n * 60 else n
            val label = "de $n ${if (m.groupValues[2].startsWith("h")) "heure" + (if (n > 1) "s" else "") else "minute" + (if (n > 1) "s" else "")}"
            startTimer(minutes, label)
            return "C'est parti, minuteur $label."
        }
        if (Regex("""quelle heure|l'heure qu'il est""").containsMatchIn(t)) {
            val n = LocalTime.now()
            return "Il est ${n.hour} heures ${if (n.minute > 0) n.minute.toString() else ""}.".replace("  ", " ")
        }
        return null
    }
}
