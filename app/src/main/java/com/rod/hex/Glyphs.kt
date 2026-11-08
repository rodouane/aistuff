package com.rod.hex

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Pilote les Glyph au dos du Nothing Phone (1) via le SDK Glyph de Nothing.
 * Le SDK est chargé par réflexion : si l'AAR est absent ou refuse, Hex marche sans lumière.
 *
 * Canaux du Phone (1) (doc Nothing) : A1=0, B1=1, C1..C4=2..5, E1=6, D1_1..D1_8=7..14.
 */
class Glyphs(private val context: Context) {
    enum class Mode { OFF, IDLE, LISTEN, THINK, SPEAK, OFFLINE, ALERT }

    private val tag = "HexGlyph"
    private val main = Handler(Looper.getMainLooper())
    private var mgr: Any? = null
    private var mgrCls: Class<*>? = null
    @Volatile private var ready = false
    private var mode = Mode.OFF
    private var tick = 0
    @Volatile var speechLevel = 0f
    var night = false

    fun start() {
        enableDebug()
        // Le mode test du SDK expire au bout de 48 h : on le relance toutes les 6 h
        main.postDelayed(object : Runnable {
            override fun run() { enableDebug(); main.postDelayed(this, 6 * 3600_000L) }
        }, 6 * 3600_000L)
        try {
            val cls = Class.forName("com.nothing.ketchum.GlyphManager")
            val cbCls = Class.forName("com.nothing.ketchum.GlyphManager\$Callback")
            val m = cls.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
            val cb = Proxy.newProxyInstance(cls.classLoader, arrayOf(cbCls)) { _, method, _ ->
                when (method.name) {
                    "onServiceConnected" -> onConnected()
                    "onServiceDisconnected" -> ready = false
                }
                null
            }
            cls.getMethod("init", cbCls).invoke(m, cb)
            mgr = m; mgrCls = cls
        } catch (e: Throwable) {
            Log.i(tag, "SDK Glyph indisponible : ${e.javaClass.simpleName}")
        }
        main.post(loop)
    }

    /** Nécessite : adb shell pm grant com.rod.hex android.permission.WRITE_SECURE_SETTINGS */
    private fun enableDebug() {
        try {
            Settings.Global.putInt(context.contentResolver, "nt_glyph_interface_debug_enable", 1)
        } catch (e: SecurityException) {
            Log.i(tag, "WRITE_SECURE_SETTINGS non accordée : mode test Glyph non relancé")
        }
    }

    private fun onConnected() {
        val cls = mgrCls ?: return
        try {
            val device = try {
                Class.forName("com.nothing.ketchum.Glyph").getField("DEVICE_20111").get(null) as String
            } catch (_: Throwable) { "20111" }
            cls.getMethod("register", String::class.java).invoke(mgr, device)
            cls.getMethod("openSession").invoke(mgr)
            ready = true
        } catch (e: Throwable) {
            Log.w(tag, "register/openSession", e)
        }
    }

    fun set(m: Mode) {
        if (m == mode) return
        mode = m
        tick = 0
        if (m == Mode.OFF || (night && m == Mode.IDLE)) off()
    }

    private fun frame(vararg channels: Int, period: Int = 0, cycles: Int = 0, interval: Int = 0): Any? {
        val cls = mgrCls ?: return null
        var b = cls.getMethod("getGlyphFrameBuilder").invoke(mgr) ?: return null
        val bc = b.javaClass
        for (c in channels) b = bc.getMethod("buildChannel", Int::class.javaPrimitiveType).invoke(b, c)!!
        if (period > 0) b = bc.getMethod("buildPeriod", Int::class.javaPrimitiveType).invoke(b, period)!!
        if (cycles > 0) b = bc.getMethod("buildCycles", Int::class.javaPrimitiveType).invoke(b, cycles)!!
        if (interval > 0) b = bc.getMethod("buildInterval", Int::class.javaPrimitiveType).invoke(b, interval)!!
        return bc.getMethod("build").invoke(b)
    }

    private fun call(name: String, f: Any?) {
        if (f == null) return
        try {
            mgrCls!!.methods.first { it.name == name && it.parameterTypes.size == 1 }.invoke(mgr, f)
        } catch (e: Throwable) { Log.d(tag, name, e) }
    }

    private fun off() {
        if (!ready) return
        try { mgrCls!!.getMethod("turnOff").invoke(mgr) } catch (_: Throwable) {}
    }

    private val loop = object : Runnable {
        override fun run() {
            if (ready) try { render() } catch (e: Throwable) { Log.d(tag, "render", e) }
            tick++
            main.postDelayed(this, 120)
        }
    }

    private val ring = intArrayOf(2, 3, 4, 5)

    private fun render() {
        when (mode) {
            Mode.OFF -> {}
            Mode.IDLE -> if (!night && tick == 0) call("toggle", frame(6))          // E1 discret
            Mode.LISTEN -> if (tick == 0) call("toggle", frame(*ring))               // anneau C plein
            Mode.THINK -> if (tick % 2 == 0) call("toggle", frame(ring[(tick / 2) % 4])) // un quart qui tourne
            Mode.SPEAK -> {
                // Barre D1 qui suit la voix (affichage de progression sur D1)
                val pct = (speechLevel * 100).toInt().coerceIn(0, 100)
                val f = frame(7, 8, 9, 10, 11, 12, 13, 14)
                if (f != null) try {
                    mgrCls!!.getMethod("displayProgress", f.javaClass, Int::class.javaPrimitiveType).invoke(mgr, f, pct)
                } catch (_: Throwable) { call("toggle", frame(*IntArray((pct / 12.5).toInt()) { 7 + it })) }
            }
            Mode.OFFLINE -> if (tick % 25 == 0) call("animate", frame(6, period = 1500, cycles = 1, interval = 10))
            Mode.ALERT -> if (tick % 8 == 0) call("animate", frame(0, 1, 2, 3, 4, 5, 6, period = 600, cycles = 1, interval = 10))
        }
    }
}
