package com.rod.hex

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalTime

/** L'écran unique de Hex. */
class MainActivity : Activity(), HexEngine.Observer {
    private lateinit var dots: DotMatrixView
    private lateinit var chat: LinearLayout
    private lateinit var chatLog: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private var engine: HexEngine? = null
    private val main = Handler(Looper.getMainLooper())
    private val app by lazy { HexApp.of(this) }
    private var cornerTaps = 0
    private var cornerFirst = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engine = (binder as HexService.LocalBinder).engine
            engine?.observe(this@MainActivity)
        }
        override fun onServiceDisconnected(name: ComponentName?) { engine = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        dots = DotMatrixView(this)
        dots.info = object : DotMatrixView.Info {
            override fun weather() = app.weather.now
            override fun runningLeft() = app.memory.runningLeft()
            override fun project() = app.memory.focusProject()
            override fun wakeLabel() = engine?.wake?.label ?: "HEY HEX"
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(dots, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildChat(), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Sans ça, Android croit que personne ne veut le geste et le transforme en appui long
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                engine?.trigger(); return true
            }
            override fun onLongPress(e: MotionEvent) { WifiPanel.show(this@MainActivity) }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -1500) { openChat(); return true }
                return false
            }
        })
        dots.setOnTouchListener { _, ev ->
            // Coin secret (haut gauche) : chaque appui compte, même rapide
            if (ev.x < dots.width * 0.22f && ev.y < dots.height * 0.12f) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) secretCorner()
                true
            } else gestures.onTouchEvent(ev)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        HexService.start(this)
        bindService(Intent(this, HexService::class.java), conn, Context.BIND_AUTO_CREATE)
        main.post(clockTick)
    }

    override fun onResume() {
        super.onResume()
        WifiPanel.closed(this)
        if (app.net.online) app.prefs.everOnline = true
        Kiosk.sync(this)
        if (!app.prefs.isConfigured) { dots.scene = DotMatrixView.Scene.SETUP; dots.setupMessage = "LANCE LE SCRIPT" }
        dots.invalidate()
    }

    override fun onDestroy() {
        engine?.unobserve(this)
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Nuit (23 h à 7 h) : écran très sombre, Glyph éteints au repos. Météo à rafraîchir. */
    private val clockTick = object : Runnable {
        override fun run() {
            val h = LocalTime.now().hour
            val night = h >= 23 || h < 7
            dots.dim = night
            app.glyphs.night = night
            val lp = window.attributes
            lp.screenBrightness = if (night) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            app.weather.refreshIfNeeded()
            if (app.net.online && !app.prefs.everOnline) { app.prefs.everOnline = true; Kiosk.sync(this@MainActivity) }
            main.postDelayed(this, 60_000)
        }
    }

    // ---------- Hex -> écran ----------
    override fun onState(state: HexEngine.State) {
        main.post {
            if (!app.prefs.isConfigured) return@post
            dots.scene = when (state) {
                HexEngine.State.IDLE -> if (app.net.online) DotMatrixView.Scene.IDLE else DotMatrixView.Scene.OFFLINE
                HexEngine.State.LISTENING -> DotMatrixView.Scene.LISTEN
                HexEngine.State.THINKING -> DotMatrixView.Scene.THINK
                HexEngine.State.SPEAKING -> DotMatrixView.Scene.SPEAK
            }
            dots.invalidate()
        }
    }

    override fun onLevel(level: Float) { dots.level = level }

    override fun onTranscript(fromRod: Boolean, text: String, partial: Boolean) {
        if (partial) return
        main.post {
            chatLog.append((if (fromRod) "\nROD  " else "\nHEX  ") + text + "\n")
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------- Bouton dédié : volume bas ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) engine?.trigger()
            return true
        }
        // Sortie de secours sans écran : 5 appuis sur volume haut en moins de 4 secondes
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) {
                val now = System.currentTimeMillis()
                if (now - volFirst > 4000) { volFirst = now; volPresses = 0 }
                if (++volPresses >= 5) { volPresses = 0; emergencyExit() }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (chat.visibility == View.VISIBLE) closeChat()
    }

    // ---------- Clavier et fil de conversation ----------
    private fun buildChat(): View {
        val mono = Typeface.MONOSPACE
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            setPadding(40, 80, 40, 40)
            visibility = View.GONE
        }
        chatLog = TextView(this).apply {
            setTextColor(Color.rgb(220, 220, 220)); typeface = mono; textSize = 14f
            setLineSpacing(0f, 1.25f)
        }
        chatScroll = ScrollView(this).apply { addView(chatLog) }
        chat.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); typeface = mono
            hint = "Écris à Hex…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "↑"; setOnClickListener { send() } })
        row.addView(Button(this).apply { text = "×"; setOnClickListener { closeChat() } })
        chat.addView(row)
        return chat
    }

    private fun openChat() {
        chat.visibility = View.VISIBLE
        input.requestFocus()
        getSystemService(InputMethodManager::class.java)!!.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeChat() {
        getSystemService(InputMethodManager::class.java)!!.hideSoftInputFromWindow(input.windowToken, 0)
        chat.visibility = View.GONE
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        engine?.handleTyped(t)
    }

    private var volFirst = 0L
    private var volPresses = 0

    /** Rend Android tout de suite, sans code : volume haut x5, ou l'option du menu. */
    private fun emergencyExit() {
        app.prefs.kioskOn = false
        Kiosk.release(this)
        Toast.makeText(this, "Mode kiosque coupé. Android est à toi.", Toast.LENGTH_LONG).show()
        try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    // ---------- Choix de la voix : on écoute, on garde ----------
    private fun chooseVoice() {
        val v = engine?.voice ?: return
        val voices = v.frenchVoices()
        if (voices.isEmpty()) {
            Toast.makeText(this, "Aucune voix française sur le téléphone.", Toast.LENGTH_LONG).show(); return
        }
        val labels = voices.mapIndexed { i, x ->
            "Voix ${i + 1}" + (if (x.locale.country != "FR") " (${x.locale.country})" else "") +
                (if (x.isNetworkConnectionRequired) " · Internet" else "")
        }.toTypedArray()
        var pick = voices.indexOfFirst { it.name == app.prefs.androidVoice }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Touche une voix pour l'écouter")
            .setSingleChoiceItems(labels, pick) { _, i -> pick = i; v.preview(voices[i].name, app.prefs.voicePitch) }
            .setPositiveButton("Garder") { _, _ -> app.prefs.androidVoice = voices[pick].name; chooseTone(voices[pick].name) }
            .setNegativeButton("Annuler") { _, _ -> v.applyChoice() }
            .show()
    }

    private fun chooseTone(name: String) {
        val v = engine?.voice ?: return
        val tones = floatArrayOf(0.75f, 0.88f, 1f, 1.12f)
        val labels = arrayOf("Très grave", "Grave", "Normal", "Plus aigu")
        var pick = tones.indexOfFirst { kotlin.math.abs(it - app.prefs.voicePitch) < 0.01f }.let { if (it < 0) 2 else it }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Le ton")
            .setSingleChoiceItems(labels, pick) { _, i -> pick = i; v.preview(name, tones[i]) }
            .setPositiveButton("Garder") { _, _ -> app.prefs.voicePitch = tones[pick]; v.applyChoice() }
            .setNegativeButton("Annuler") { _, _ -> v.applyChoice() }
            .show()
    }

    // ---------- Sortie cachée : 5 tapes dans le coin haut gauche, puis le code ----------
    private fun secretCorner() {
        val now = System.currentTimeMillis()
        if (now - cornerFirst > 4000) { cornerFirst = now; cornerTaps = 0 }
        cornerTaps++
        if (cornerTaps < 5) return
        cornerTaps = 0
        val pin = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Code").setView(pin)
            .setPositiveButton("OK") { _, _ -> if (pin.text.toString() == app.prefs.pin) adminMenu() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun adminMenu() {
        val items = arrayOf("Ajouter un Wi-Fi", "Ouvrir le clavier", "Refaire la découverte (efface la mémoire)",
            "Quitter le mode kiosque", "Revenir en mode kiosque", "Rendre le téléphone normal (retirer Hex)", "Choisir la voix",
            "Rappels en cours", "Restaurer la mémoire d'un jour précédent",
            if (app.prefs.webSearch) "Recherche web : activée (toucher pour couper)" else "Recherche web : coupée (toucher pour activer)")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Hex · réglages  (dépense du mois : %.2f €, %d recherches)".format(app.budget.spentEur, app.budget.searchesThisMonth))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> WifiPanel.show(this)
                    1 -> openChat()
                    2 -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("Effacer toute la mémoire de Hex ?")
                        .setPositiveButton("Effacer") { _, _ -> app.memory.reset() }
                        .setNegativeButton("Annuler", null).show()
                    3 -> emergencyExit()
                    4 -> { app.prefs.kioskOn = true; Kiosk.sync(this) }
                    6 -> chooseVoice()
                    7 -> showReminders()
                    8 -> restoreMemory()
                    9 -> { app.prefs.webSearch = !app.prefs.webSearch
                        Toast.makeText(this, if (app.prefs.webSearch) "Recherche web activée" else "Recherche web coupée", Toast.LENGTH_SHORT).show() }
                    5 -> { app.prefs.kioskOn = false; Kiosk.removeOwner(this)
                        Toast.makeText(this, "Hex n'est plus propriétaire. Tu peux le désinstaller.", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
                }
            }.show()
    }

    // ---------- Rappels et sauvegardes de la mémoire ----------
    private fun showReminders() {
        val list = app.reminders.upcoming()
        if (list.isEmpty()) { Toast.makeText(this, "Aucun rappel en cours.", Toast.LENGTH_LONG).show(); return }
        val labels = list.map { (if (it.isTimer) "Minuteur " + it.text else it.text) + "\n" + Reminders.describe(it.at) }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Toucher un rappel pour l'annuler")
            .setItems(labels) { _, i -> app.reminders.cancelId(list[i].id)
                Toast.makeText(this, "Rappel annulé.", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Fermer", null).show()
    }

    private fun restoreMemory() {
        val dates = app.memory.backupDates()
        if (dates.isEmpty()) { Toast.makeText(this, "Pas encore de sauvegarde.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Revenir à la mémoire du…")
            .setItems(dates.toTypedArray()) { _, i ->
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Remplacer la mémoire actuelle par celle du ${dates[i]} ? (La mémoire d'aujourd'hui reste sauvegardée.)")
                    .setPositiveButton("Restaurer") { _, _ ->
                        val ok = app.memory.restore(dates[i])
                        Toast.makeText(this, if (ok) "Mémoire restaurée." else "Échec de la restauration.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Annuler", null).show()
            }
            .setNegativeButton("Fermer", null).show()
    }
}
