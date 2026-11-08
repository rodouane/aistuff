package com.rod.hex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * La mémoire de Hex : trois carnets (profil, projets, journal) + le suivi running.
 * Stockée en JSON dans le stockage privé de l'app (chiffré par Android) et incluse
 * dans la sauvegarde Google automatique d'Android.
 */
class Memory(context: Context) {
    private val file = File(context.filesDir, "memory.json")
    private val lock = Any()
    private var data: JSONObject = load()

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else blank()
    } catch (_: Exception) {
        blank()
    }

    private fun blank() = JSONObject().apply {
        put("onboarded", false)
        put("profile", "")
        put("city", "")
        put("projects", JSONArray())
        put("journal", JSONArray())
        put("running", JSONObject().apply {
            put("goal_km", 0.0); put("period", "semaine"); put("done_km", 0.0); put("period_start", "")
        })
        put("pending_questions", JSONArray())
    }

    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(data.toString()) }

    val onboarded: Boolean get() = synchronized(lock) { data.optBoolean("onboarded", false) }
    val city: String get() = synchronized(lock) { data.optString("city", "") }

    /** Remplace la mémoire par la version mise à jour par Claude, en gardant les champs manquants. */
    fun replaceWith(updated: JSONObject) = synchronized(lock) {
        val merged = JSONObject(data.toString())
        for (k in updated.keys()) merged.put(k, updated.get(k))
        // Le journal ne garde que les 60 derniers jours
        val j = merged.optJSONArray("journal") ?: JSONArray()
        if (j.length() > 60) {
            val keep = JSONArray()
            for (i in j.length() - 60 until j.length()) keep.put(j.get(i))
            merged.put("journal", keep)
        }
        data = merged
        save()
    }

    fun addPendingQuestion(q: String) = synchronized(lock) {
        val arr = data.optJSONArray("pending_questions") ?: JSONArray().also { data.put("pending_questions", it) }
        arr.put(JSONObject().put("date", LocalDate.now().toString()).put("question", q))
        save()
    }

    fun reset() = synchronized(lock) { data = blank(); save() }

    private fun save() {
        val tmp = File(file.parentFile, "memory.json.tmp")
        tmp.writeText(data.toString(2))
        tmp.renameTo(file)
    }

    /** Km restants pour l'objectif running, ou null si pas d'objectif. */
    fun runningLeft(): Pair<Double, Double>? = synchronized(lock) {
        val r = data.optJSONObject("running") ?: return null
        val goal = r.optDouble("goal_km", 0.0)
        if (goal <= 0) return null
        val done = r.optDouble("done_km", 0.0)
        Pair((goal - done).coerceAtLeast(0.0), (done / goal).coerceIn(0.0, 1.0))
    }

    /** Le projet à afficher au repos : celui dont l'échéance est la plus proche. */
    fun focusProject(): Pair<String, String>? = synchronized(lock) {
        val arr = data.optJSONArray("projects") ?: return null
        var best: JSONObject? = null
        var bestKey = "9999"
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("status").lowercase().let { it.contains("fini") || it.contains("termin") || it.contains("pause") }) continue
            val key = p.optString("deadline").ifBlank { "9998" }
            if (best == null || key < bestKey) { best = p; bestKey = key }
        }
        best?.let { Pair(it.optString("name"), it.optString("next")) }
    }
}
