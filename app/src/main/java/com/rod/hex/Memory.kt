package com.rod.hex

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * La mémoire de Hex, stockée en JSON dans le stockage privé de l'app.
 *
 * Elle n'est jamais réécrite en entier par Claude : après chaque conversation, Claude propose
 * une liste d'opérations précises (ajouter un fait, mettre à jour un projet…) que le code
 * applique une par une. Rien ne peut donc disparaître par accident. En plus :
 * - une sauvegarde par jour est gardée 14 jours (restaurable depuis le menu caché) ;
 * - les conversations pas encore mémorisées (hors ligne, erreur) sont gardées et retentées ;
 * - le vieux journal est résumé par mois au lieu d'être effacé.
 *
 * Contenu : faits sur Rod (numérotés), ville, projets, journal, archive mensuelle, course à pied.
 */
class Memory(context: Context) {
    private val tag = "HexMemory"
    private val dir = context.filesDir
    private val file = File(dir, "memory.json")
    private val backups = File(dir, "memory-backups").apply { mkdirs() }
    private val pendingFile = File(dir, "memory-pending.json")
    private val lock = Any()
    // Jamais de plantage au démarrage à cause de la mémoire : au pire, on lit telle quelle
    private var data: JSONObject = (load(file) ?: blank()).let { raw -> try { migrate(raw) } catch (e: Exception) { Log.w(tag, "migration", e); raw } }

    private fun load(f: File): JSONObject? = try {
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        Log.w(tag, "lecture ${f.name}", e)
        // Fichier abîmé : on repart de la dernière sauvegarde plutôt que de tout perdre
        backups.listFiles()?.sortedByDescending { it.name }?.firstNotNullOfOrNull {
            try { JSONObject(it.readText()) } catch (_: Exception) { null }
        }
    }

    private fun blank() = JSONObject().apply {
        put("version", 2)
        put("onboarded", false)
        put("facts", JSONArray())
        put("next_fact_id", 1)
        put("city", "")
        put("projects", JSONArray())
        put("journal", JSONArray())
        put("archive", JSONArray())
        put("running", JSONObject().put("goal_km", 0.0).put("period", "semaine").put("log", JSONArray()))
        put("pending_questions", JSONArray())
    }

