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
    @Volatile private var turn = 0
    /** La conversation précédente, pour que Hex garde le fil si Rod revient peu après. */
    private var lastSession: List<Brain.Turn> = emptyList()
    private var lastSessionEnd = 0L
    /** Rappels arrivés à échéance, dits dès que Hex est libre. */
    private val dueQueue = mutableListOf<Reminders.Item>()

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
                if (online) {
                    offlineQueued = true
                    io.execute { brain.retryQueued() }
                }
            }
        }
        setState(State.IDLE)
        wake.start()
        io.execute { brain.retryQueued() }
        main.postDelayed(reminderTick, 3000)
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
        Offline.tryAnswer(text, app.net.online, ::startTimer)?.let { local ->
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
        val minutesAgo = if (lastSessionEnd > 0) (System.currentTimeMillis() - lastSessionEnd) / 60_000 else -1
        val moment = Brain.Moment(offlineQueued, if (session.size <= 1) lastSession else emptyList(), minutesAgo)
        offlineQueued = false
        voice.begin(voiceListener)
        val myTurn = ++turn
        io.execute {
            val say = { s: String -> if (myTurn == turn) voice.say(s) }
            val splitter = SentenceSplitter { s -> say(s) }
            try {
                val full = brain.reply(history, moment) { chunk -> splitter.feed(chunk) }
                splitter.flush()
                main.post { session.add(Brain.Turn(false, full)); observers.forEach { it.onTranscript(false, full, false) } }
                if (app.budget.nearCap && !app.budget.blocked) say("Au fait, on approche de ton budget du mois.")
            } catch (e: Brain.OutOfBudget) {
                say("On a atteint le budget du mois, Rod. Je reprends le mois prochain, ou tu peux relever le plafond.")
            } catch (e: Exception) {
                Log.w(tag, "reply", e)
                splitter.flush()
                say(brain.explain(e))
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
        if (transcript.isNotEmpty()) { lastSession = transcript; lastSessionEnd = System.currentTimeMillis() }
        if (transcript.any { it.fromRod }) {
            // Mémorisée tout de suite si possible, sinon gardée et retentée plus tard
            io.execute { brain.rememberOrQueue(transcript) }
        }
        announceDue()
    }

    private fun startTimer(minutes: Int, label: String) {
        app.reminders.add(System.currentTimeMillis() + minutes * 60_000L, label, "minuteur")
    }

    /** Toutes les 15 secondes : les rappels et minuteurs arrivés à échéance. */
    private val reminderTick = object : Runnable {
        override fun run() {
            try {
                val due = app.reminders.takeDue()
                if (due.isNotEmpty()) { dueQueue.addAll(due); announceDue() }
            } catch (e: Exception) { Log.w(tag, "rappels", e) }
            main.postDelayed(this, 15_000)
        }
    }

    /** Dit les rappels en attente, seulement quand Hex ne parle pas et n'écoute pas. */
    private fun announceDue() {
        if (dueQueue.isEmpty() || state != State.IDLE) return
        val items = dueQueue.toList()
        dueQueue.clear()
        val now = System.currentTimeMillis()
        val phrases = items.map { r ->
            val late = now - r.at > 10 * 60_000
            when {
                r.isTimer -> "Rod, ton minuteur" + (if (r.text.isNotBlank()) " " + r.text else "") + " est terminé."
                late -> "Rod, un rappel que je n'ai pas pu te dire à l'heure : " + r.text + "."
                else -> "Rod, petit rappel : " + r.text + "."
            }
        }
        app.glyphs.set(Glyphs.Mode.ALERT)
        val beeps = if (items.any { it.isTimer }) 3 else 1
        repeat(beeps) { i -> main.postDelayed({ beep(long = true) }, i * 900L) }
        main.postDelayed({
            if (state != State.IDLE) { dueQueue.addAll(0, items); return@postDelayed }
            voice.begin(voiceListener)
            setState(State.THINKING)
            phrases.forEach { voice.say(it) }
            voice.finish()
            phrases.forEach { p -> observers.forEach { it.onTranscript(false, p, false) } }
        }, beeps * 900L + 300)
    }

    private fun beep(long: Boolean = false) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            tg.startTone(if (long) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK, if (long) 600 else 120)
            main.postDelayed({ tg.release() }, 800)
        } catch (_: Exception) {}
    }

    fun release() {
        main.removeCallbacks(reminderTick)
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

/** Ce que Hex sait faire sans Internet (et tout de suite, sans payer) : l'heure, la date, des minuteurs. */
object Offline {
    private val timerRe = Regex("""minuteur\s+(?:de\s+)?(\d+)\s*(min|minute|minutes|h|heure|heures)\b""", RegexOption.IGNORE_CASE)
    // Seulement la vraie question « quelle heure est-il », pas « à quelle heure est mon rendez-vous »
    private val hourRe = Regex("""^(?:hex[ ,]*)?(?:il est )?quelle heure(?: il est| est-il| est il)?\s*\??$""")
    private val dateRe = Regex("""^(?:hex[ ,]*)?(?:on est )?quel jour (?:on est|sommes-nous|est-on|on est aujourd'hui)\s*\??$""")

    fun tryAnswer(text: String, online: Boolean, startTimer: (Int, String) -> Unit): String? {
        val t = text.lowercase().trim().trimEnd('.', '!')
        timerRe.find(t)?.let { m ->
            // « annule le minuteur de 5 minutes » : on laisse Claude gérer
            if (online && Regex("""annul|arr[eê]t|stop|combien""").containsMatchIn(t)) return null
            val n = m.groupValues[1].toInt()
            val hours = m.groupValues[2].startsWith("h")
            val minutes = if (hours) n * 60 else n
            val label = "de $n ${if (hours) "heure" + (if (n > 1) "s" else "") else "minute" + (if (n > 1) "s" else "")}"
            startTimer(minutes, label)
            return "C'est parti, minuteur $label."
        }
        if (hourRe.matches(t) || (!online && t.contains("quelle heure"))) {
            val n = LocalTime.now()
            return if (n.minute == 0) "Il est ${n.hour} heures pile." else "Il est ${n.hour} heures ${n.minute}."
        }
        if (dateRe.matches(t) || (!online && t.contains("quel jour"))) {
            val d = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH))
            return "On est le $d."
        }
        return null
    }
}
