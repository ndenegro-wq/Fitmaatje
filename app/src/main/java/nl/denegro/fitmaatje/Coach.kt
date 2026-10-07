package nl.denegro.fitmaatje

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime

data class Video(val name: String, val query: String)

/** Splits the coach's answer into visible text and exercise-video markers. */
fun splitVideos(text: String): Pair<String, List<Video>> {
    val re = Regex("""\[\[\s*oefening\s*:\s*([^|\]]+?)\s*(?:\|\s*([^\]]+?))?\s*]]""", RegexOption.IGNORE_CASE)
    val vids = re.findAll(text).map { m ->
        val name = m.groupValues[1].trim()
        val q = m.groupValues[2].trim().trim('"', '“', '”').ifBlank { "$name techniek uitleg" }
        Video(name, q)
    }.distinctBy { it.name.lowercase() }.toList()
    return re.replace(text, "").trim() to vids
}

fun openVideo(ctx: android.content.Context, query: String) {
    val url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

object Coach {

    private fun systemPrompt() = """
Je bent FitMaatje, de persoonlijke voedings- en sportcoach van ${Repo.name}. Je spreekt Nederlands: direct, warm, kort en concreet.
Hij werkt aan gezond afvallen met dit plan: ${Repo.protocol}
Dagdoel: ${Repo.kcalTarget} kcal en circa ${Repo.proteinTarget} g eiwit. Eetmomenten: ${Repo.moments.mapIndexed { i, m -> "${i + 1}=${m.format(HM)}" }.joinToString(", ")}.
Je bent een coach, geen arts: bij klachten, duizeligheid of pijn verwijs je naar huisarts of zijn eigen coach. Moedig nooit extreem weinig eten of overtraining aan.
""".trim()

    /** Low-level call to the Anthropic Messages API. */
    fun call(system: String, messages: JSONArray, maxTokens: Int = 1000): String {
        val key = Repo.apiKey
        if (key.isBlank()) throw IllegalStateException("Nog geen API-sleutel ingesteld. Ga naar Instellingen.")
        val con = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 20000
            con.readTimeout = 120000
            con.setRequestProperty("content-type", "application/json")
            con.setRequestProperty("x-api-key", key)
            con.setRequestProperty("anthropic-version", "2023-06-01")
            val body = JSONObject()
                .put("model", Repo.model)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", messages)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault(txt.take(300))
                throw RuntimeException("Coach niet bereikbaar ($code): $msg")
            }
            val arr = JSONObject(txt).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "text") sb.append(o.getString("text"))
            }
            return sb.toString().trim()
        } finally {
            con.disconnect()
        }
    }

    private fun single(user: String) = JSONArray().put(JSONObject().put("role", "user").put("content", user))

    /** Turns a spoken/typed log into a structured Entry (blocking: call off the main thread). */
    fun process(text: String, now: Long = System.currentTimeMillis()): Entry {
        val nowTime = LocalTime.now()
        val fallbackMoment = Repo.momentAt(nowTime)
        if (Repo.apiKey.isBlank()) {
            return Entry(now, now, text, fallbackMoment, emptyList(), emptyList(), null,
                "Opgeslagen. Stel in Instellingen je API-sleutel in, dan reken ik calorieën en eiwit voor je uit.")
        }
        val user = """
${Repo.contextText(2)}

Ingesproken tekst van ${Repo.name}:
«$text»

Zet dit om naar precies één JSON-object (geen tekst eromheen, geen markdown) met deze velden:
{"foods":[{"name":"","amount":"","kcal":0,"protein_g":0}],
 "exercises":[{"name":"","detail":"","minutes":0,"kcal":0}],
 "weight_kg":null,
 "moment":null,
 "reply":""}
Regels:
- foods: alles wat hij eet, gaat eten of heeft gegeten. Schat kcal en eiwit realistisch voor gangbare Nederlandse porties (NEVO-tabel). amount = hoeveelheid zoals genoemd of geschat.
- exercises: oefeningen/sport die hij doet, gaat doen of heeft gedaan. detail bijv. "3x12" of "5 km". Schat minuten en verbruikte kcal.
- weight_kg: alleen als hij zijn gewicht noemt.
- moment: nummer van het eetmoment (1-${Repo.moments.size}) waar dit eten bij hoort, op basis van tijd en wat hij zegt; null als het buiten het schema valt of er geen eten is.
- reply: maximaal 3 korte zinnen. Bevestig wat je noteerde, noem wat er vandaag nog over is (kcal/eiwit) en geef één concrete tip. Valt eten buiten het schema of past het niet bij het plan, zeg dat vriendelijk maar eerlijk.
- Niets herkend? Lege lijsten en een korte reply.
""".trim()
        val raw = call(systemPrompt(), single(user), 1200)
        val json = raw.substring(raw.indexOf('{').coerceAtLeast(0), (raw.lastIndexOf('}') + 1).coerceAtLeast(0))
        val o = runCatching { JSONObject(json) }.getOrElse {
            return Entry(now, now, text, fallbackMoment, emptyList(), emptyList(), null, raw.take(400))
        }
        val fa = o.optJSONArray("foods") ?: JSONArray()
        val ea = o.optJSONArray("exercises") ?: JSONArray()
        val foods = (0 until fa.length()).map { i ->
            val f = fa.getJSONObject(i)
            Food(f.optString("name"), f.optString("amount"), f.optDouble("kcal", 0.0).toInt(), f.optDouble("protein_g", 0.0).toInt())
        }
        val exs = (0 until ea.length()).map { i ->
            val e = ea.getJSONObject(i)
            Ex(e.optString("name"), e.optString("detail"), e.optDouble("minutes", 0.0).toInt(), e.optDouble("kcal", 0.0).toInt())
        }
        val moment = if (o.isNull("moment") || !o.has("moment")) (if (foods.isNotEmpty()) fallbackMoment else null)
        else o.optInt("moment").takeIf { it in 1..Repo.moments.size }
        val weight = if (o.isNull("weight_kg") || !o.has("weight_kg")) null else o.optDouble("weight_kg").takeIf { !it.isNaN() && it > 0 }
        return Entry(now, now, text, moment, foods, exs, weight, o.optString("reply"))
    }

    /** Free chat with the coach (blocking). */
    fun ask(question: String): String {
        val history = Repo.chat.takeLast(10)
        val msgs = JSONArray()
        // Messages must alternate and start with user.
        var expectUser = true
        history.forEach { m ->
            if (m.fromMe == expectUser) {
                msgs.put(JSONObject().put("role", if (m.fromMe) "user" else "assistant").put("content", m.text))
                expectUser = !expectUser
            }
        }
        if (!expectUser) msgs.put(JSONObject().put("role", "assistant").put("content", "Oké."))
        msgs.put(JSONObject().put("role", "user").put("content", "Actuele gegevens:\n${Repo.contextText(7)}\n\nVraag: $question"))
        return call(systemPrompt() + """

Antwoord kort (max ~120 woorden) tenzij om een schema of uitleg wordt gevraagd. Geen markdown-koppen of sterretjes.
Noem je concrete oefeningen (ook in een trainingsschema), zet dan helemaal onderaan per oefening één regel in exact dit formaat:
[[oefening: <Nederlandse naam> | <Engelse zoekterm voor een techniekvideo, bijv. "push up proper form">]]
De app maakt daar videoknoppen van; noem die regels verder niet in je tekst.""", msgs, 1800)
    }
}