    /** Convertit l'ancienne mémoire (profil en un seul texte, km cumulés) sans rien perdre. */
    private fun migrate(m: JSONObject): JSONObject {
        if (m.optInt("version", 1) >= 2) return m
        val b = blank()
        for (k in m.keys()) if (!b.has(k) || k in listOf("onboarded", "city", "projects", "journal", "pending_questions")) b.put(k, m.get(k))
        // Profil -> faits, une phrase par fait
        var id = 1
        val facts = JSONArray()
        m.optString("profile").split(Regex("""(?<=[.!?])\s+|\n+""")).map { it.trim() }.filter { it.length > 2 }.forEach {
            facts.put(JSONObject().put("id", id++).put("text", it))
        }
        b.put("facts", facts).put("next_fact_id", id)
        b.remove("profile")
        // Course : le cumul devient une sortie dans le carnet
        val r = m.optJSONObject("running")
        if (r != null) {
            val log = JSONArray()
            val done = r.optDouble("done_km", 0.0)
            if (done > 0) log.put(JSONObject().put("date", r.optString("last_run").ifBlank { LocalDate.now().toString() }).put("km", done))
            b.put("running", JSONObject().put("goal_km", r.optDouble("goal_km", 0.0)).put("period", r.optString("period", "semaine")).put("log", log))
        }
        b.put("version", 2)
        return b
    }

    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(data.toString()) }

    val onboarded: Boolean get() = synchronized(lock) { data.optBoolean("onboarded", false) }
    val city: String get() = synchronized(lock) { data.optString("city", "") }

    // ---------- Écriture ----------

    private fun save() {
        try {
            backupOncePerDay()
            val tmp = File(dir, "memory.json.tmp")
            tmp.writeText(data.toString(2))
            if (!tmp.renameTo(file)) { file.writeText(data.toString(2)); tmp.delete() }
        } catch (e: Exception) { Log.w(tag, "écriture", e) }
    }

    /** Avant la première écriture du jour : copie de la mémoire d'hier. 14 jours gardés. */
    private fun backupOncePerDay() {
        if (!file.exists()) return
        val today = File(backups, "memory-${LocalDate.now()}.json")
        if (today.exists()) return
        try {
            file.copyTo(today, overwrite = true)
            backups.listFiles()?.sortedByDescending { it.name }?.drop(14)?.forEach { it.delete() }
        } catch (e: Exception) { Log.w(tag, "sauvegarde", e) }
    }

    /** Dates des sauvegardes disponibles, la plus récente d'abord. */
    fun backupDates(): List<String> =
        backups.listFiles()?.map { it.name.removePrefix("memory-").removeSuffix(".json") }?.sortedDescending() ?: emptyList()

    fun restore(date: String): Boolean = synchronized(lock) {
        val f = File(backups, "memory-$date.json")
        val m = load(f) ?: return false
        // La mémoire actuelle est gardée de côté : on peut annuler la restauration
        try { if (file.exists()) file.copyTo(File(backups, "memory-avant-restauration.json"), overwrite = true) } catch (_: Exception) {}
        data = migrate(m)
        save()
        true
    }

    fun reset() = synchronized(lock) { data = blank(); save() }

    fun addPendingQuestion(q: String) = synchronized(lock) {
        val arr = data.optJSONArray("pending_questions") ?: JSONArray().also { data.put("pending_questions", it) }
        arr.put(JSONObject().put("date", LocalDate.now().toString()).put("question", q))
        save()
    }

    /** Ajout immédiat d'un fait (outil « noter » pendant la conversation). */
    fun addFact(text: String): Int = synchronized(lock) {
        val id = addFactLocked(text)
        save(); id
    }

    private fun addFactLocked(text: String): Int {
        val facts = data.optJSONArray("facts") ?: JSONArray().also { data.put("facts", it) }
        val t = text.trim()
        for (i in 0 until facts.length()) {
            val f = facts.getJSONObject(i)
            if (f.optString("text").equals(t, ignoreCase = true)) return f.getInt("id")
        }
        val id = data.optInt("next_fact_id", facts.length() + 1)
        facts.put(JSONObject().put("id", id).put("text", t).put("date", LocalDate.now().toString()))
        data.put("next_fact_id", id + 1)
        return id
    }

    /** Une sortie running, notée tout de suite (outil « course »). */
    fun logRun(km: Double, date: String = LocalDate.now().toString()) = synchronized(lock) {
        val r = data.optJSONObject("running") ?: JSONObject().also { data.put("running", it) }
        val log = r.optJSONArray("log") ?: JSONArray().also { r.put("log", it) }
        log.put(JSONObject().put("date", date).put("km", km))
        // On garde un an de sorties
        val limit = LocalDate.now().minusDays(400).toString()
        val keep = JSONArray()
        for (i in 0 until log.length()) log.optJSONObject(i)?.let { if (it.optString("date") >= limit) keep.put(it) }
        r.put("log", keep)
        save()
    }

    /**
     * Applique les opérations proposées par Claude après une conversation.
     * Chaque opération est vérifiée ; une opération invalide est ignorée sans toucher au reste.
     */
    fun apply(ops: JSONArray, date: String = LocalDate.now().toString()): Int = synchronized(lock) {
        var applied = 0
        val today = date
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            try {
                when (o.optString("op")) {
                    "fait" -> o.optString("texte").takeIf { it.isNotBlank() }?.let { addFactLocked(it); applied++ }
                    "corriger" -> {
                        val f = findFact(o.optInt("id", -1)) ?: continue
                        val t = o.optString("texte").takeIf { it.isNotBlank() } ?: continue
                        f.put("text", t).put("date", today); applied++
                    }
                    "oublier" -> if (removeFact(o.optInt("id", -1))) applied++
                    "ville" -> o.optString("valeur").takeIf { it.isNotBlank() }?.let { data.put("city", it.trim()); applied++ }
                    "projet" -> if (upsertProject(o, today)) applied++
                    "projet_supprimer" -> if (removeProject(o.optString("nom"))) applied++
                    "journal" -> o.optString("resume").takeIf { it.isNotBlank() }?.let { addJournal(today, it); applied++ }
                    "archive" -> if (archiveMonth(o.optString("mois"), o.optString("resume"))) applied++
                    "objectif_course" -> {
                        val km = o.optDouble("km", -1.0)
                        if (km < 0) continue
                        val r = data.optJSONObject("running") ?: JSONObject().also { data.put("running", it) }
                        r.put("goal_km", km)
                        val p = o.optString("periode")
                        if (p == "semaine" || p == "mois") r.put("period", p)
                        if (!r.has("log")) r.put("log", JSONArray())
                        applied++
                    }
                    "decouverte_finie" -> { data.put("onboarded", true); applied++ }
                    "questions_traitees" -> { data.put("pending_questions", JSONArray()); applied++ }
                }
            } catch (e: Exception) {
                Log.w(tag, "opération ignorée : $o", e)
            }
        }
        compactJournal()
        if (applied > 0) save()
        applied
    }

    private fun findFact(id: Int): JSONObject? {
        val facts = data.optJSONArray("facts") ?: return null
        for (i in 0 until facts.length()) facts.optJSONObject(i)?.let { if (it.optInt("id") == id) return it }
        return null
    }

    private fun removeFact(id: Int): Boolean {
        val facts = data.optJSONArray("facts") ?: return false
        for (i in 0 until facts.length()) if (facts.optJSONObject(i)?.optInt("id") == id) { facts.remove(i); return true }
        return false
    }

    private fun projects(): JSONArray = data.optJSONArray("projects") ?: JSONArray().also { data.put("projects", it) }

    private fun findProject(name: String): JSONObject? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        val arr = projects()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("name").trim().lowercase() == key) return it }
        return null
    }

    private fun upsertProject(o: JSONObject, today: String): Boolean {
        val name = o.optString("nom").trim()
        if (name.isEmpty()) return false
        val p = findProject(name) ?: JSONObject().put("name", name).put("status", "en cours").put("next", "")
            .put("deadline", "").put("decisions", JSONArray()).put("notes", JSONArray()).put("created", today)
            .also { projects().put(it) }
        o.optString("statut").takeIf { it.isNotBlank() }?.let { p.put("status", it) }
        o.optString("prochaine_etape").takeIf { it.isNotBlank() }?.let { p.put("next", it) }
        if (o.has("echeance")) {
            val d = o.optString("echeance")
            if (d.isEmpty() || Regex("""\d{4}-\d{2}-\d{2}""").matches(d)) p.put("deadline", d)
        }
        o.optString("decision").takeIf { it.isNotBlank() }?.let { appendTo(p, "decisions", "$today : $it") }
        o.optString("note").takeIf { it.isNotBlank() }?.let { appendTo(p, "notes", it) }
        p.put("updated", today)
        return true
    }

    /** Les anciennes mémoires avaient parfois un texte au lieu d'une liste. */
    private fun appendTo(p: JSONObject, key: String, text: String) {
        val arr = when (val v = p.opt(key)) {
            is JSONArray -> v
            is String -> JSONArray().also { if (v.isNotBlank()) it.put(v) }
            else -> JSONArray()
        }
        arr.put(text)
        while (arr.length() > 30) arr.remove(0)
        p.put(key, arr)
    }

    private fun removeProject(name: String): Boolean {
        val key = name.trim().lowercase()
        val arr = projects()
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("name")?.trim()?.lowercase() == key) { arr.remove(i); return true }
        return false
    }

    private fun addJournal(date: String, summary: String) {
        val j = data.optJSONArray("journal") ?: JSONArray().also { data.put("journal", it) }
        val last = if (j.length() > 0) j.optJSONObject(j.length() - 1) else null
        if (last != null && last.optString("date") == date) last.put("summary", (last.optString("summary") + " " + summary.trim()).trim())
        else j.put(JSONObject().put("date", date).put("summary", summary.trim()))
    }

    /** Remplace les entrées d'un mois du journal par un résumé dans l'archive. */
    private fun archiveMonth(month: String, summary: String): Boolean {
        if (!Regex("""\d{4}-\d{2}""").matches(month) || summary.isBlank()) return false
        if (month >= LocalDate.now().toString().substring(0, 7)) return false // jamais le mois en cours
        val j = data.optJSONArray("journal") ?: return false
        val keep = JSONArray()
        for (i in 0 until j.length()) j.optJSONObject(i)?.let { if (!it.optString("date").startsWith(month)) keep.put(it) }
        data.put("journal", keep)
        val a = data.optJSONArray("archive") ?: JSONArray().also { data.put("archive", it) }
        a.put(JSONObject().put("month", month).put("summary", summary.trim()))
        return true
    }

    /** Filet de sécurité : au-delà de 75 entrées, le mois le plus ancien est archivé tel quel. */
    private fun compactJournal() {
        repeat(24) {
            val j = data.optJSONArray("journal") ?: return
            if (j.length() <= 75) return
            val month = j.optJSONObject(0)?.optString("date")?.take(7) ?: return
            val texts = mutableListOf<String>()
            for (i in 0 until j.length()) j.optJSONObject(i)?.let { if (it.optString("date").startsWith(month)) texts.add(it.optString("summary")) }
            val joined = texts.joinToString(" ").let { if (it.length > 1500) it.take(1500) + "…" else it }
            if (!archiveMonth(month, joined.ifBlank { "(rien de notable)" })) return
        }
    }

    /** Mois du journal assez anciens pour être résumés (plus de 45 jours). */
    fun monthsToArchive(): List<String> = synchronized(lock) {
        val j = data.optJSONArray("journal") ?: return emptyList()
        val limit = LocalDate.now().minusDays(45).toString().take(7)
        val months = sortedSetOf<String>()
        for (i in 0 until j.length()) j.optJSONObject(i)?.optString("date")?.take(7)?.let { if (it < limit) months.add(it) }
        months.toList()
    }

    // ---------- Conversations à mémoriser plus tard ----------

    fun queueTranscript(t: JSONArray) = synchronized(lock) {
        val arr = try { if (pendingFile.exists()) JSONArray(pendingFile.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("date", LocalDate.now().toString()).put("turns", t))
        while (arr.length() > 20) arr.remove(0)
        pendingFile.writeText(arr.toString())
    }

    /** Retire et renvoie les conversations en attente. */
    fun takeQueuedTranscripts(): JSONArray = synchronized(lock) {
        val arr = try { if (pendingFile.exists()) JSONArray(pendingFile.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
        pendingFile.delete()
        arr
    }

    val hasQueuedTranscripts: Boolean get() = pendingFile.exists()

    // ---------- Lecture pour l'écran et pour Claude ----------

    private fun periodStart(period: String, today: LocalDate = LocalDate.now()): LocalDate =
        if (period == "mois") today.withDayOfMonth(1) else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Km faits sur la période en cours (semaine du lundi, ou mois). */
    fun runningDone(): Double = synchronized(lock) {
        val r = data.optJSONObject("running") ?: return 0.0
        val start = periodStart(r.optString("period", "semaine")).toString()
        val log = r.optJSONArray("log") ?: return 0.0
        var sum = 0.0
        for (i in 0 until log.length()) log.optJSONObject(i)?.let { if (it.optString("date") >= start) sum += it.optDouble("km", 0.0) }
        sum
    }

    fun lastRun(): String? = synchronized(lock) {
        val log = data.optJSONObject("running")?.optJSONArray("log") ?: return null
        var best: String? = null
        for (i in 0 until log.length()) log.optJSONObject(i)?.optString("date")?.let { if (best == null || it > best!!) best = it }
        best
    }

    /** Km restants pour l'objectif running, ou null si pas d'objectif. */
    fun runningLeft(): Pair<Double, Double>? {
        val goal = synchronized(lock) { data.optJSONObject("running")?.optDouble("goal_km", 0.0) ?: 0.0 }
        if (goal <= 0) return null
        val done = runningDone()
        return Pair((goal - done).coerceAtLeast(0.0), (done / goal).coerceIn(0.0, 1.0))
    }

    private fun isClosed(p: JSONObject) =
        p.optString("status").lowercase().let { it.contains("fini") || it.contains("termin") || it.contains("pause") }

    /** Le projet à afficher au repos : celui dont l'échéance est la plus proche. */
    fun focusProject(): Pair<String, String>? = synchronized(lock) {
        val arr = data.optJSONArray("projects") ?: return null
        var best: JSONObject? = null
        var bestKey = "9999"
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (isClosed(p)) continue
            val key = p.optString("deadline").ifBlank { "9998" }
            if (best == null || key < bestKey) { best = p; bestKey = key }
        }
        best?.let { Pair(it.optString("name"), it.optString("next")) }
    }

    /**
     * La mémoire telle que Claude la lit : lisible, compacte, avec ce qui aide à piloter
     * les projets (échéance proche, projet sans nouvelles depuis longtemps).
     */
    fun forPrompt(): String = synchronized(lock) {
        val today = LocalDate.now()
        val sb = StringBuilder()
        sb.append("Découverte terminée : ").append(if (data.optBoolean("onboarded")) "oui" else "non").append('\n')
        sb.append("Ville : ").append(data.optString("city").ifBlank { "inconnue" }).append("\n\n")
        sb.append("Ce que tu sais sur Rod :\n")
        val facts = data.optJSONArray("facts") ?: JSONArray()
        if (facts.length() == 0) sb.append("(rien encore)\n")
        for (i in 0 until facts.length()) facts.optJSONObject(i)?.let { sb.append("[").append(it.optInt("id")).append("] ").append(it.optString("text")).append('\n') }

        sb.append("\nSes projets :\n")
        val arr = data.optJSONArray("projects") ?: JSONArray()
        if (arr.length() == 0) sb.append("(aucun)\n")
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            sb.append("- ").append(p.optString("name")).append(" (").append(p.optString("status")).append(")")
            val dl = p.optString("deadline")
            if (dl.isNotBlank()) {
                val days = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(dl)) }.getOrNull()
                sb.append(", échéance ").append(dl)
                if (days != null) sb.append(when {
                    days < 0 -> " (DÉPASSÉE de ${-days} j)"
                    days == 0L -> " (AUJOURD'HUI)"
                    days <= 7 -> " (dans $days j)"
                    else -> ""
                })
            }
            val upd = p.optString("updated").ifBlank { p.optString("created") }
            if (upd.isNotBlank() && !isClosed(p)) {
                val idle = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(upd), today) }.getOrNull()
                if (idle != null && idle >= 10) sb.append(", pas de nouvelles depuis $idle j")
            }
            sb.append('\n')
            p.optString("next").takeIf { it.isNotBlank() }?.let { sb.append("  prochaine étape : ").append(it).append('\n') }
            listOf("decisions" to "décisions", "notes" to "notes").forEach { (k, label) ->
                when (val v = p.opt(k)) {
                    is JSONArray -> if (v.length() > 0) {
                        sb.append("  ").append(label).append(" : ")
                        val from = (v.length() - 8).coerceAtLeast(0)
                        sb.append((from until v.length()).joinToString(" | ") { v.optString(it) }).append('\n')
                    }
                    is String -> if (v.isNotBlank()) sb.append("  ").append(label).append(" : ").append(v).append('\n')
                }
            }
        }

        val r = data.optJSONObject("running")
        val goal = r?.optDouble("goal_km", 0.0) ?: 0.0
        sb.append("\nCourse à pied : ")
        if (goal > 0) {
            val done = runningDone()
            sb.append("objectif ").append(fmt(goal)).append(" km par ").append(r!!.optString("period", "semaine"))
                .append(", fait ").append(fmt(done)).append(" km, reste ").append(fmt((goal - done).coerceAtLeast(0.0)))
                .append(" km. Dernière sortie : ").append(lastRun() ?: "aucune").append('\n')
        } else sb.append("pas d'objectif connu\n")

        val a = data.optJSONArray("archive") ?: JSONArray()
        if (a.length() > 0) {
            sb.append("\nLes mois passés :\n")
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { sb.append("- ").append(it.optString("month")).append(" : ").append(it.optString("summary")).append('\n') }
        }
        val j = data.optJSONArray("journal") ?: JSONArray()
        if (j.length() > 0) {
            sb.append("\nJournal récent :\n")
            for (i in 0 until j.length()) j.optJSONObject(i)?.let { sb.append("- ").append(it.optString("date")).append(" : ").append(it.optString("summary")).append('\n') }
        }
        val q = data.optJSONArray("pending_questions") ?: JSONArray()
        if (q.length() > 0) {
            sb.append("\nQuestions posées hors ligne, pas encore traitées :\n")
            for (i in 0 until q.length()) q.optJSONObject(i)?.let { sb.append("- ").append(it.optString("date")).append(" : ").append(it.optString("question")).append('\n') }
        }
        sb.toString()
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.FRENCH, "%.1f", d)
}
