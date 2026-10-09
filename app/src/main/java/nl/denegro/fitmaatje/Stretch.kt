package nl.denegro.fitmaatje

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Een lenigheidsoefening. [seconds] per kant als [twoSides]. */
data class Stretch(
    val name: String,
    val seconds: Int,
    val twoSides: Boolean,
    val how: String,
    val video: String,
)

object Stretches {
    val ALL = listOf(
        Stretch("Nek ontspannen", 30, false,
            "Sta of zit rechtop. Breng je oor rustig naar je schouder, terug naar het midden en naar de andere kant. Schouders laag, niet rollen met je hoofd.",
            "gentle neck stretch beginner"),
        Stretch("Schouders rollen", 30, false,
            "Rol je schouders langzaam en groot naar achteren, daarna naar voren. Adem rustig door.",
            "shoulder rolls exercise"),
        Stretch("Borst openen aan deurpost", 30, true,
            "Onderarm tegen de deurpost, elleboog op schouderhoogte. Draai je bovenlichaam zacht weg tot je rek voelt in borst en schouder.",
            "doorway chest stretch"),
        Stretch("Zijwaartse rek", 30, true,
            "Sta met voeten op heupbreedte, één arm boven je hoofd. Buig rustig opzij, alsof je tussen twee muren staat. Niet verend bewegen.",
            "standing side stretch beginner"),
        Stretch("Kuiten tegen de muur", 30, true,
            "Handen tegen de muur, één been naar achteren met de hiel op de grond. Buig je voorste knie tot je de kuit van je achterste been voelt.",
            "wall calf stretch"),
        Stretch("Bovenbeen voorkant (staand)", 30, true,
            "Houd een stoel of muur vast. Pak je enkel en breng je hiel richting bil. Knieën naast elkaar, heup iets naar voren. Lukt pakken niet: gebruik een handdoek.",
            "standing quad stretch with support"),
        Stretch("Achterkant bovenbeen (stoel)", 30, true,
            "Zit op de rand van een stoel, één been gestrekt met de hiel op de grond. Kantel met rechte rug vanuit je heup naar voren.",
            "seated hamstring stretch chair"),
        Stretch("Heupbuiger (uitvalspas)", 30, true,
            "Kleine uitvalspas, achterste knie op een kussen of sta. Span je billen aan en schuif je heup licht naar voren. Rug rechtop.",
            "kneeling hip flexor stretch beginner"),
        Stretch("Kat-koe (rug mobiliseren)", 45, false,
            "Op handen en knieën, of zittend met handen op je knieën. Maak je rug bol en kijk naar je navel, daarna hol en kijk vooruit. Op het ritme van je adem.",
            "cat cow stretch beginner"),
        Stretch("Rug draaien (zittend)", 30, true,
            "Zit rechtop op een stoel. Draai je bovenlichaam rustig naar één kant, hand op de rugleuning. Groei bij elke inademing een beetje langer.",
            "seated spinal twist chair"),
    )

    data class Routine(val id: String, val title: String, val items: List<Stretch>) {
        val seconds get() = items.sumOf { it.seconds * if (it.twoSides) 2 else 1 }
        val minutes get() = (seconds + 59) / 60
    }

    val MORNING = Routine("ochtend", "☀️ Ochtend · wakker worden", listOf(ALL[0], ALL[1], ALL[3], ALL[4], ALL[8], ALL[9]))
    val EVENING = Routine("avond", "🌙 Avond · ontspannen", ALL)
    val ROUTINES = listOf(MORNING, EVENING)
}

private fun beep(double: Boolean = false) {
    runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        tg.startTone(if (double) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 200)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StretchSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var routine by remember { mutableStateOf<Stretches.Routine?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.background) {
        val r = routine
        if (r == null) StretchMenu(onStart = { routine = it }, onVideo = { openVideo(ctx, it) })
        else StretchPlayer(r, onDone = { finished ->
            if (finished > 0) {
                val now = System.currentTimeMillis()
                val min = (finished + 59) / 60
                val kg = Repo.lastWeight()?.second ?: Repo.startWeight.replace(',', '.').toDoubleOrNull() ?: 80.0
                Repo.add(Entry(now, now, "Lenigheid: ${r.title.drop(2).trim()} ($min min)", null, emptyList(),
                    listOf(Ex("Lenigheid", r.id, min, Math.round(2.3 * kg * min / 60).toInt())), null, "", kind = "stretch"))
                toast(ctx, "🧘 $min minuten lenigheid genoteerd")
            }
            onClose()
        })
    }
}

@Composable
private fun StretchMenu(onStart: (Stretches.Routine) -> Unit, onVideo: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🧘 Lenigheid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Rustig rekken, niet verend bewegen en blijf doorademen. Je voelt rek, geen pijn. Stop bij pijn, duizeligheid of tintelingen.",
            fontSize = 13.sp, color = Color.DarkGray)
        Stretches.ROUTINES.forEach { r ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.title, fontWeight = FontWeight.Bold)
                    Text("${r.items.size} oefeningen · ±${r.minutes} min", fontSize = 13.sp, color = Color.DarkGray)
                    Button(onClick = { onStart(r) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("▶ Start begeleid") }
                }
            }
        }
        Text("Alle oefeningen", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Stretches.ALL.forEach { s ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${s.seconds} s${if (s.twoSides) " per kant" else ""}", fontSize = 12.sp, color = Color.DarkGray)
                    }
                    Text(s.how, fontSize = 14.sp)
                    TextButton(onClick = { onVideo(s.video) }) { Text("▶ Voorbeeldvideo") }
                }
            }
        }
    }
}

@Composable
private fun StretchPlayer(r: Stretches.Routine, onDone: (Int) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var idx by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(r.items[0].let { it.seconds * if (it.twoSides) 2 else 1 }) }
    var paused by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    val s = r.items[idx]
    val total = s.seconds * if (s.twoSides) 2 else 1

    fun goTo(i: Int) {
        if (i >= r.items.size) { beep(true); onDone(done); return }
        idx = i.coerceAtLeast(0)
        val n = r.items[idx]; left = n.seconds * if (n.twoSides) 2 else 1
    }
    LaunchedEffect(idx, paused) {
        while (!paused && left > 0) {
            delay(1000)
            left--; done++
            if (s.twoSides && left == s.seconds) beep()
        }
        if (!paused && left == 0) { beep(); delay(600); goTo(idx + 1) }
    }

    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${r.title} · ${idx + 1}/${r.items.size}", fontSize = 13.sp, color = Color.DarkGray)
        LinearProgressIndicator(progress = { (idx + (total - left).toFloat() / total) / r.items.size }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Green, trackColor = GreenLight)
        Text(s.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (s.twoSides) Text(if (left > s.seconds) "Eerste kant" else "Andere kant", color = GreenDark, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(GreenLight).padding(horizontal = 10.dp, vertical = 4.dp))
        Text("%d:%02d".format(left / 60, left % 60), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = GreenDark)
        Text(s.how, fontSize = 15.sp, textAlign = TextAlign.Center)
        TextButton(onClick = { paused = true; openVideo(ctx, s.video) }) { Text("▶ Voorbeeldvideo (pauzeert)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { goTo(idx - 1) }, enabled = idx > 0) { Text("◀") }
            Button(onClick = { paused = !paused }, modifier = Modifier.width(140.dp)) { Text(if (paused) "▶ Verder" else "⏸ Pauze") }
            OutlinedButton(onClick = { goTo(idx + 1) }) { Text("▶▶") }
        }
        TextButton(onClick = { onDone(done) }) { Text("Stoppen en opslaan") }
    }
}
