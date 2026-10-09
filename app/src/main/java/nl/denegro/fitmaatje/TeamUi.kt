package nl.denegro.fitmaatje

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors

/** Gesprek met het coachteam; loopt door als je van tabblad wisselt. */
object Talk {
    private val worker = Executors.newSingleThreadExecutor()
    val busy = mutableStateOf(false)

    fun ask(question: String, coachId: String = Repo.activeCoach) {
        if (busy.value) return
        val role = Team.coach(coachId)
        Repo.activeCoach = role.id
        Repo.addChat(ChatMsg(true, question))
        busy.value = true
        worker.execute {
            val answer = runCatching { Coach.ask(question, role.id) }.getOrElse { it.message ?: "Er ging iets mis" }
            Repo.addChat(ChatMsg(false, answer, role.name))
            busy.value = false
        }
    }
}

fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

fun copyText(c: Context, text: String, msg: String) {
    c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("FitMaatje", text))
    toast(c, msg)
}

/** Opent het deelmenu (kies daar de ChatGPT-app). */
fun shareText(c: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    c.startActivity(Intent.createChooser(send, "Deel met ChatGPT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun newId() = System.currentTimeMillis()

// ---------------------------------------------------------------- hulp bij moeilijke momenten

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(onClose: () -> Unit, onLog: () -> Unit, onDiscuss: (String) -> Unit) {
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf("sweet") }
    var level by remember { mutableFloatStateOf(3f) }
    var endAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endAt) { while (endAt > 0 && now < endAt) { delay(500); now = System.currentTimeMillis() } }
    val h = Team.help(choice)

    fun log(outcome: String) {
        val t = System.currentTimeMillis()
        Repo.add(Entry(newId(), t, "${h.label} · sterkte ${level.toInt()}/5 · $outcome", null, emptyList(), emptyList(), null, "",
            kind = "craving", type = choice))
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ik heb het moeilijk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Team.HELP.forEach { o ->
                    FilterChip(selected = choice == o.id, onClick = { choice = o.id }, label = { Text(o.label, fontSize = 13.sp) })
                }
            }
            Text(h.text)
            if (choice == "sweet") {
                Text("Hoe sterk is de trek? ${level.toInt()}/5", fontSize = 13.sp, color = Color.DarkGray)
                Slider(value = level, onValueChange = { level = it }, valueRange = 1f..5f, steps = 3)
                Card(colors = CardDefaults.cardColors(containerColor = GreenLight)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Stap 1 · Golf uitzitten (10 min)", fontWeight = FontWeight.Bold)
                        Text("Trek piekt meestal en zakt dan weer. Drink een groot glas water, loop even naar buiten of poets je tanden. Daarna kies je bewust.", fontSize = 14.sp)
                        if (endAt == 0L) Button(onClick = { endAt = System.currentTimeMillis() + 10 * 60_000; now = System.currentTimeMillis() },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("▶ Start 10 minuten") }
                        else {
                            val left = (endAt - now).coerceAtLeast(0)
                            Text(if (left > 0) "%d:%02d".format(left / 60000, (left % 60000) / 1000) else "Klaar! Hoe is de trek nu?",
                                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GreenDark,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = GreenLight)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Stap 2 · Als je toch iets wilt", fontWeight = FontWeight.Bold)
                        Team.SWEET_SWAPS.forEach { Text("• $it", fontSize = 14.sp) }
                    }
                }
                Button(onClick = { log("gezakt"); toast(ctx, "Goed gedaan 💪 genoteerd"); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("✓ Trek is gezakt") }
                OutlinedButton(onClick = { log("bewust iets genomen"); onClose(); onLog() }, modifier = Modifier.fillMaxWidth()) { Text("Ik neem bewust iets — loggen") }
            } else {
                Button(onClick = { log("verder gegaan"); toast(ctx, "Genoteerd. Volgende maaltijd gewoon verder."); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Oké, ik ga verder") }
            }
            OutlinedButton(onClick = { log("besproken"); onClose(); onDiscuss(h.question) }, modifier = Modifier.fillMaxWidth()) { Text("💬 Bespreek dit met Emma") }
            Text("Werkt ook zonder internet. Vandaag ${Repo.cravings(LocalDate.now()).size}× hulp gevraagd — Emma en Noor zien dit patroon in je dagboek, zonder oordeel.",
                fontSize = 12.sp, color = Color.DarkGray)
        }
    }
}

// ---------------------------------------------------------------- ochtend- en avondcheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckSheet(type: String, onClose: () -> Unit, onDiscuss: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val morning = type == "morning"
    var a by remember { mutableFloatStateOf(3f) }   // slaap / eten
    var energy by remember { mutableFloatStateOf(3f) }
    var sweet by remember { mutableFloatStateOf(2f) }
    var weight by remember { mutableStateOf("") }
    var walk by remember { mutableStateOf("") }
    var t1 by remember { mutableStateOf("") }
    var t2 by remember { mutableStateOf("") }

    fun save(discuss: Boolean) {
        val now = System.currentTimeMillis()
        val text: String
        var w: Double? = null
        var ex: List<Ex> = emptyList()
        if (morning) {
            w = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..400.0 }?.let { Math.round(it * 10) / 10.0 }
            text = "Ochtendcheck: slaap ${a.toInt()}/5, energie ${energy.toInt()}/5, zoete trek ${sweet.toInt()}/5" +
                (w?.let { ", gewicht $it kg" } ?: "") + ". Planning: ${t1.ifBlank { "-" }}"
        } else {
            val min = walk.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val walkedLogged = Repo.day(LocalDate.now()).any { it.kind == "walk" }
            if (min > 0 && !walkedLogged) ex = listOf(Team.walkExercise(min))
            text = "Avondcheck: eten ${a.toInt()}/5, energie ${energy.toInt()}/5, $min min gewandeld, zoete-trekmomenten vandaag " +
                "${Repo.cravings(LocalDate.now()).size}. Goed: ${t1.ifBlank { "-" }}. Morgen: ${t2.ifBlank { "-" }}"
        }
        Repo.add(Entry(now, now, text, null, emptyList(), ex, w, "", kind = "checkin", type = type))
        onClose()
        if (discuss) {
            if (Repo.apiKey.isBlank()) toast(ctx, "Opgeslagen. Stel je API-sleutel in voor een reactie van de coach.")
            else onDiscuss(
                if (morning) "$text\nMaak samen met mij een haalbaar dagplan: eten, wanneer wandelen, en een plan voor als de zoete trek komt."
                else "$text\nReflecteer kort en help me één concrete stap voor morgen te kiezen.",
                if (morning) "ayse" else "emma")
        } else toast(ctx, "Opgeslagen ✓")
    }

    @Composable
    fun Scale(label: String, v: Float, lo: String, hi: String, set: (Float) -> Unit) {
        Text("$label: ${v.toInt()}/5", fontSize = 13.sp, color = Color.DarkGray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lo, fontSize = 12.sp); Slider(value = v, onValueChange = set, valueRange = 1f..5f, steps = 3, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)); Text(hi, fontSize = 12.sp)
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (morning) "☀️ Ochtendcheck" else "🌙 Avondcheck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Duurt 1 minuut. Je antwoorden komen in je dagboek, zodat het hele coachteam ermee verder kan.", fontSize = 13.sp, color = Color.DarkGray)
            if (morning) {
                Scale("Hoe heb je geslapen?", a, "slecht", "goed") { a = it }
                Scale("Energie nu", energy, "laag", "hoog") { energy = it }
                Scale("Trek in zoet nu", sweet, "geen", "veel") { sweet = it }
                OutlinedTextField(weight, { weight = it }, label = { Text("Gewicht vandaag (optioneel, kg)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(t1, { t1 = it }, label = { Text("Wat staat er vandaag op de planning? Wanneer kun je wandelen?") }, modifier = Modifier.fillMaxWidth())
            } else {
                Scale("Hoe ging het eten vandaag?", a, "lastig", "goed") { a = it }
                Scale("Energie vandaag", energy, "laag", "hoog") { energy = it }
                OutlinedTextField(walk, { walk = it.filter(Char::isDigit) }, label = { Text("Gewandeld vandaag (minuten)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(t1, { t1 = it }, label = { Text("Wat ging goed?") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(t2, { t2 = it }, label = { Text("Wat wil je morgen anders of hetzelfde doen?") }, modifier = Modifier.fillMaxWidth())
            }
            Text("Tip: gebruik de 🎤 van je toetsenbord om in te spreken.", fontSize = 12.sp, color = Color.DarkGray)
            Button(onClick = { save(true) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Opslaan & bespreken met ${if (morning) "Ayse" else "Emma"}") }
            OutlinedButton(onClick = { save(false) }, modifier = Modifier.fillMaxWidth()) { Text("Alleen opslaan") }
        }
    }
}

// ---------------------------------------------------------------- kaarten voor Vandaag

@Composable
fun DayRhythmCard(onCheck: (String) -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val m = Repo.checkin(today, "morning"); val e = Repo.checkin(today, "evening")
    val h = LocalTime.now().hour
    val showM = m == null && h < 14
    val showE = e == null && h >= 17
    var askWalk by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dagritme", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("☀️ ${if (m != null) "✓" else "–"}   🌙 ${if (e != null) "✓" else "–"}", fontSize = 13.sp, color = Color.DarkGray)
            }
            if (showM) {
                Text("Begin de dag met een korte check. Ayse maakt er je dagplan van.", fontSize = 13.sp, color = Color.DarkGray)
                Button(onClick = { onCheck("morning") }, modifier = Modifier.fillMaxWidth()) { Text("☀️ Ochtendcheck doen") }
            }
            if (showE) {
                Text("Sluit de dag af. Emma helpt je met één stap voor morgen.", fontSize = 13.sp, color = Color.DarkGray)
                Button(onClick = { onCheck("evening") }, modifier = Modifier.fillMaxWidth()) { Text("🌙 Avondcheck doen") }
            }
            if (!showM && !showE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onCheck("morning") }, label = { Text("☀️ Ochtendcheck" + if (m != null) " (opnieuw)" else "") })
                AssistChip(onClick = { onCheck("evening") }, label = { Text("🌙 Avondcheck" + if (e != null) " (opnieuw)" else "") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { askWalk = true }, modifier = Modifier.weight(1f)) { Text("🚶 Wandeling") }
                OutlinedButton(onClick = { onCheck("stretch") }, modifier = Modifier.weight(1f)) { Text("🧘 Lenigheid") }
            }
        }
    }
    if (askWalk) {
        var min by remember { mutableStateOf("30") }
        AlertDialog(
            onDismissRequest = { askWalk = false },
            title = { Text("Hoeveel minuten gewandeld?") },
            text = { OutlinedTextField(min, { min = it.filter(Char::isDigit) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = {
                TextButton(onClick = {
                    min.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
                        val t = System.currentTimeMillis()
                        Repo.add(Entry(t, t, "$n min gewandeld", null, emptyList(), listOf(Team.walkExercise(n)), null, "", kind = "walk"))
                        toast(ctx, "🚶 $n minuten genoteerd")
                    }
                    askWalk = false
                }) { Text("Opslaan") }
            },
            dismissButton = { TextButton(onClick = { askWalk = false }) { Text("Annuleren") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressCard(onAskNoor: () -> Unit, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    val p = Team.progress()
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mijn voortgang", fontWeight = FontWeight.Bold)
            if (p.start == null || p.goal == null) {
                Text("Vul je startgewicht en gewenste einddoel in (of importeer je profiel), dan rekent FitMaatje haalbare tussendoelen uit.", fontSize = 14.sp, color = Color.DarkGray)
                OutlinedButton(onClick = onProfile) { Text("Profiel instellen") }
            } else {
                val cur = p.current ?: p.start
                Text("${Team.fmt(cur)} kg${if (Repo.lastWeight() != null) " · laatst gelogd" else " · startgewicht"} · gewenst einddoel ${Team.fmt(p.goal)} kg")
                val pct = ((p.start - cur) / (p.start - p.goal)).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = Green, trackColor = GreenLight)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.milestones.forEach { m ->
                        val hit = cur <= m
                        Text((if (hit) "✓ " else "") + Team.fmt(m) + " kg", fontSize = 12.sp,
                            color = if (hit) Color.White else GreenDark,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(if (hit) Green else if (m == p.next) Amber.copy(alpha = 0.3f) else GreenLight)
                                .padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                Text(buildString {
                    if ((p.lost ?: 0.0) > 0) append("${p.lost} kg eraf sinds de start. ")
                    p.next?.let { append("Volgend tussendoel: ${Team.fmt(it)} kg (nog ${Math.round((cur - it) * 10) / 10.0} kg).") }
                    p.trend?.let { append(" Weektrend: ${if (it > 0) "+" else ""}$it kg.") }
                }, fontSize = 14.sp)
                Text("Kijk naar de trend over meerdere weken; een losse meting is geen oordeel. Gezond tempo: ongeveer 0,5–1 kg per week.", fontSize = 12.sp, color = Color.DarkGray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onAskNoor, label = { Text("📈 Vraag Noor") })
                AssistChip(onClick = { shareText(ctx, Team.overviewText()) }, label = { Text("Deel met ChatGPT") })
            }
        }
    }
}

// ---------------------------------------------------------------- coachteam

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachPicker(active: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        Team.COACHES.forEach { c ->
            FilterChip(selected = active == c.id, onClick = { onPick(c.id) },
                label = { Text("${c.emoji} ${c.name} · ${c.role}", fontSize = 12.sp) })
        }
    }
}

// ---------------------------------------------------------------- instellingen: profiel, import, ChatGPT

@Composable
fun ProfileCard() {
    var name by remember { mutableStateOf(Repo.name) }
    var height by remember { mutableStateOf(Repo.heightCm) }
    var start by remember { mutableStateOf(Repo.startWeight) }
    var goal by remember { mutableStateOf(Repo.goalWeight) }
    var date by remember { mutableStateOf(Repo.startDate) }
    var notes by remember { mutableStateOf(Repo.healthNotes) }
    var msg by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mijn profiel", fontWeight = FontWeight.Bold)
            Text("Alleen op deze telefoon bewaard — niet in de code of online.", fontSize = 12.sp, color = Color.DarkGray)
            OutlinedTextField(name, { name = it }, label = { Text("Naam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(height, { height = it }, label = { Text("Lengte (cm)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("Startgewicht (kg)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(goal, { goal = it }, label = { Text("Einddoel (kg)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            OutlinedTextField(date, { date = it }, label = { Text("Startdatum (JJJJ-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Gezondheid en aandachtspunten (bijv. zoete trek)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            Button(onClick = {
                val ok = runCatching { if (date.isNotBlank()) LocalDate.parse(date.trim()) }.isSuccess
                if (!ok) { msg = "Startdatum als 2026-10-13 invullen."; return@Button }
                Repo.name = name.ifBlank { "Nick" }; Repo.heightCm = height; Repo.startWeight = start; Repo.goalWeight = goal
                Repo.startDate = date; Repo.healthNotes = notes; msg = "Opgeslagen ✓"
            }, modifier = Modifier.fillMaxWidth()) { Text("Profiel opslaan") }
            if (msg.isNotBlank()) Text(msg, color = Green, fontSize = 13.sp)
        }
    }
}

@Composable
fun ImportCard(onImported: () -> Unit) {
    val ctx = LocalContext.current
    var raw by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Team.Import?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    fun check(t: String) {
        raw = t
        if (t.isBlank()) { preview = null; err = null; return }
        runCatching { Team.parse(t) }.onSuccess { preview = it; err = null }.onFailure { preview = null; err = it.message }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            .onSuccess { check(it) }.onFailure { err = "Bestand lezen mislukt: ${it.message}" }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Importeren", fontWeight = FontWeight.Bold)
            Text("Profielbestand, een ${Team.IMPORT_TAG}-blok uit ChatGPT of een back-up. Er wordt alleen toegevoegd; bestaande gegevens blijven staan.",
                fontSize = 12.sp, color = Color.DarkGray)
            OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("📄 Bestand kiezen") }
            OutlinedTextField(raw, { check(it) }, label = { Text("…of plak hier") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), maxLines = 6)
            err?.let { Text("⚠️ $it", color = Red, fontSize = 13.sp) }
            preview?.let { p ->
                Text(Team.describe(p), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Button(onClick = {
                    Team.apply(p); raw = ""; preview = null
                    toast(ctx, "Geïmporteerd ✓ — bestaande gegevens bewaard"); onImported()
                }, modifier = Modifier.fillMaxWidth()) { Text("Toevoegen") }
            }
        }
    }
}

@Composable
fun ChatGptCard() {
    val ctx = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Repo.exportJson().toByteArray()) } }
            .onSuccess { toast(ctx, "Back-up opgeslagen ✓") }.onFailure { toast(ctx, "Opslaan mislukt") }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Samenwerken met ChatGPT", fontWeight = FontWeight.Bold)
            Text("ChatGPT kan FitMaatje niet zelf lezen of aanpassen. Zo wissel je in een paar tikken uit:", fontSize = 13.sp, color = Color.DarkGray)
            Text("1. Eenmalig: kopieer de instructie en plak die in je ChatGPT-project (Projectinstructies).\n" +
                "2. FitMaatje → ChatGPT: tik op Deel overzicht en kies de ChatGPT-app.\n" +
                "3. ChatGPT → FitMaatje: kopieer het ${Team.IMPORT_TAG}-blok onder ChatGPT's antwoord en plak het bij Importeren.", fontSize = 13.sp)
            OutlinedButton(onClick = { copyText(ctx, Team.chatgptInstruction(), "Instructie gekopieerd ✓") }, modifier = Modifier.fillMaxWidth()) { Text("1 · Instructie voor ChatGPT kopiëren") }
            Button(onClick = { shareText(ctx, Team.overviewText()) }, modifier = Modifier.fillMaxWidth()) { Text("2 · Deel overzicht met ChatGPT") }
            OutlinedButton(onClick = { save.launch("fitmaatje-backup-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("💾 Back-up opslaan") }
        }
    }
}
