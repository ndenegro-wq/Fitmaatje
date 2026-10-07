package nl.denegro.fitmaatje

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.time.LocalTime
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class Meal(val moment: Int, val time: String, val title: String, val foods: List<Food>, val note: String) {
    val kcal get() = foods.sumOf { it.kcal }
    val protein get() = foods.sumOf { it.protein }

    fun toJson(): JSONObject = JSONObject().put("moment", moment).put("time", time).put("title", title).put("note", note)
        .put("foods", JSONArray().apply {
            foods.forEach { put(JSONObject().put("name", it.name).put("amount", it.amount).put("kcal", it.kcal).put("protein_g", it.protein)) }
        })

    companion object {
        fun fromJson(o: JSONObject, fallbackMoment: Int = 0): Meal {
            val fa = o.optJSONArray("foods") ?: JSONArray()
            return Meal(
                moment = o.optInt("moment", fallbackMoment),
                time = o.optString("time"),
                title = o.optString("title"),
                foods = (0 until fa.length()).map { i ->
                    val f = fa.getJSONObject(i)
                    Food(f.optString("name"), f.optString("amount"),
                        f.optDouble("kcal", 0.0).toInt(),
                        (if (f.has("protein_g")) f.optDouble("protein_g", 0.0) else f.optDouble("protein", 0.0)).toInt())
                },
                note = o.optString("note"),
            )
        }
    }
}

data class DayPlan(val date: LocalDate, val meals: List<Meal>, val tip: String, val theme: String) {
    val kcal get() = meals.sumOf { it.kcal }
    val protein get() = meals.sumOf { it.protein }

    fun toJson(): JSONObject = JSONObject().put("date", date.toString()).put("tip", tip).put("theme", theme)
        .put("meals", JSONArray().apply { meals.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject, date: LocalDate = LocalDate.parse(o.getString("date"))): DayPlan {
            val ma = o.optJSONArray("meals") ?: JSONArray()
            return DayPlan(
                date = date,
                meals = (0 until ma.length()).map { Meal.fromJson(ma.getJSONObject(it), it + 1) }.sortedBy { it.moment },
                tip = o.optString("tip"),
                theme = o.optString("theme"),
            )
        }
    }
}

object Plans {
    private lateinit var file: File
    val plans = mutableStateMapOf<LocalDate, DayPlan>()

