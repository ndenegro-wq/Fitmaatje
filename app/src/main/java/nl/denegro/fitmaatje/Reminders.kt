package nl.denegro.fitmaatje

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifs.createChannels(this)
        Reminders.scheduleNext(this)
    }
}

object Reminders {
    private data class Ev(val at: LocalDateTime, val kind: String, val idx: Int)

    private fun eventsFor(d: LocalDate): List<Ev> {
        val l = mutableListOf<Ev>()
        l += Ev(d.atTime(7, 15), "morning", 0)
        Repo.moments.forEachIndexed { i, m -> l += Ev(d.atTime(m).minusMinutes(5), "moment", i + 1) }
        l += Ev(d.atTime(21, 30), "evening", 0)
        return l
    }

    fun scheduleNext(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, "", 0)
        am.cancel(pi)
        if (!Repo.reminders) return
        val now = LocalDateTime.now()
        val next = (eventsFor(now.toLocalDate()) + eventsFor(now.toLocalDate().plusDays(1)))
            .filter { it.at.isAfter(now.plusSeconds(30)) }
            .minByOrNull { it.at } ?: return
        val t = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(c, next.kind, next.idx))
    }

    private fun pending(c: Context, kind: String, idx: Int): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).putExtra("kind", kind).putExtra("idx", idx)
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun fire(c: Context, kind: String, idx: Int) {
        val today = LocalDate.now()
        val s = Repo.sum(today)
        when (kind) {
            "morning" -> {
                val thursday = today.dayOfWeek == DayOfWeek.THURSDAY
                val first = Repo.moments.firstOrNull()?.format(HM) ?: "-"
                val plan = Plans.plans[today]
                val text = buildString {
                    if (plan != null) append("Je eetschema (${plan.theme}) staat klaar: ${plan.meals.firstOrNull()?.title ?: ""} om $first. ")
                    else append("Vandaag: ${Repo.kcalTarget} kcal over ${Repo.moments.size} momenten, eerste om $first. Open de app voor je eetschema. ")
                    if (thursday) append("Het is donderdag: weegdag! Spreek je gewicht in. ")
                    append("Welke training doe je vandaag? Spreek het even in.")
                }
                Notifs.remind(c, if (thursday) "Goedemorgen ${Repo.name} — weegdag" else "Goedemorgen ${Repo.name}", text, true)
            }
            "moment" -> {
                if (idx !in s.moments) {
                    val left = Repo.kcalTarget - s.kcal
                    val meal = Plans.plans[today]?.meals?.firstOrNull { it.moment == idx }
                    val text = if (meal != null)
                        "Op je schema: ${meal.title} — " + meal.foods.joinToString(", ") { "${it.name} ${it.amount}" } +
                            " (${meal.kcal} kcal). Gegeten? Vink het af in Schema of spreek in wat je anders at."
                    else "Nog $left kcal over vandaag. Spreek in wat je eet."
                    Notifs.remind(c, "Eetmoment $idx om ${Repo.moments.getOrNull(idx - 1)?.format(HM)}", text, meal == null)
                }
            }
            "evening" -> {
                val text = "Vandaag ${s.kcal} van ${Repo.kcalTarget} kcal, ${s.protein} g eiwit, " +
                    "${s.moments.size}/${Repo.moments.size} momenten, ${s.sportMin} min sport. " +
                    if (s.kcal == 0) "Nog niets gelogd? Spreek je dag even kort in." else "Goed bezig, morgen weer!"
                Notifs.remind(c, "Je dag in het kort", text, s.kcal == 0)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Repo.init(c)
        val kind = intent.getStringExtra("kind") ?: ""
        Reminders.fire(c, kind, intent.getIntExtra("idx", 0))
        Reminders.scheduleNext(c)
        // In the evening, prepare tomorrow's eating plan in the background.
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        if (kind == "evening" && Repo.apiKey.isNotBlank() && Plans.plans[tomorrow] == null) {
            val pending = goAsync()
            Thread {
                try { runCatching { Plans.generate(tomorrow) } } finally { pending.finish() }
            }.start()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Repo.init(c)
        Reminders.scheduleNext(c)
        if (Repo.listening) {
            // Android does not allow a microphone service to start by itself after a reboot.
            Notifs.remind(c, "Meeluisteren staat uit", "Je telefoon is herstart. Tik hier om FitMaatje te openen; meeluisteren gaat dan weer aan.", false)
        }
    }
}
