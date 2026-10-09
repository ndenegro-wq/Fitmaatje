package nl.denegro.fitmaatje

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CoachRole(val id: String, val name: String, val role: String, val emoji: String, val task: String)
data class HelpOption(val id: String, val label: String, val text: String, val question: String)

/** Coachteam, hulp, voortgang en uitwisseling met ChatGPT (gelijk aan de web-app). */
object Team {
    val COACHES = listOf(
        CoachRole("ayse", "Ayse", "Coördinatie", "🧭", "Maak één overzichtelijk dagplan. Stem voeding, bewegen en motivatie op elkaar af."),
        CoachRole("sara", "Sara", "Voeding", "🥗", "Help met gewone vullende maaltijden, porties en praktische alternatieven bij zoete trek."),
        CoachRole("milan", "Milan", "Beweging", "🚶", "Maak bewegen haalbaar, bouw wandelen rustig op. Vraag naar beperkingen; geen strafsport of zware oefeningen bij pijn."),
        CoachRole("emma", "Emma", "Motivatie", "💛", "Help zonder oordeel bij zoete trek, stress en terugval. Kies één kleine volgende stap."),
        CoachRole("noor", "Noor", "Voortgang", "📈", "Bespreek werkelijk gelogde gewichtstrends over meerdere weken en de tussendoelen. Geen dieetwijziging op basis van één uitschieter."),
    )
    fun coach(id: String?) = COACHES.firstOrNull { it.id == id } ?: COACHES[0]

    val HELP = listOf(
        HelpOption("sweet", "Ik wil zoet",
            "Sta even stil: heb je honger, of vooral zin in een bepaalde smaak? Bij honger kun je iets vullends nemen, zoals yoghurt met fruit. Kies je iets zoets, leg een portie op een bordje en berg de verpakking op.",
            "Ik heb sterke trek in zoet. Help me één haalbare stap te kiezen en vraag wanneer die trek komt en wat ik dan meestal eet."),
        HelpOption("extra", "Ik heb meer gegeten",
            "Je hoeft niets te herstellen met vasten of extra sporten. Ga bij je volgende maaltijd verder met je normale plan. Eén moment bepaalt je hele traject niet.",
            "Ik heb meer gegeten dan ik wilde. Help me zonder schuldgevoel mijn gewone eetpatroon te hervatten."),
        HelpOption("quit", "Ik wil opgeven",
            "Maak het voor dit moment kleiner. Kies één stap: je volgende maaltijd plannen, iets klaarmaken voor morgen of iemand vertellen dat je steun nodig hebt. Vandaag hoeft niet perfect te zijn.",
            "Ik vind afvallen nu moeilijk en wil opgeven. Help me één kleine volgende stap te kiezen."),
    )
    fun help(id: String?) = HELP.firstOrNull { it.id == id } ?: HELP[0]

    val SWEET_SWAPS = listOf(
        "Magere kwark of skyr met kaneel en een paar bessen",
        "Een appel of peer in partjes, of 2 mandarijnen",
        "Thee met kaneel of zoethoutthee, of koffie",
        "2 vierkantjes pure chocolade (70%+) op een bordje, rustig opeten",
        "Bevroren druiven of banaanplakjes",
    )

    fun teamPrompt() = """
Het coachteam bestaat uit AI-rollen die hetzelfde dagboek delen: ${COACHES.joinToString(", ") { "${it.name} (${it.role.lowercase()})" }}. Verwijs naar een collega als een vraag beter bij die rol past. Het zijn geen geregistreerde zorgverleners.
Profiel (alleen wat de gebruiker invulde): lengte ${Repo.heightCm.ifBlank { "onbekend" }} cm; startgewicht ${Repo.startWeight.ifBlank { "onbekend" }} kg; gewenst einddoel ${Repo.goalWeight.ifBlank { "onbekend" }} kg; startdatum ${Repo.startDate.ifBlank { "onbekend" }}.
Gezondheid en aandachtspunten: ${Repo.healthNotes.ifBlank { "nog niet ingevuld" }}.
${progressLine()}
Werk met haalbare tussendoelen, zonder beloofde einddatum. Verzin geen voortgang. Bij zoete trek: vraag naar tijd, hoeveelheid, honger, gewoonte of stress. Geen schuldgevoel, totaalverbod of compensatie door vasten of extra sport. Vraag naar actuele beperkingen voordat je oefeningen geeft. Bij pijn op de borst, ernstige benauwdheid of flauwvallen adviseer je spoedzorg. Adviseer geen strenger kcal-doel dan ingesteld zonder overleg met huisarts of diëtist.
""".trim()

