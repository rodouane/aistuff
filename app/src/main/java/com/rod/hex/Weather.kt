package com.rod.hex

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.util.concurrent.Executors

/** Météo du lieu de Rod via Open-Meteo (gratuit, sans clé). Mise à jour toutes les 30 min. */
class Weather(private val context: Context) {
    data class Now(val tempC: Int, val kind: Kind, val hint: String)
    enum class Kind { CLEAR, CLOUD, RAIN, SNOW, STORM, FOG }

    @Volatile var now: Now? = null
        private set
    private var lastFetch = 0L
    private var coords: Pair<Double, Double>? = null
    private var coordsCity = ""
    private val io = Executors.newSingleThreadExecutor()

    fun refreshIfNeeded() {
        if (System.currentTimeMillis() - lastFetch < 30 * 60_000) return
        lastFetch = System.currentTimeMillis()
        io.execute { runCatching { fetch() } }
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun fetch() {
        val city = HexApp.of(context).memory.city
        if (city.isBlank()) return
        if (coords == null || coordsCity != city) {
            val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" + URLEncoder.encode(city, "UTF-8"))
            val r = g.optJSONArray("results")?.optJSONObject(0) ?: return
            coords = Pair(r.getDouble("latitude"), r.getDouble("longitude"))
            coordsCity = city
        }
        val (lat, lon) = coords!!
        val w = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&hourly=precipitation_probability&forecast_days=2&timezone=auto"
        )
        val cur = w.getJSONObject("current")
        val code = cur.getInt("weather_code")
        val temp = Math.round(cur.getDouble("temperature_2m")).toInt()
        // Prochaine heure de pluie probable dans les 12 h
        var hint = describe(code)
        val hourly = w.optJSONObject("hourly")
        if (hourly != null) {
            val times = hourly.getJSONArray("time")
            val probs = hourly.getJSONArray("precipitation_probability")
            val nowT = LocalDateTime.now()
            for (i in 0 until times.length()) {
                val t = LocalDateTime.parse(times.getString(i))
                if (t.isBefore(nowT) || t.isAfter(nowT.plusHours(12))) continue
                if (probs.optInt(i, 0) >= 50) { hint = "PLUIE ${t.hour}H"; break }
            }
        }
        now = Now(temp, kind(code), hint)
    }

    private fun kind(code: Int) = when (code) {
        0 -> Kind.CLEAR
        in 1..3 -> Kind.CLOUD
        45, 48 -> Kind.FOG
        in 51..67, in 80..82 -> Kind.RAIN
        in 71..77, 85, 86 -> Kind.SNOW
        in 95..99 -> Kind.STORM
        else -> Kind.CLOUD
    }

    private fun describe(code: Int) = when (kind(code)) {
        Kind.CLEAR -> "CIEL CLAIR"
        Kind.CLOUD -> "NUAGEUX"
        Kind.FOG -> "BROUILLARD"
        Kind.RAIN -> "PLUIE"
        Kind.SNOW -> "NEIGE"
        Kind.STORM -> "ORAGE"
    }
}
