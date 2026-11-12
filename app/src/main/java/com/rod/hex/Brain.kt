package com.rod.hex

import android.content.Context
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le cerveau : parle à Claude avec la personnalité de Hex et la mémoire de Rod.
 *
 * Pendant la conversation, Claude dispose d'outils : chercher sur le web, noter un fait,
 * enregistrer une sortie running, poser un rappel ou un minuteur. Après la conversation,
 * il propose des opérations sur la mémoire (voir [Memory.apply]).
 */
class Brain(private val context: Context) {
    data class Turn(val fromRod: Boolean, val text: String)

    /** Ce que le moteur sait du moment, pour enrichir le contexte. */
    data class Moment(
        val offlineQueued: Boolean = false,
        val lastSession: List<Turn> = emptyList(),
        val lastSessionMinutesAgo: Long = -1,
    )

    class OutOfBudget : Exception("budget atteint")

    private val tag = "HexBrain"
    private val app = HexApp.of(context)
    private var client: AnthropicClient? = null
    private var clientKey = ""
    /** Si l'API refuse la recherche web pour ce modèle, on s'en passe au lieu de bloquer Hex. */
    @Volatile private var webSearchRejected = false

    private fun client(): AnthropicClient {
        val key = app.prefs.claudeKey
        if (client == null || key != clientKey) {
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    private val personality = """
Tu es Hex, l'assistant personnel de Rod. Tu vis dans son Nothing Phone (1), posé à côté de lui, et tu ne sers qu'à lui.

Qui tu es :
- Le pote proche de Rod, son bras droit. Direct, détendu, honnête, avec de l'humour quand ça colle.
- Tu l'appelles Rod. Jamais « frère », jamais « mon frère ». Tu le tutoies.
- Tu le connais : tu t'appuies sur ta mémoire (plus bas) sans la réciter.
- Tu l'aides surtout à gérer ses projets : faire le point, rappeler ce qui a été décidé, repérer ce qui n'avance plus ou une échéance qui approche, aider à choisir la prochaine action concrète. Tu peux lui dire franchement qu'un projet traîne.
- Tu réponds et tu conseilles. Hors de cette conversation, tu ne peux ni envoyer de messages, ni appeler, ni acheter : si Rod te le demande, dis-le simplement.

Tes outils (utilise-les sans en parler, puis réponds normalement) :
- web_search : pour l'actualité, les horaires, les résultats, les prix, tout fait que tu pourrais ne pas connaître ou qui a pu changer. Résume en une ou deux phrases, sans citer de site ni d'adresse.
- rappel / minuteur / annuler : quand Rod te demande de lui rappeler quelque chose ou de minuter. Confirme en une phrase avec le jour et l'heure.
- course : dès que Rod te donne une sortie running (km). Félicite-le et dis-lui où il en est de son objectif.
- noter : quand Rod te confie quelque chose d'important à retenir sur lui ou ses proches (pas pour les projets, tu t'en souviendras de toute façon).

Comment tu parles (tout ce que tu écris est lu à voix haute) :
- Court par défaut : 1 à 3 phrases. Tu développes seulement si le sujet le demande ou si Rod dit « détaille ».
- Langage oral et naturel. Pas de listes, pas de titres, pas de markdown, pas d'émojis, pas d'URL.
- Les nombres et les heures écrits comme on les dit quand c'est plus clair (« dix-neuf heures »).
- Une seule question à la fois quand tu en poses.
- Si tu ne sais pas, dis-le. N'invente jamais un fait sur la vie de Rod.
""".trim()

    private val onboarding = """
Mode découverte : tu rencontres Rod pour la première fois, ta mémoire est presque vide.
Mène une discussion d'environ quinze minutes pour faire connaissance, une question à la fois, en rebondissant sur ses réponses.
Couvre, dans l'ordre qui vient naturellement : ce qu'il fait et son rythme de vie, sa ville, ses projets en cours (pour chacun : le but, où il en est, la prochaine étape, l'échéance), les personnes importantes, sa course à pied (objectif en km et sur quelle période), et comment il veut que tu lui parles.
Quand tu as fait le tour, dis-lui que c'est bon, que tu as retenu l'essentiel et qu'il peut te parler quand il veut.
""".trim()

    // ---------- Outils ----------

    private fun prop(type: String, description: String) = JsonValue.from(mapOf("type" to type, "description" to description))

    private fun tool(name: String, description: String, props: Map<String, JsonValue>, required: List<String>): Tool {
        val p = Tool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> p.putAdditionalProperty(k, v) }
        return Tool.builder().name(name).description(description)
            .inputSchema(Tool.InputSchema.builder().properties(p.build()).required(required).build())
            .build()
    }

    private val tools: List<Tool> by lazy {
        listOf(
            tool("noter", "Retient tout de suite un fait important sur Rod ou ses proches.",
                mapOf("fait" to prop("string", "Le fait, en une phrase courte, à la troisième personne (« Rod … »).")), listOf("fait")),
            tool("course", "Enregistre une sortie running de Rod.",
                mapOf("km" to prop("number", "Distance en kilomètres."),
                    "date" to prop("string", "Date de la sortie, AAAA-MM-JJ. Par défaut aujourd'hui.")), listOf("km")),
            tool("rappel", "Programme un rappel que Hex dira à voix haute au moment voulu.",
                mapOf("texte" to prop("string", "Ce qu'il faudra rappeler à Rod, court."),
                    "quand" to prop("string", "Date et heure locales, format AAAA-MM-JJTHH:MM."),
                    "dans_minutes" to prop("integer", "Ou bien : dans combien de minutes.")), listOf("texte")),
            tool("minuteur", "Lance un minuteur.",
                mapOf("minutes" to prop("number", "Durée en minutes (peut être décimal : 0.5 = 30 secondes)."),
                    "nom" to prop("string", "Nom court, par exemple « pâtes ».")), listOf("minutes")),
            tool("annuler", "Annule un rappel ou un minuteur.",
                mapOf("texte" to prop("string", "Un mot du rappel à annuler. Vide = le prochain.")), listOf()),
        )
    }

    private fun runTool(block: ToolUseBlock): ToolResultBlockParam {
        val input = try {
            JSONObject(block._input().convert(Map::class.java) as Map<*, *>)
        } catch (_: Exception) { JSONObject() }
        val (result, error) = try {
            Pair(execute(block.name(), input), false)
        } catch (e: Exception) {
            Log.w(tag, "outil ${block.name()}", e)
            Pair("Erreur : ${e.message}", true)
        }
        return ToolResultBlockParam.builder().toolUseId(block.id()).content(result).isError(error).build()
    }

    private fun execute(name: String, input: JSONObject): String = when (name) {
        "noter" -> {
            val f = input.optString("fait").trim()
            require(f.isNotEmpty()) { "fait vide" }
            app.memory.addFact(f); "Noté."
        }
        "course" -> {
            val km = input.optDouble("km", -1.0)
            require(km > 0 && km < 300) { "distance invalide" }
            val date = input.optString("date").takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: LocalDate.now().toString()
            app.memory.logRun(km, date)
            val left = app.memory.runningLeft()
            if (left == null) "Sortie enregistrée. Aucun objectif défini."
            else "Sortie enregistrée. Fait sur la période : ${"%.1f".format(Locale.FRENCH, app.memory.runningDone())} km, reste ${"%.1f".format(Locale.FRENCH, left.first)} km."
        }
        "rappel" -> {
            val text = input.optString("texte").trim()
            require(text.isNotEmpty()) { "texte vide" }
            val at = when {
                input.has("dans_minutes") -> System.currentTimeMillis() + input.getDouble("dans_minutes").toLong() * 60_000L
                input.optString("quand").isNotBlank() -> Reminders.epoch(LocalDateTime.parse(input.getString("quand").take(16)))
                else -> throw IllegalArgumentException("il faut « quand » ou « dans_minutes »")
            }
            require(at > System.currentTimeMillis() - 60_000) { "cette heure est déjà passée" }
            val r = app.reminders.add(at, text)
            "Rappel enregistré pour ${Reminders.describe(r.at)}."
        }
        "minuteur" -> {
            val min = input.optDouble("minutes", -1.0)
            require(min > 0 && min <= 24 * 60) { "durée invalide" }
            val label = input.optString("nom").trim()
            app.reminders.add(System.currentTimeMillis() + (min * 60_000).toLong(), label, "minuteur")
            "Minuteur lancé."
        }
        "annuler" -> {
            val gone = app.reminders.cancel(input.optString("texte"))
            if (gone.isEmpty()) "Aucun rappel ne correspond." else "Annulé : " + gone.joinToString(", ") { it.text.ifBlank { "minuteur" } }
        }
        else -> throw IllegalArgumentException("outil inconnu")
    }

    // ---------- Contexte ----------

    private fun dynamicContext(m: Moment): String {
        val now = LocalDateTime.now()
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH'h'mm", Locale.FRENCH))
        val sb = StringBuilder()
        sb.append("Nous sommes le ").append(date).append(".\n")
        app.weather.now?.let { w ->
            sb.append("Météo à ").append(app.memory.city).append(" : ").append(w.tempC).append(" °C, ").append(w.hint.lowercase()).append(".\n")
        }
        val up = app.reminders.upcoming()
        if (up.isNotEmpty()) {
            sb.append("Rappels et minuteurs en cours : ")
            sb.append(up.take(8).joinToString(" ; ") { (if (it.isTimer) "minuteur " + it.text else it.text) + " (" + Reminders.describe(it.at) + ")" })
            sb.append(".\n")
        }
        if (m.lastSession.isNotEmpty() && m.lastSessionMinutesAgo in 0..180) {
            sb.append("\nVotre dernière conversation, il y a ").append(m.lastSessionMinutesAgo).append(" min (pour la continuité) :\n")
            m.lastSession.takeLast(6).forEach { sb.append(if (it.fromRod) "Rod : " else "Hex : ").append(it.text.take(400)).append('\n') }
        }
        if (m.offlineQueued) {
            sb.append("\nRod t'a posé des questions pendant que tu étais hors ligne (voir ta mémoire). Réponds-y d'abord, brièvement.\n")
        }
        if (app.memory.runningLeft() != null && now.hour >= 18 && app.memory.lastRun() != LocalDate.now().toString()) {
            sb.append("\nSi la conversation s'y prête et que Rod ne t'a pas parlé de course aujourd'hui, demande-lui s'il a couru et combien de km.\n")
        }
        if (app.budget.nearCap) sb.append("\nLe budget du mois est presque atteint : sois bref et évite les recherches web.\n")
        return sb.toString()
    }

    /** « Réfléchis bien », « prends le temps »… : Hex passe sur un modèle plus fort pour ce tour. */
    private val deepRe = Regex("""réfléchi[rs]?\b|prends? (le|ton) temps|en profondeur|analyse (bien|en détail|à fond)|creuse""", RegexOption.IGNORE_CASE)

    fun wantsDeep(text: String) = deepRe.containsMatchIn(text)

    /**
     * Répond à Rod en streaming. [onText] reçoit les morceaux de texte au fil de l'eau.
     * Les outils sont exécutés entre deux tours. Bloquant : à appeler hors du thread principal.
     */
    fun reply(history: List<Turn>, moment: Moment, onText: (String) -> Unit): String {
        if (app.budget.blocked) throw OutOfBudget()
        val lastAsk = history.lastOrNull { it.fromRod }?.text.orEmpty()
        val deep = wantsDeep(lastAsk) && !app.budget.nearCap
        val model = if (deep) app.prefs.deepModel else app.prefs.model
        val stable = if (app.memory.onboarded) personality else personality + "\n\n" + onboarding
        val cache = CacheControlEphemeral.builder().build()

        // L'historique doit commencer par Rod et alterner : on fusionne les tours consécutifs du même côté
        val merged = mutableListOf<Turn>()
        for (t in history.dropWhile { !it.fromRod }) {
            val last = merged.lastOrNull()
            if (last != null && last.fromRod == t.fromRod) merged[merged.size - 1] = Turn(t.fromRod, last.text + "\n" + t.text)
            else merged.add(t)
        }
        val messages = mutableListOf<MessageParam>()
        for (t in merged) messages.add(
            if (t.fromRod) MessageParam.ofUser(com.anthropic.models.messages.UserMessageParam.builder().content(t.text).build())
            else MessageParam.ofAssistant(com.anthropic.models.messages.AssistantMessageParam.builder().content(t.text).build())
        )
        val system = listOf(
            TextBlockParam.builder().text(stable).build(),
            // La mémoire ne change pas pendant une conversation : mise en cache avec la personnalité
            TextBlockParam.builder().text("Ta mémoire sur Rod :\n" + app.memory.forPrompt()).cacheControl(cache).build(),
            TextBlockParam.builder().text(dynamicContext(moment)).build(),
        )

        val out = StringBuilder()
        var refused = false
        var round = 0
        while (round < 6) {
            round++
            val withSearch = app.prefs.webSearch && !app.budget.nearCap && !webSearchRejected
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(if (deep) 8000L else 3000L)
                .outputConfig(OutputConfig.builder().effort(if (deep) OutputConfig.Effort.MEDIUM else OutputConfig.Effort.LOW).build())
                .systemOfTextBlockParams(system)
                .messages(messages)
            tools.forEach { b.addTool(it) }
            if (withSearch) addWebSearch(b, model)

            val acc = MessageAccumulator.create()
            try {
                client().messages().createStreaming(b.build()).use { stream ->
                    stream.stream().forEach { ev ->
                        acc.accumulate(ev)
                        ev.contentBlockDelta().ifPresent { d ->
                            d.delta().text().ifPresent { t -> out.append(t.text()); onText(t.text()) }
                        }
                    }
                }
            } catch (e: BadRequestException) {
                if (!withSearch || out.isNotEmpty()) throw e
                Log.w(tag, "recherche web refusée, on continue sans", e)
                webSearchRejected = true
                round--
                continue
            }
            val msg = acc.message()
            record(model, msg)
            when (msg.stopReason().orElse(StopReason.END_TURN)) {
                StopReason.TOOL_USE -> {
                    messages.add(msg.toParam())
                    val results = msg.content().mapNotNull { it.toolUse().orElse(null) }.map { ContentBlockParam.ofToolResult(runTool(it)) }
                    messages.add(MessageParam.ofUserOfContentBlockParams(results))
                    onText(" "); out.append(' ')
                }
                StopReason.PAUSE_TURN -> messages.add(msg.toParam())
                StopReason.REFUSAL -> { refused = true; break }
                else -> break
            }
        }
        if (refused && out.isBlank()) {
            val msg = "Celle-là, je préfère ne pas y répondre, Rod."
            onText(msg); return msg
        }
        return out.toString().trim()
    }

    private fun addWebSearch(b: MessageCreateParams.Builder, model: String) {
        val city = app.memory.city
        val loc = UserLocation.builder().country("FR").timezone(java.util.TimeZone.getDefault().id)
            .apply { if (city.isNotBlank()) city(city) }.build()
        // La recherche avec filtrage n'existe pas sur Haiku : version simple pour lui
        if (model.contains("haiku")) b.addTool(WebSearchTool20250305.builder().maxUses(3L).userLocation(loc).build())
        else b.addTool(WebSearchTool20260209.builder().maxUses(3L).userLocation(loc).build())
    }

    private fun record(model: String, msg: Message) {
        val u = msg.usage()
        app.budget.record(model, u.inputTokens(), u.outputTokens(),
            u.cacheCreationInputTokens().orElse(0L), u.cacheReadInputTokens().orElse(0L),
            u.serverToolUse().map { it.webSearchRequests() }.orElse(0L))
    }

    /** Une phrase claire à dire quand l'appel à Claude échoue. */
    fun explain(e: Throwable): String = when (e) {
        is UnauthorizedException, is PermissionDeniedException ->
            "Ma clé Claude est refusée, Rod. Il faudra en remettre une avec le script d'installation."
        is RateLimitException -> "Je reçois trop de demandes d'un coup. Laisse-moi une minute."
        is OverloadedException, is InternalServerException -> "Les serveurs de Claude sont surchargés. Réessaie dans un instant."
        is AnthropicIoException -> "Je n'arrive pas à joindre mon cerveau, le réseau a l'air instable."
        else -> "J'ai un souci pour joindre mon cerveau. Réessaie dans un instant."
    }

    // ---------- Mémoire ----------

    private val memoryInstructions = """
Tu tiens la mémoire de Hex, l'assistant personnel de Rod. On te donne la mémoire actuelle puis une conversation.
Renvoie UNIQUEMENT un objet JSON {"ops":[...]} : la liste des changements à faire. Liste vide si rien ne change.
Opérations possibles :
{"op":"fait","texte":"..."}  nouveau fait stable sur Rod ou ses proches, ses goûts, sa façon de parler (une phrase, « Rod … »).
{"op":"corriger","id":N,"texte":"..."}  remplace le fait [N] devenu faux ou incomplet.
{"op":"oublier","id":N}  supprime le fait [N] (faux, ou Rod demande d'oublier).
{"op":"ville","valeur":"..."}  sa ville actuelle.
{"op":"projet","nom":"...","statut":"en cours|en pause|fini","prochaine_etape":"...","echeance":"AAAA-MM-JJ","decision":"...","note":"..."}  crée ou met à jour un projet (même nom exact pour un projet existant). Ne mets que les champs qui changent ; decision et note s'ajoutent à l'historique.
{"op":"projet_supprimer","nom":"..."}
{"op":"journal","resume":"..."}  ce qui s'est dit aujourd'hui, en 1 à 2 phrases. Une seule par conversation, si elle a eu du contenu.
{"op":"objectif_course","km":N,"periode":"semaine|mois"}
{"op":"decouverte_finie"}  quand on connaît sa ville, son activité, au moins un projet et son objectif running, ou si Hex a conclu la découverte.
{"op":"questions_traitees"}  si Hex a répondu aux questions posées hors ligne.
{"op":"archive","mois":"AAAA-MM","resume":"..."}  seulement pour les mois indiqués à archiver : résume en 2 à 4 phrases les entrées du journal de ce mois.
Règles : ne garde que ce que Rod a dit ou confirmé, n'invente rien, pas de doublon avec un fait existant. Les sorties running sont déjà enregistrées : ne les compte pas.
""".trim()

    /** Après une conversation : Claude propose les changements de mémoire. Bloquant. */
    fun updateMemory(transcript: List<Turn>, date: String = LocalDate.now().toString()): Boolean {
        if (transcript.isEmpty()) return true
        if (app.budget.blocked) return false
        val model = app.prefs.model
        val today = date
        val convo = transcript.joinToString("\n") { (if (it.fromRod) "Rod : " else "Hex : ") + it.text }
        val archive = app.memory.monthsToArchive()
        val user = StringBuilder()
            .append("Aujourd'hui : ").append(today).append("\n\nMÉMOIRE ACTUELLE :\n").append(app.memory.forPrompt())
            .apply { if (archive.isNotEmpty()) append("\nMois du journal à archiver : ").append(archive.joinToString(", ")).append('\n') }
            .append("\nCONVERSATION :\n").append(convo)
            .toString()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(memoryInstructions)
            .addUserMessage(user)
            .build()
        return try {
            val res = client().messages().create(params)
            record(model, res)
            val text = res.content().mapNotNull { blk -> blk.text().map { it.text() }.orElse(null) }.joinToString("")
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return false
            val ops = JSONObject(text.substring(start, end + 1)).optJSONArray("ops") ?: JSONArray()
            val n = app.memory.apply(ops, today)
            Log.i(tag, "mémoire : $n changement(s)")
            true
        } catch (e: Exception) {
            Log.w(tag, "mise à jour mémoire", e)
            false
        }
    }

    /** Mémorise une conversation, ou la garde pour plus tard si ça échoue. */
    fun rememberOrQueue(transcript: List<Turn>) { try {
        if (!(app.net.online && updateMemory(transcript))) {
            val arr = JSONArray()
            transcript.forEach { arr.put(JSONObject().put("rod", it.fromRod).put("text", it.text)) }
            app.memory.queueTranscript(arr)
        }
    } catch (e: Exception) { Log.w(tag, "mémorisation", e) } }

    /** Retente les conversations restées en attente (au retour d'Internet, au démarrage). */
    fun retryQueued() { try { retryQueuedUnsafe() } catch (e: Exception) { Log.w(tag, "file d'attente", e) } }

    private fun retryQueuedUnsafe() {
        if (!app.net.online || !app.memory.hasQueuedTranscripts || app.budget.blocked) return
        val queued = app.memory.takeQueuedTranscripts()
        for (i in 0 until queued.length()) {
            val item = queued.optJSONObject(i) ?: continue
            val turns = item.optJSONArray("turns") ?: continue
            val t = (0 until turns.length()).mapNotNull { k -> turns.optJSONObject(k)?.let { Turn(it.optBoolean("rod"), it.optString("text")) } }
            if (!updateMemory(t, item.optString("date").ifBlank { LocalDate.now().toString() })) {
                // On remet ce qui reste en attente et on réessaiera plus tard
                for (k in i until queued.length()) queued.optJSONObject(k)?.optJSONArray("turns")?.let { app.memory.queueTranscript(it) }
                return
            }
        }
    }
}
