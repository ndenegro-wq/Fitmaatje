package nl.denegro.fitmaatje

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Food(val name: String, val amount: String, val kcal: Int, val protein: Int)
data class Ex(val name: String, val detail: String, val minutes: Int, val kcal: Int)

data class Entry(
    val id: Long,
    val ts: Long,
    val text: String,
    val moment: Int?,
    val foods: List<Food>,
    val exercises: List<Ex>,
    val weight: Double?,
    val reply: String,
) {
    val kcal get() = foods.sumOf { it.kcal }
    val protein get() = foods.sumOf { it.protein }
    val date: LocalDate get() = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    val time: String get() = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime().format(HM)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("ts", ts); put("text", text)
        put("moment", moment ?: JSONObject.NULL)
        put("foods", JSONArray().apply {
            foods.forEach { put(JSONObject().put("name", it.name).put("amount", it.amount).put("kcal", it.kcal).put("protein", it.protein)) }
        })
        put("exercises", JSONArray().apply {
            exercises.forEach { put(JSONObject().put("name", it.name).put("detail", it.detail).put("minutes", it.minutes).put("kcal", it.kcal)) }
        })
        put("weight", weight ?: JSONObject.NULL)
        put("reply", reply)
    }

    companion object {
        fun fromJson(o: JSONObject): Entry {
            val fa = o.optJSONArray("foods") ?: JSONArray()
            val ea = o.optJSONArray("exercises") ?: JSONArray()
            return Entry(
                id = o.getLong("id"),
                ts = o.getLong("ts"),
                text = o.optString("text"),
                moment = if (o.isNull("moment")) null else o.optInt("moment"),
                foods = (0 until fa.length()).map { i ->
                    val f = fa.getJSONObject(i)
                    Food(f.optString("name"), f.optString("amount"), f.optInt("kcal"), f.optInt("protein"))
                },
                exercises = (0 until ea.length()).map { i ->
                    val e = ea.getJSONObject(i)
                    Ex(e.optString("name"), e.optString("detail"), e.optInt("minutes"), e.optInt("kcal"))
                },
                weight = if (o.isNull("weight")) null else o.optDouble("weight"),
                reply = o.optString("reply"),
            )
        }
    }
}

data class ChatMsg(val fromMe: Boolean, val text: String)

data class DaySum(val kcal: Int, val protein: Int, val sportMin: Int, val sportKcal: Int, val moments: Set<Int>)

val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

