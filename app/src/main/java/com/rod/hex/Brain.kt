package com.rod.hex

import android.content.Context
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Le cerveau : parle à Claude avec la personnalité de Hex et la mémoire de Rod. */
class Brain(private val context: Context) {
    data class Turn(val fromRod: Boolean, val text: String)

    class OutOfBudget : Exception("budget atteint")

    private val tag = "HexBrain"
    private val app = HexApp.of(context)
    private var client: AnthropicClient? = null
    private var clientKey = ""

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
- Tu l'aides surtout à gérer ses projets : faire le point, rappeler ce qui a été décidé, repérer ce qui n'avance plus, aider à choisir la prochaine action. Tu peux lui dire franchement qu'un projet traîne.
- Tu réponds et tu conseilles. Tu n'as aucun moyen d'agir hors de la conversation (pas de mails, pas d'appels, pas d'achats) : si Rod te le demande, dis-le simplement.

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

    private fun dynamicContext(offlineQueued: Boolean): String {
        val mem = app.memory.snapshot()
        val now = LocalDateTime.now()
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH'h'mm", Locale.FRENCH))
        val sb = StringBuilder()
        sb.append("Nous sommes le ").append(date).append(".\n\n")
        sb.append("Ta mémoire sur Rod (JSON) :\n").append(mem.toString(1)).append("\n")
        val pending = mem.optJSONArray("pending_questions")
        if (pending != null && pending.length() > 0 && offlineQueued) {
            sb.append("\nRod t'a posé ces questions pendant que tu étais hors ligne. Réponds-y d'abord, brièvement.\n")
        }
        val r = mem.optJSONObject("running")
        if (r != null && r.optDouble("goal_km", 0.0) > 0 && now.hour >= 18) {
            sb.append("\nSi la conversation s'y prête et que Rod ne t'a pas parlé de course aujourd'hui (dernière sortie : ")
                .append(r.optString("last_run", "inconnue"))
                .append("), demande-lui s'il a couru et combien de km.\n")
        }
        return sb.toString()
    }

    /**
     * Répond à Rod en streaming. [onText] reçoit les morceaux de texte au fil de l'eau.
     * Bloquant : à appeler hors du thread principal.
     */
    fun reply(history: List<Turn>, offlineQueued: Boolean, onText: (String) -> Unit): String {
        if (app.budget.blocked) throw OutOfBudget()
        val model = app.prefs.model
        val stable = if (app.memory.onboarded) personality else personality + "\n\n" + onboarding
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder().text(stable).cacheControl(CacheControlEphemeral.builder().build()).build(),
                    TextBlockParam.builder().text(dynamicContext(offlineQueued)).build(),
                )
            )
        // L'historique doit commencer par Rod et alterner : on fusionne les tours consécutifs du même côté
        val merged = mutableListOf<Turn>()
        for (t in history.dropWhile { !it.fromRod }) {
            val last = merged.lastOrNull()
            if (last != null && last.fromRod == t.fromRod) merged[merged.size - 1] = Turn(t.fromRod, last.text + "\n" + t.text)
            else merged.add(t)
        }
        for (t in merged) if (t.fromRod) b.addUserMessage(t.text) else b.addAssistantMessage(t.text)
        val out = StringBuilder()
        var inTok = 0L; var outTok = 0L; var cw = 0L; var cr = 0L
        var refused = false
        client().messages().createStreaming(b.build()).use { stream ->
            stream.stream().forEach { ev ->
                ev.messageStart().ifPresent { s ->
                    val u = s.message().usage()
                    inTok = u.inputTokens()
                    cw = u.cacheCreationInputTokens().orElse(0L)
                    cr = u.cacheReadInputTokens().orElse(0L)
                }
                ev.contentBlockDelta().ifPresent { d ->
                    d.delta().text().ifPresent { t -> out.append(t.text()); onText(t.text()) }
                }
                ev.messageDelta().ifPresent { d ->
                    outTok = d.usage().outputTokens()
                    if (d.delta().stopReason().map { it == StopReason.REFUSAL }.orElse(false)) refused = true
                }
            }
        }
        app.budget.record(model, inTok, outTok, cw, cr)
        if (refused && out.isBlank()) {
            val msg = "Celle-là, je préfère ne pas y répondre, Rod."
            onText(msg); return msg
        }
        return out.toString()
    }

    /** Après une conversation : Claude met à jour les carnets. Bloquant. */
    fun updateMemory(transcript: List<Turn>): Boolean {
        if (transcript.isEmpty() || app.budget.blocked) return false
        val model = app.prefs.model
        val mem = app.memory.snapshot()
        val today = java.time.LocalDate.now().toString()
        val convo = transcript.joinToString("\n") { (if (it.fromRod) "Rod : " else "Hex : ") + it.text }
        val instructions = """
Tu tiens la mémoire de Hex, l'assistant personnel de Rod. Voici la mémoire actuelle (JSON) puis la conversation qui vient d'avoir lieu (aujourd'hui : $today).
Renvoie UNIQUEMENT la mémoire mise à jour, en JSON valide, avec exactement ces clés :
- "onboarded" (booléen) : passe à true quand on connaît sa ville, son activité, au moins un projet et son objectif running, ou si Hex a conclu la découverte.
- "profile" (texte) : faits stables sur Rod et ses proches, ses préférences, sa façon de parler. Concis, sans doublon.
- "city" (texte) : sa ville actuelle, pour la météo.
- "projects" (liste d'objets {"name","status","next","deadline","decisions","notes"}) : un objet par projet. deadline au format AAAA-MM-JJ ou "". status parmi "en cours", "en pause", "fini".
- "journal" (liste d'objets {"date","summary"}) : garde les entrées existantes et ajoute ou complète celle d'aujourd'hui en 1 à 3 phrases.
- "running" (objet {"goal_km","period","period_start","done_km","last_run"}) : period = "semaine" ou "mois". Si Rod donne une sortie, ajoute ses km à done_km et mets last_run à la date. Si la période est terminée, remets done_km à 0 et avance period_start.
- "pending_questions" (liste) : retire celles auxquelles Hex a répondu.
Ne garde que ce que Rod a dit ou confirmé. N'invente rien. Si rien n'a changé, renvoie la mémoire telle quelle.
""".trim()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(instructions)
            .addUserMessage("MÉMOIRE ACTUELLE :\n" + mem.toString() + "\n\nCONVERSATION :\n" + convo)
            .build()
        return try {
            val res = client().messages().create(params)
            val u = res.usage()
            app.budget.record(model, u.inputTokens(), u.outputTokens(),
                u.cacheCreationInputTokens().orElse(0L), u.cacheReadInputTokens().orElse(0L))
            val text = res.content().mapNotNull { blk -> blk.text().map { it.text() }.orElse(null) }.joinToString("")
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return false
            app.memory.replaceWith(JSONObject(text.substring(start, end + 1)))
            true
        } catch (e: Exception) {
            Log.w(tag, "mise à jour mémoire", e)
            false
        }
    }
}
