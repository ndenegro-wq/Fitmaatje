package nl.denegro.fitmaatje

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalTime

object Notifs {
    const val CH_LISTEN = "listen"
    const val CH_EAT = "eat"
    const val CH_REMIND = "remind"
    const val ID_LISTEN = 1
    const val ID_EAT = 2
    const val ID_REMIND = 3

    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Meeluisteren", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Vaste melding zolang FitMaatje meeluistert"
        })
        nm.createNotificationChannel(NotificationChannel(CH_EAT, "Eten gedetecteerd", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Melding als FitMaatje hoort dat je gaat eten"
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Herinneringen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Eetmomenten, ochtendplan en avondoverzicht"
        })
    }

    fun openApp(c: Context, speak: Boolean, req: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("speak", speak)
        }
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun serviceAction(c: Context, action: String, req: Int): PendingIntent {
        val i = Intent(c, ListenService::class.java).setAction(action)
        return PendingIntent.getService(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun listening(c: Context, status: String): Notification =
        NotificationCompat.Builder(c, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("FitMaatje luistert mee")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(c, false, 10))
            .addAction(0, "Inspreken", openApp(c, true, 11))
            .addAction(0, "Pauze 2 uur", serviceAction(c, ListenService.ACTION_PAUSE, 12))
            .addAction(0, "Stop", serviceAction(c, ListenService.ACTION_STOP, 13))
            .build()

    private fun canPost(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun eatingDetected(c: Context) {
        if (!canPost(c)) return
        val now = LocalTime.now()
        val m = Repo.momentAt(now)
        val already = m != null && m in Repo.sum(java.time.LocalDate.now()).moments
        val (title, text) = when {
            m != null && !already -> "Eetmoment $m — eet smakelijk!" to
                "Hoor ik je eten? Spreek even in wat je eet, dan tel ik het mee."
            m != null -> "Eetmoment $m had je al gelogd" to
                "Dit is een extra eetmoment. Bewuste keuze, of is het trek? Spreek het in, dan houd ik je dag op koers."
            else -> {
                val next = Repo.nextMoment(now)
                "Dit staat niet in je schema" to
                    ("Bewuste keuze, of is het trek? " + (next?.let { "Je volgende eetmoment is om ${it.second.format(HM)}. " } ?: "")
                        + "Eet je toch, spreek het dan even in.")
            }
        }
        val n = NotificationCompat.Builder(c, CH_EAT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(c, true, 20))
            .addAction(0, "Inspreken", openApp(c, true, 21))
            .addAction(0, "Ik eet niet", serviceAction(c, ListenService.ACTION_NO, 22))
            .addAction(0, "Gezelschap 2 uur", serviceAction(c, ListenService.ACTION_PAUSE, 23))
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(ID_EAT, n) }
    }

    fun remind(c: Context, title: String, text: String, speak: Boolean) {
        if (!canPost(c)) return
        val n = NotificationCompat.Builder(c, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(c, speak, 30))
            .apply { if (speak) addAction(0, "Inspreken", openApp(c, true, 31)) }
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(ID_REMIND, n) }
    }
}