object Repo {
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    val entries = mutableStateListOf<Entry>()
    val chat = mutableStateListOf<ChatMsg>()
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        prefs = ctx.getSharedPreferences("fitmaatje", Context.MODE_PRIVATE)
        load()
    }

    // ---------- settings ----------
    private fun s(k: String, d: String) = prefs.getString(k, d) ?: d
    private fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()

    var name: String get() = s("name", "Nick"); set(v) = put("name", v)
    var apiKey: String get() = s("apiKey", ""); set(v) = put("apiKey", v.trim())
    var model: String get() = s("model", "claude-sonnet-5-5"); set(v) = put("model", v.trim())
    var kcalTarget: Int get() = prefs.getInt("kcal", 1560); set(v) = prefs.edit().putInt("kcal", v).apply()
    var proteinTarget: Int get() = prefs.getInt("prot", 130); set(v) = prefs.edit().putInt("prot", v).apply()
    var momentsCsv: String get() = s("moments", "07:30,10:00,12:30,15:30,18:00,20:30"); set(v) = put("moments", v)
    var protocol: String
        get() = s("protocol", "FitSupport-protocol: ongeveer 1560 kcal per dag, 6 eetmomenten, eiwitrotatie. Wekelijks wegen op donderdag met coach.")
        set(v) = put("protocol", v)
    var listening: Boolean get() = prefs.getBoolean("listen", false); set(v) = prefs.edit().putBoolean("listen", v).apply()
    var sensitivity: Int get() = prefs.getInt("sens", 2); set(v) = prefs.edit().putInt("sens", v).apply()
    var quietStart: Int get() = prefs.getInt("qs", 23); set(v) = prefs.edit().putInt("qs", v).apply()
    var quietEnd: Int get() = prefs.getInt("qe", 7); set(v) = prefs.edit().putInt("qe", v).apply()
    var reminders: Boolean get() = prefs.getBoolean("rem", true); set(v) = prefs.edit().putBoolean("rem", v).apply()

    val moments: List<LocalTime>
        get() = momentsCsv.split(",", ";", " ").mapNotNull { t ->
            runCatching { LocalTime.parse(t.trim().let { if (it.length == 4) "0$it" else it }, HM) }.getOrNull()
        }.sorted()

    /** Planned moment (1-based) within 60 minutes of [t], or null. */
    fun momentAt(t: LocalTime): Int? {
        val ms = moments
        var best: Int? = null
        var bestDiff = Long.MAX_VALUE
        ms.forEachIndexed { i, m ->
            val d = kotlin.math.abs(java.time.Duration.between(m, t).toMinutes())
            if (d <= 60 && d < bestDiff) { best = i + 1; bestDiff = d }
        }
        return best
    }

    fun nextMoment(t: LocalTime): Pair<Int, LocalTime>? {
        val ms = moments
        val i = ms.indexOfFirst { it.isAfter(t) }
        return if (i >= 0) (i + 1) to ms[i] else null
    }

    // ---------- data ----------
    private val entriesFile get() = File(ctx.filesDir, "entries.json")
    private val chatFile get() = File(ctx.filesDir, "chat.json")

    private fun load() {
        entries.clear(); chat.clear()
        runCatching {
            if (entriesFile.exists()) {
                val a = JSONArray(entriesFile.readText())
                for (i in 0 until a.length()) entries.add(Entry.fromJson(a.getJSONObject(i)))
            }
        }
        runCatching {
            if (chatFile.exists()) {
                val a = JSONArray(chatFile.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i); chat.add(ChatMsg(o.getBoolean("me"), o.getString("t")))
                }
            }
        }
    }

    private fun saveEntries() {
        val a = JSONArray(); entries.forEach { a.put(it.toJson()) }
        entriesFile.writeText(a.toString())
    }

    private fun saveChat() {
        val a = JSONArray(); chat.takeLast(60).forEach { a.put(JSONObject().put("me", it.fromMe).put("t", it.text)) }
        chatFile.writeText(a.toString())
    }

    private fun onMain(f: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }

    fun add(e: Entry) = onMain { entries.add(e); entries.sortBy { it.ts }; saveEntries() }
    fun delete(e: Entry) = onMain { entries.removeAll { it.id == e.id }; saveEntries() }
    fun addChat(m: ChatMsg) = onMain { chat.add(m); saveChat() }
    fun clearChat() = onMain { chat.clear(); saveChat() }

    fun day(d: LocalDate): List<Entry> = entries.filter { it.date == d }

    fun sum(d: LocalDate): DaySum {
        val l = day(d)
        return DaySum(
            kcal = l.sumOf { it.kcal },
            protein = l.sumOf { it.protein },
            sportMin = l.sumOf { e -> e.exercises.sumOf { it.minutes } },
            sportKcal = l.sumOf { e -> e.exercises.sumOf { it.kcal } },
            moments = l.mapNotNull { if (it.foods.isNotEmpty()) it.moment else null }.toSet(),
        )
    }

    fun lastWeight(): Pair<LocalDate, Double>? =
        entries.lastOrNull { it.weight != null }?.let { it.date to it.weight!! }

    /** Short text summary used as context for the coach. */
    fun contextText(days: Int = 7): String {
        val sb = StringBuilder()
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        sb.append("Nu: ").append(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm", java.util.Locale("nl")))).append('\n')
        sb.append("Doel: ").append(kcalTarget).append(" kcal, ").append(proteinTarget).append(" g eiwit per dag. Eetmomenten: ")
            .append(moments.mapIndexed { i, m -> "${i + 1}=${m.format(HM)}" }.joinToString(", ")).append('\n')
        sb.append("Protocol: ").append(protocol).append('\n')
        lastWeight()?.let { sb.append("Laatste gewicht: ").append(it.second).append(" kg op ").append(it.first).append('\n') }
        for (k in days - 1 downTo 0) {
            val d = today.minusDays(k.toLong())
            val l = day(d)
            if (l.isEmpty()) continue
            val s = sum(d)
            sb.append("\n").append(if (k == 0) "VANDAAG" else d.toString()).append(": ")
                .append(s.kcal).append(" kcal, ").append(s.protein).append(" g eiwit, sport ").append(s.sportMin)
                .append(" min, momenten ").append(s.moments.sorted().joinToString(",")).append('\n')
            if (k <= 1) l.forEach { e ->
                sb.append("  ").append(e.time).append(" ")
                if (e.foods.isNotEmpty()) sb.append(e.foods.joinToString("; ") { "${it.name} ${it.amount} (${it.kcal} kcal)" }).append(' ')
                if (e.exercises.isNotEmpty()) sb.append("sport: ").append(e.exercises.joinToString("; ") { "${it.name} ${it.detail} ${it.minutes} min" }).append(' ')
                e.weight?.let { sb.append("gewicht ").append(it).append(" kg") }
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
