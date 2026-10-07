package nl.denegro.fitmaatje

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
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

    private fun planRules() = """
Eisen:
- Precies ${Repo.moments.size} eetmomenten op deze tijden: ${Repo.moments.mapIndexed { i, m -> "${i + 1}=${m.format(HM)}" }.joinToString(", ")}.
- Dagtotaal ${Repo.kcalTarget} kcal (marge ±40) en minimaal ${Repo.proteinTarget} g eiwit, verdeeld over de dag. Grotere maaltijden op de hoofdmomenten, kleine tussendoortjes.
- Volg het protocol: ${Repo.protocol}
- Eiwitrotatie: wissel de eiwitbron af t.o.v. de afgelopen dagen (bijv. kip, vis, ei, kwark/skyr, rund/mager vlees, peulvruchten/tofu).
- Gewone, betaalbare producten uit een Nederlandse (of Spaanse) supermarkt. Simpele bereiding, max 15 min doordeweeks.
- Concrete hoeveelheden in gram/stuks/el. kcal en eiwit realistisch volgens NEVO.
- Voorkeuren / niet eten: ${Repo.foodPrefs.ifBlank { "geen opgegeven" }}
""".trim()

    /** Builds a full day plan (blocking). */
    fun generate(date: LocalDate, wish: String = ""): DayPlan {
        val user = """
Maak een eetschema voor ${Repo.name} voor ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("nl"))} $date.
${planRules()}
Recente schema's (niet herhalen): ${recentThemes(date)}
${if (wish.isNotBlank()) "Extra wens voor dit schema: $wish" else ""}

Antwoord met alleen één JSON-object, zonder tekst eromheen:
{"theme":"korte naam van de eiwitfocus van vandaag","tip":"1 korte praktische tip voor vandaag",
 "meals":[{"moment":1,"time":"07:30","title":"","foods":[{"name":"","amount":"","kcal":0,"protein_g":0}],"note":"korte bereiding of tip"}]}
""".trim()
        val o = Coach.callJson("Je bent een Nederlandse voedingscoach die nauwkeurige, haalbare dagmenu's maakt.", user, 8000)
        val p = DayPlan.fromJson(o, date)
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
