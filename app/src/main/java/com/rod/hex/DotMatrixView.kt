package com.rod.hex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * L'écran de Hex : une matrice de 72 x 160 points, façon Nothing.
 * Chaque état a sa scène (repos, écoute, réflexion, parole, hors ligne).
 */
class DotMatrixView(context: Context) : View(context) {
    enum class Scene { IDLE, LISTEN, THINK, SPEAK, OFFLINE, SETUP }

    interface Info {
        fun weather(): Weather.Now?
        fun runningLeft(): Pair<Double, Double>?
        fun project(): Pair<String, String>?
        fun wakeLabel(): String
    }

    var scene = Scene.IDLE
    var info: Info? = null
    @Volatile var level = 0f
    var dim = false
    var setupMessage = "CONFIGURATION"

    private val cols = 72
    private val rows = 160
    private val grid = FloatArray(cols * rows)
    private val red = BooleanArray(cols * rows)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var smooth = 0f
    private val t0 = System.nanoTime()

    init { setBackgroundColor(Color.BLACK) }

    private fun clear() { grid.fill(0f); red.fill(false) }

    private fun set(x: Int, y: Int, v: Float, isRed: Boolean = false) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return
        val i = y * cols + x
        if (v > grid[i]) grid[i] = min(1f, v)
        if (isRed) red[i] = true
    }

    private fun set(x: Double, y: Double, v: Float, isRed: Boolean = false) = set(x.toInt(), y.toInt(), v, isRed)

    private fun text(s: String, x0: Int, y: Int, scale: Int = 1, v: Float = 1f) {
        var x = x0
        for (c in DotFont.normalize(s)) {
            val g = DotFont.glyph(c)
            for (r in 0 until 7) for (k in g[r].indices) if (g[r][k] == '1')
                for (a in 0 until scale) for (b in 0 until scale) set(x + k * scale + a, y + r * scale + b, v)
            x += (g[0].length + 1) * scale
        }
    }

    private fun center(s: String, scale: Int = 1) = ((cols - DotFont.width(s, scale)) / 2)

    /** Coupe un texte pour qu'il tienne sur la largeur (11 caractères environ). */
    private fun fit(s: String, maxCols: Int = cols - 20): String {
        var out = DotFont.normalize(s)
        while (out.isNotEmpty() && DotFont.width(out) > maxCols) out = out.dropLast(1)
        return out.trimEnd()
    }

    private fun icon(m: Array<String>, x: Int, y: Int, v: Float = 0.9f) {
        m.forEachIndexed { j, r -> r.forEachIndexed { i, c -> if (c == '1') set(x + i, y + j, v) } }
    }

    private val cloud = arrayOf("000111000", "001111100", "011111111", "111111111", "011111110")
    private val sun = arrayOf("010010010", "001111100", "111111111", "001111100", "010010010")
    private val rain = arrayOf("001111100", "011111111", "111111111", "010101010", "101010100")
    private val runner = arrayOf("0001100", "0001100", "0111000", "1011110", "0010000", "0101000", "1000100")

    // ---------------- scènes ----------------
    private fun sceneIdle(t: Double, offline: Boolean) {
        clear()
        val now = LocalDateTime.now()
        val clock = "%02d:%02d".format(now.hour, now.minute)
        text(clock, center(clock, 2), 24, 2, 1f)
        val days = arrayOf("LUN", "MAR", "MER", "JEU", "VEN", "SAM", "DIM")
        val months = arrayOf("JAN", "FEV", "MAR", "AVR", "MAI", "JUN", "JUL", "AOU", "SEP", "OCT", "NOV", "DEC")
        val date = "${days[now.dayOfWeek.value - 1]} %02d ${months[now.monthValue - 1]}".format(now.dayOfMonth)
        text(date, center(date), 44, 1, 0.55f)

        if (offline) {
            val s = "HORS LIGNE"; text(s, center(s), 76, 1, 0.9f)
            val s2 = "MINUTEURS OK"; text(s2, center(s2), 88, 1, 0.35f)
        } else {
            val i = info
            i?.weather()?.let { w ->
                icon(when (w.kind) { Weather.Kind.CLEAR -> sun; Weather.Kind.RAIN, Weather.Kind.STORM -> rain; else -> cloud }, 10, 72)
                text("${w.tempC}°", 22, 71, 1, 1f)
                text(fit(w.hint), 10, 82, 1, 0.45f)
            }
            i?.runningLeft()?.let { (left, pct) ->
                icon(runner, 10, 99)
                val km = if (left >= 10) "${left.roundToInt()} KM" else "%.1f KM".format(left).replace('.', ',')
                text(km, 21, 99, 1, 1f)
                val n = 52
                for (k in 0 until n) set(10 + k, 110, if (k < n * pct) 0.9f else 0.15f)
            }
            i?.project()?.let { (name, next) ->
                text("PROJET", 10, 124, 1, 0.45f)
                text(fit(name), 10, 133, 1, 0.95f)
                if (next.isNotBlank()) text(fit(next), 10, 142, 1, 0.45f)
            }
        }
        // le point qui respire : Hex est là
        val b = (0.25 + 0.55 * (0.5 + 0.5 * sin(t * 1.6))).toFloat()
        set(cols / 2, rows - 6, b)
    }

    private fun sceneListen(t: Double) {
        clear()
        val cx = cols / 2.0; val cy = rows / 2.0
        val r = 13 + 2 * sin(t * 3.2) + smooth * 6
        for (a in 0 until 64) { val th = a / 64.0 * 2 * PI; set(cx + r * cos(th), cy + r * sin(th), 0.85f) }
        val r2 = r + 5 + 2 * sin(t * 3.2 + 1)
        for (a in 0 until 80) { val th = a / 80.0 * 2 * PI; set(cx + r2 * cos(th), cy + r2 * sin(th), 0.25f) }
        for (i in -1..1) for (j in -1..1) set(cx.toInt() + i, cy.toInt() + j, 1f, true)
    }

    private fun sceneThink(t: Double) {
        clear()
        val cx = cols / 2.0; val cy = rows / 2.0
        for (k in 0 until 3) {
            val radius = 9.0 + k * 7
            val sp = (1.8 - k * 0.45) * (if (k % 2 == 1) -1 else 1)
            for (tail in 0 until 14) {
                val th = t * sp - tail * 0.09 * (if (sp < 0) -1 else 1) + k * 2.1
                set(cx + radius * cos(th), cy + radius * sin(th), 1f - tail / 14f)
            }
        }
    }

    /** La ligne de voix façon « Her », en points, qui ondule avec la voix de Hex. */
    private fun sceneSpeak(t: Double) {
        clear()
        val cy = rows / 2.0
        for (x in 0 until cols) {
            val u = x / (cols - 1.0)
            val win = sin(PI * u).pow(1.6)
            val y = cy + win * smooth * 24 * (sin(u * 9 + t * 5.2) * 0.6 + sin(u * 17 - t * 3.1) * 0.3 + sin(u * 4 + t * 1.7) * 0.4)
            for (dy in -5..5) {
                val yy = y.roundToInt() + dy
                val dist = abs(yy - y)
                set(x, yy, (max(0.0, 1 - dist / (1.1 + smooth * 1.6)) * (0.35 + 0.65 * win)).toFloat())
            }
        }
    }

    private fun sceneSetup() {
        clear()
        val s = "HEX"; text(s, center(s, 2), 50, 2, 1f)
        val m = fit(setupMessage, cols - 8); text(m, center(m), 80, 1, 0.6f)
    }

    // ---------------- rendu ----------------
    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - t0) / 1e9
        smooth += (level - smooth) * 0.25f
        when (scene) {
            Scene.IDLE -> sceneIdle(t, false)
            Scene.OFFLINE -> sceneIdle(t, true)
            Scene.LISTEN -> sceneListen(t)
            Scene.THINK -> sceneThink(t)
            Scene.SPEAK -> sceneSpeak(t)
            Scene.SETUP -> sceneSetup()
        }
        val cell = min(width / cols.toFloat(), height / rows.toFloat())
        val ox = (width - cell * cols) / 2f
        val oy = (height - cell * rows) / 2f
        val rad = cell * 0.36f
        val gain = if (dim) 0.35f else 1f
        for (y in 0 until rows) for (x in 0 until cols) {
            val v = grid[y * cols + x]
            val cx = ox + (x + 0.5f) * cell
            val cyy = oy + (y + 0.5f) * cell
            if (v < 0.02f) {
                if (dim) continue
                paint.color = Color.argb(12, 255, 255, 255)
                canvas.drawCircle(cx, cyy, rad * 0.6f, paint)
                continue
            }
            val a = ((0.12f + 0.88f * v) * gain * 255).toInt().coerceIn(0, 255)
            paint.color = if (red[y * cols + x]) Color.argb(a, 215, 25, 33) else Color.argb(a, 242, 242, 242)
            canvas.drawCircle(cx, cyy, rad * (0.7f + 0.3f * v), paint)
        }
        // repos : 10 images/s suffisent ; le reste à 30
        if (scene == Scene.IDLE || scene == Scene.OFFLINE || scene == Scene.SETUP) postInvalidateDelayed(100)
        else postInvalidateDelayed(33)
    }
}