    // ---------------------------------------------------------------- voortgang

    private fun d(s: String) = s.replace(',', '.').toDoubleOrNull()

    fun milestones(): List<Double> {
        val start = d(Repo.startWeight) ?: return emptyList()
        val goal = d(Repo.goalWeight) ?: return emptyList()
        if (start <= goal) return emptyList()
        val r1 = Math.round(start * 0.95 * 10) / 10.0
        val r2 = Math.round(start * 0.90 * 10) / 10.0
        val l = mutableListOf(r1, r2)
        var k = Math.floor(r2 / 5) * 5
        while (k > goal) { if (k < r2 - 1) l.add(k); k -= 5 }
        l.add(goal)
        return l.filter { it in goal..start && it < start }.distinct().sortedDescending()
    }

    data class Progress(val start: Double?, val goal: Double?, val current: Double?, val lost: Double?, val next: Double?, val trend: Double?, val milestones: List<Double>)

    private fun avg(days: Int, endOffset: Long = 0): Double? {
        val to = LocalDate.now().minusDays(endOffset)
        val from = to.minusDays((days - 1).toLong())
        val l = Repo.entries.filter { it.weight != null && !it.date.isBefore(from) && !it.date.isAfter(to) }.map { it.weight!! }
        return if (l.isEmpty()) null else l.average()
    }

    fun progress(): Progress {
        val start = d(Repo.startWeight)
        val goal = d(Repo.goalWeight)
        val cur = Repo.lastWeight()?.second ?: start
        val ms = milestones()
        val a7 = avg(7); val p7 = avg(7, 7)
        return Progress(
            start, goal, cur,
            if (start != null && cur != null) Math.round((start - cur) * 10) / 10.0 else null,
            ms.firstOrNull { cur != null && it < cur - 0.05 },
            if (a7 != null && p7 != null) Math.round((a7 - p7) * 10) / 10.0 else null,
            ms,
        )
    }

    fun progressLine(): String {
        val p = progress(); val start = p.start ?: return ""
        return "Voortgang: start $start kg${if (Repo.startDate.isNotBlank()) " op ${Repo.startDate}" else ""}, nu ${p.current} kg " +
            "(${if ((p.lost ?: 0.0) >= 0) "-" else "+"}${kotlin.math.abs(p.lost ?: 0.0)} kg), gewenst einddoel ${p.goal ?: "?"} kg. " +
            "Tussendoelen: ${p.milestones.joinToString(" → ") { fmt(it) }} kg. Volgend tussendoel: ${p.next?.let { fmt(it) } ?: "-"} kg." +
            (p.trend?.let { " 7-daags gemiddelde t.o.v. week ervoor: ${if (it > 0) "+" else ""}$it kg." } ?: "")
    }

