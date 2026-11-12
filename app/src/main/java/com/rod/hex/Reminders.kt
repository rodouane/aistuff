package com.rod.hex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Rappels et minuteurs de Hex. Enregistrés sur le téléphone : ils survivent à un redémarrage,
 * et ceux qui tombent pendant que Hex est éteint sont annoncés au retour.
 */
class Reminders(context: Context) {
    data class Item(val id: Long, val at: Long, val text: String, val kind: String) {
        val isTimer get() = kind == "minuteur"
    }

    private val file = File(context.filesDir, "reminders.json")
    private val lock = Any()
    private val items = mutableListOf<Item>()

    init {
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(Item(o.getLong("id"), o.getLong("at"), o.getString("text"), o.optString("kind", "rappel")))
                }
            }
        } catch (_: Exception) {}
    }

    private fun save() = try { write() } catch (_: Exception) {}

    private fun write() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("kind", it.kind)) }
        val tmp = File(file.parentFile, "reminders.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) file.writeText(arr.toString())
    }

    fun add(at: Long, text: String, kind: String = "rappel"): Item = synchronized(lock) {
        val item = Item(System.currentTimeMillis() + items.size, at, text.trim(), kind)
        items.add(item); items.sortBy { it.at }; save(); item
    }

    /** Annule les rappels dont le texte contient [query] (ou le prochain si vide). Renvoie ceux annulés. */
    fun cancel(query: String): List<Item> = synchronized(lock) {
        val q = query.trim().lowercase()
        val hit = if (q.isEmpty()) items.take(1) else items.filter { it.text.lowercase().contains(q) || (it.text.isNotBlank() && q.contains(it.text.lowercase())) }
        items.removeAll(hit.toSet()); if (hit.isNotEmpty()) save(); hit
    }

    fun cancelId(id: Long) = synchronized(lock) { if (items.removeAll { it.id == id }) save() }

    /** Retire et renvoie les rappels arrivés à échéance. */
    fun takeDue(now: Long = System.currentTimeMillis()): List<Item> = synchronized(lock) {
        val due = items.filter { it.at <= now }
        if (due.isNotEmpty()) { items.removeAll(due.toSet()); save() }
        due
    }

    fun upcoming(): List<Item> = synchronized(lock) { items.toList() }

    fun next(): Item? = synchronized(lock) { items.firstOrNull() }

    companion object {
        private val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH)
        fun describe(at: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).format(fmt)
        fun epoch(ldt: LocalDateTime): Long = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