    // Background work survives switching tabs/screens.
    private val worker = Executors.newSingleThreadExecutor()
    val busy = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)

    fun launch(label: String, block: () -> Unit) {
        if (busy.value != null) return
        busy.value = label; error.value = null
        worker.execute {
            try { block() } catch (e: Throwable) { error.value = e.message ?: "Er ging iets mis" }
            finally { busy.value = null }
        }
    }

    /** Moments of [date] that can still be planned (today: from now on, with 20 min grace). */
    fun openMoments(date: LocalDate): List<Pair<Int, LocalTime>> {
        val all = Repo.moments.mapIndexed { i, t -> (i + 1) to t }
        if (date != LocalDate.now()) return all
        val now = LocalTime.now().minusMinutes(20)
        val eaten = Repo.sum(date).moments
        return all.filter { (n, t) -> t.isAfter(now) && n !in eaten }
    }

    /** Day to show by default: tomorrow once today's last moment has passed. */
    fun defaultDate(): LocalDate =
        if (openMoments(LocalDate.now()).isEmpty()) LocalDate.now().plusDays(1) else LocalDate.now()

    fun init(c: Context) {
        if (::file.isInitialized) return
        file = File(c.filesDir, "plans.json")
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.keys().forEach { k -> plans[LocalDate.parse(k)] = DayPlan.fromJson(o.getJSONObject(k)) }
            }
        }
    }

    @Synchronized
    fun put(p: DayPlan) {
        plans[p.date] = p
        // keep the last 30 days
        plans.keys.filter { it.isBefore(LocalDate.now().minusDays(30)) }.forEach { plans.remove(it) }
        val o = JSONObject()
        plans.forEach { (d, pl) -> o.put(d.toString(), pl.toJson()) }
        file.writeText(o.toString())
    }

    private fun recentThemes(before: LocalDate): String =
        (1..4).mapNotNull { plans[before.minusDays(it.toLong())] }
            .joinToString("; ") { p -> "${p.date}: ${p.theme} (" + p.meals.joinToString(", ") { it.title } + ")" }
            .ifBlank { "geen" }

    private fun planRules(moments: List<Pair<Int, LocalTime>> = Repo.moments.mapIndexed { i, t -> (i + 1) to t },
                          kcal: Int = Repo.kcalTarget, protein: Int = Repo.proteinTarget) = """
Eisen:
- Plan precies deze eetmomenten (nummer=tijd): ${moments.joinToString(", ") { "${it.first}=${it.second.format(HM)}" }}.
- Samen ${kcal} kcal (marge ±40) en minimaal ${protein} g eiwit. Grotere maaltijden op de hoofdmomenten (ontbijt/lunch/avondeten), kleine tussendoortjes.
- Volg het protocol: ${Repo.protocol}
- Eiwitrotatie: wissel de eiwitbron af t.o.v. de afgelopen dagen (bijv. kip, vis, ei, kwark/skyr, rund/mager vlees, peulvruchten/tofu).
- Gewone, betaalbare producten uit een Nederlandse (of Spaanse) supermarkt. Simpele bereiding, max 15 min doordeweeks.
- Concrete hoeveelheden in gram/stuks/el. kcal en eiwit realistisch volgens NEVO.
- Voorkeuren / niet eten: ${Repo.foodPrefs.ifBlank { "geen opgegeven" }}
""".trim()

    /** Builds a full day plan (blocking). */
    fun generate(date: LocalDate, wish: String = ""): DayPlan {
        val open = openMoments(date)
        if (open.isEmpty()) throw RuntimeException("Voor vandaag zijn alle eetmomenten voorbij. Kies ▶ voor het schema van morgen.")
        val partial = open.size < Repo.moments.size
        val eaten = if (date == LocalDate.now()) Repo.sum(date) else null
        val kcalLeft = (Repo.kcalTarget - (eaten?.kcal ?: 0)).coerceAtLeast(200)
        val protLeft = (Repo.proteinTarget - (eaten?.protein ?: 0)).coerceAtLeast(15)
        val eatenText = if (eaten != null && eaten.kcal > 0)
            "Vandaag al gegeten: ${eaten.kcal} kcal, ${eaten.protein} g eiwit (" +
                Repo.day(date).flatMap { it.foods }.joinToString(", ") { it.name } + ")."
        else ""
        val user = """
Maak een eetschema voor ${Repo.name} voor ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("nl"))} $date.
Het is nu ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.
${if (partial) "Een deel van de dag is al voorbij; plan alleen de resterende momenten. $eatenText" else ""}
${planRules(open, kcalLeft, protLeft)}
Recente schema's (niet herhalen): ${recentThemes(date)}
${if (wish.isNotBlank()) "Extra wens voor dit schema: $wish" else ""}

Antwoord met alleen één JSON-object, zonder tekst eromheen:
{"theme":"korte naam van de eiwitfocus","tip":"1 korte praktische tip",
 "meals":[{"moment":${open.first().first},"time":"${open.first().second.format(HM)}","title":"","foods":[{"name":"","amount":"","kcal":0,"protein_g":0}],"note":"max 1 zin bereiding"}]}
""".trim()
        val o = Coach.callJson("Je bent een Nederlandse voedingscoach die nauwkeurige, haalbare dagmenu's maakt. Antwoord compact.", user, 6000)
        val p = DayPlan.fromJson(o, date).let { pl ->
            pl.copy(meals = pl.meals.filter { m -> open.any { it.first == m.moment } }.ifEmpty { pl.meals })
        }
        if (p.meals.isEmpty()) throw RuntimeException("Leeg schema ontvangen, probeer opnieuw.")
        put(p)
        return p
    }

    /** Replaces one meal with an alternative of similar kcal/protein (blocking). */
    fun swap(plan: DayPlan, moment: Int, wish: String = ""): DayPlan {
        val old = plan.meals.first { it.moment == moment }
        val user = """
Geef een ander voorstel voor eetmoment $moment (${old.time}) van ${Repo.name}.
Huidig: ${old.title} — ${old.foods.joinToString(", ") { "${it.name} ${it.amount}" }} (${old.kcal} kcal, ${old.protein} g eiwit).
Houd ongeveer dezelfde kcal (±30) en minstens evenveel eiwit, maar kies iets duidelijk anders.
Rest van de dag: ${plan.meals.filter { it.moment != moment }.joinToString("; ") { it.title }}.
${planRules()}
${if (wish.isNotBlank()) "Wens: $wish" else ""}
Antwoord met alleen één JSON-object: {"moment":$moment,"time":"${old.time}","title":"","foods":[{"name":"","amount":"","kcal":0,"protein_g":0}],"note":""}
""".trim()
        val m = Meal.fromJson(Coach.callJson("Je bent een Nederlandse voedingscoach.", user, 4000), moment).copy(moment = moment, time = old.time)
        val np = plan.copy(meals = plan.meals.map { if (it.moment == moment) m else it })
        put(np)
        return np
    }

    fun shoppingList(p: DayPlan): String =
        p.meals.flatMap { it.foods }.groupBy { it.name.lowercase().trim() }
            .map { (_, l) -> "• ${l.first().name}: " + l.joinToString(" + ") { it.amount } }
            .joinToString("\n")
}