    fun fmt(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    fun walkExercise(min: Int): Ex {
        val kg = Repo.lastWeight()?.second ?: d(Repo.startWeight) ?: 80.0
        return Ex("Wandelen", "", min, Math.round(3.5 * kg * min / 60).toInt())
    }

    // ---------------------------------------------------------------- ChatGPT

    const val IMPORT_TAG = "FITMAATJE-IMPORT"

    fun chatgptInstruction() = """
Je begeleidt mij samen met mijn app FitMaatje. Ik plak soms een "FitMaatje-overzicht"; gebruik dat als actuele stand (de calorieën zijn schattingen).
Als we in dit gesprek iets vastleggen wat in mijn app hoort (maaltijd, gewicht, wandeling, check-in of afspraak), zet dan helemaal onderaan je antwoord één codeblok dat begint met de regel $IMPORT_TAG gevolgd door JSON in dit formaat:
$IMPORT_TAG
{"items":[
 {"type":"meal","date":"JJJJ-MM-DD","time":"UU:MM","text":"wat ik at","kcal":0,"protein_g":0},
 {"type":"weight","date":"JJJJ-MM-DD","kg":0},
 {"type":"walk","date":"JJJJ-MM-DD","minutes":0},
 {"type":"note","date":"JJJJ-MM-DD","text":"afspraak of inzicht van de coach"}
]}
Neem alleen items op die ik echt heb genoemd. Verzin geen gegevens. Ik kopieer dat blok en plak het in FitMaatje bij "Importeren".
""".trim()

    fun overviewText(): String = """
FitMaatje-overzicht voor mijn dieetbegeleiding
Naam: ${Repo.name}
Lengte: ${Repo.heightCm.ifBlank { "niet ingevuld" }} cm
Startgewicht: ${Repo.startWeight.ifBlank { "niet ingevuld" }} kg
Gewenst einddoel: ${Repo.goalWeight.ifBlank { "niet ingevuld" }} kg
Startdatum: ${Repo.startDate.ifBlank { "niet ingevuld" }}
Voorkeuren: ${Repo.foodPrefs.ifBlank { "niet ingevuld" }}
Aandachtspunten: ${Repo.healthNotes.ifBlank { "niet ingevuld" }}

${Repo.contextText(7)}
Volgend tussendoel: ${progress().next?.let { fmt(it) } ?: "-"} kg.
Begeleid me met één haalbare volgende stap. De gelogde calorieën zijn schattingen; ontbrekende logs betekenen niet dat ik niets gegeten heb.
""".trim()

    // ---------------------------------------------------------------- import / export

    private val PROFILE_KEYS = listOf("name", "heightCm", "startWeight", "goalWeight", "startDate", "healthNotes", "kcal", "protein", "moments", "protocol", "prefs")

    fun profileJson(): JSONObject = JSONObject()
        .put("name", Repo.name).put("heightCm", Repo.heightCm).put("startWeight", Repo.startWeight).put("goalWeight", Repo.goalWeight)
        .put("startDate", Repo.startDate).put("healthNotes", Repo.healthNotes).put("kcal", Repo.kcalTarget).put("protein", Repo.proteinTarget)
        .put("moments", Repo.momentsCsv).put("protocol", Repo.protocol).put("prefs", Repo.foodPrefs)

    sealed class Import {
        data class Profile(val o: JSONObject) : Import()
        data class Items(val a: JSONArray) : Import()
        data class Backup(val o: JSONObject) : Import()
    }

    fun parse(raw: String): Import {
        val tag = raw.indexOf(IMPORT_TAG)
        val txt = if (tag >= 0) raw.substring(tag + IMPORT_TAG.length) else raw
        val a = txt.indexOf('{'); val b = txt.lastIndexOf('}')
        if (a < 0 || b <= a) throw IllegalArgumentException("Geen gegevens gevonden. Plak het hele blok, inclusief { en }.")
        val o = JSONObject(txt.substring(a, b + 1))
        return when {
            o.has("fitmaatjeProfile") -> Import.Profile(o.getJSONObject("fitmaatjeProfile"))
            o.has("items") -> Import.Items(o.getJSONArray("items"))
            o.has("entries") || o.has("settings") || o.has("profile") -> Import.Backup(o)
            else -> throw IllegalArgumentException("Onbekend formaat.")
        }
    }

    fun describe(i: Import): String = when (i) {
        is Import.Profile -> "Profiel: " + PROFILE_KEYS.filter { i.o.has(it) && i.o.optString(it).isNotBlank() }.joinToString(", ")
        is Import.Items -> "${i.a.length()} item(s) uit ChatGPT: " + (0 until i.a.length()).joinToString(", ") { i.a.getJSONObject(it).optString("type") }
        is Import.Backup -> "Back-up: ${(i.o.optJSONArray("entries") ?: JSONArray()).length()} dagboekregels"
    }

    private fun applyProfile(o: JSONObject, onlyEmpty: Boolean) {
        fun has(k: String) = o.has(k) && !o.isNull(k) && o.optString(k).isNotBlank()
        fun str(k: String) = o.optString(k)
        if (has("name") && (!onlyEmpty || Repo.name.isBlank())) Repo.name = str("name")
        if (has("heightCm") && (!onlyEmpty || Repo.heightCm.isBlank())) Repo.heightCm = str("heightCm")
        if (has("startWeight") && (!onlyEmpty || Repo.startWeight.isBlank())) Repo.startWeight = str("startWeight")
        if (has("goalWeight") && (!onlyEmpty || Repo.goalWeight.isBlank())) Repo.goalWeight = str("goalWeight")
        if (has("startDate") && (!onlyEmpty || Repo.startDate.isBlank())) Repo.startDate = str("startDate")
        if (has("healthNotes") && (!onlyEmpty || Repo.healthNotes.isBlank())) Repo.healthNotes = str("healthNotes")
        if (has("prefs") && (!onlyEmpty || Repo.foodPrefs.isBlank())) Repo.foodPrefs = str("prefs")
        if (!onlyEmpty) {
            if (has("kcal")) o.optInt("kcal").takeIf { it in 800..5000 }?.let { Repo.kcalTarget = it }
            if (has("protein")) o.optInt("protein").takeIf { it in 20..400 }?.let { Repo.proteinTarget = it }
            if (has("moments")) Repo.momentsCsv = str("moments")
            if (has("protocol")) Repo.protocol = str("protocol")
        }
    }

    private fun itemToEntry(o: JSONObject, n: Int): Entry? {
        val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrDefault(LocalDate.now())
        val time = runCatching { LocalTime.parse(o.optString("time").let { if (it.length == 4) "0$it" else it }, HM) }.getOrDefault(LocalTime.NOON)
        val ts = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = System.currentTimeMillis() + n
        val text = o.optString("text")
        return when (o.optString("type")) {
            "meal" -> Entry(id, ts, text, Repo.momentAt(time),
                listOf(Food(text.ifBlank { "Maaltijd" }, o.optString("amount"), o.optDouble("kcal", 0.0).toInt(), o.optDouble("protein_g", 0.0).toInt())),
                emptyList(), null, "", source = "chatgpt")
            "weight" -> o.optDouble("kg", 0.0).takeIf { it > 30 }?.let {
                Entry(id, ts, text.ifBlank { "Gewicht (uit ChatGPT)" }, null, emptyList(), emptyList(), Math.round(it * 10) / 10.0, "", source = "chatgpt") }
            "walk" -> o.optInt("minutes").takeIf { it > 0 }?.let {
                Entry(id, ts, text.ifBlank { "$it min gewandeld" }, null, emptyList(), listOf(walkExercise(it)), null, "", kind = "walk", source = "chatgpt") }
            "note", "checkin" -> Entry(id, ts, "Notitie uit ChatGPT", null, emptyList(), emptyList(), null, text, kind = "note", source = "chatgpt")
            else -> null
        }
    }

    /** Voegt alleen toe; bestaande gegevens worden nooit overschreven. */
    fun apply(i: Import) {
        when (i) {
            is Import.Profile -> applyProfile(i.o, onlyEmpty = false)
            is Import.Items -> Repo.addAll((0 until i.a.length()).mapNotNull { itemToEntry(i.a.getJSONObject(it), it) })
            is Import.Backup -> {
                val a = i.o.optJSONArray("entries") ?: JSONArray()
                Repo.addAll((0 until a.length()).mapNotNull { k -> runCatching { webOrAndroidEntry(a.getJSONObject(k)) }.getOrNull() })
                (i.o.optJSONObject("profile") ?: i.o.optJSONObject("settings"))?.let { applyProfile(it, onlyEmpty = true) }
            }
        }
    }

    /** Accepteert zowel Android-dagboekregels als regels uit de web-app-back-up. */
    private fun webOrAndroidEntry(o: JSONObject): Entry {
        if (o.has("ts") && o.has("id") && !o.has("date")) return Entry.fromJson(o)
        val e = Entry.fromJson(o)
        return e.copy(
            weight = if (o.isNull("weight")) null else o.optDouble("weight").takeIf { !it.isNaN() },
            photo = null,
            text = e.text.ifBlank { o.optString("text") },
        )
    }

    fun daysSinceStart(): Long? = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(Repo.startDate), LocalDate.now()) }.getOrNull()
}
