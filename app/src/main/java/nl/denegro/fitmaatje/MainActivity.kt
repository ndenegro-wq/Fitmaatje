package nl.denegro.fitmaatje

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Green = Color(0xFF2E7D32)
private val GreenDark = Color(0xFF1B5E20)
private val GreenLight = Color(0xFFE8F5E9)
private val Amber = Color(0xFFF9A825)
private val Red = Color(0xFFC62828)

private val scheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenLight,
    onPrimaryContainer = GreenDark,
    secondary = Color(0xFF558B2F),
    background = Color(0xFFF4F7F2),
    surface = Color.White,
)

class MainActivity : ComponentActivity() {
    private val speakRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        if (intent?.getBooleanExtra("speak", false) == true) speakRequest.intValue++
        setContent {
            MaterialTheme(colorScheme = scheme) {
                FitApp(speakRequest.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("speak", false)) speakRequest.intValue++
    }

    override fun onResume() {
        super.onResume()
        // Restart listening after a reboot / app update if it was switched on.
        if (Repo.listening && !ListenService.running && hasMic()) runCatching { ListenService.start(this) }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun FitApp(speakRequest: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var autoSpeak by remember { mutableIntStateOf(0) }
    var autoPhoto by remember { mutableIntStateOf(0) }
    LaunchedEffect(speakRequest) {
        if (speakRequest > 0) { tab = 2; autoSpeak = speakRequest }
    }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf("🏠" to "Vandaag", "🍽" to "Schema", "🎤" to "Inspreken", "💬" to "Coach", "⚙️" to "Instellingen")
                    .forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(icon, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(onSpeak = { tab = 2; autoSpeak++ }, onPhoto = { tab = 2; autoPhoto++ })
                1 -> PlanScreen()
                2 -> SpeakScreen(autoSpeak, autoPhoto)
                3 -> CoachScreen()
                else -> SettingsScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- Vandaag

@Composable
fun TodayScreen(onSpeak: () -> Unit, onPhoto: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    val entries = Repo.entries.filter { it.date == date }
    val s = Repo.sum(date)
    val target = Repo.kcalTarget
    val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl"))

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("◀") }
                Text(
                    if (date == LocalDate.now()) "Vandaag" else date.format(fmt).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                TextButton(onClick = { if (date < LocalDate.now()) date = date.plusDays(1) }) { Text("▶") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    val left = target - s.kcal
                    Text("${s.kcal} / $target kcal", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = GreenDark)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (s.kcal.toFloat() / target).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = if (s.kcal > target) Red else if (s.kcal > target * 0.9) Amber else Green,
                        trackColor = GreenLight,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (left >= 0) "Nog $left kcal over" else "${-left} kcal boven je doel",
                        color = if (left >= 0) Color.DarkGray else Red,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Eiwit", "${s.protein}/${Repo.proteinTarget} g", Modifier.weight(1f))
                        Stat("Sport", "${s.sportMin} min", Modifier.weight(1f))
                        Stat("Verbrand", "${s.sportKcal} kcal", Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Eetmomenten", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Repo.moments.forEachIndexed { i, m ->
                            val done = (i + 1) in s.moments
                            val isNow = date == LocalDate.now() && Repo.momentAt(LocalTime.now()) == i + 1
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (done) Green else if (isNow) Amber.copy(alpha = 0.25f) else GreenLight)
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("${i + 1}", fontWeight = FontWeight.Bold, color = if (done) Color.White else GreenDark)
                                Text(m.format(HM), fontSize = 11.sp, color = if (done) Color.White else Color.DarkGray)
                            }
                        }
                    }
                    Repo.lastWeight()?.let { (d, w) ->
                        Spacer(Modifier.height(10.dp))
                        Text("Laatste gewicht: $w kg (${d.format(DateTimeFormatter.ofPattern("d MMM", Locale("nl")))})", color = Color.DarkGray)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSpeak, modifier = Modifier.weight(2f).height(56.dp)) {
                    Text("🎤  Inspreken", fontSize = 16.sp)
                }
                Button(onClick = onPhoto, modifier = Modifier.weight(1.4f).height(56.dp)) {
                    Text("📷  Foto", fontSize = 16.sp)
                }
            }
        }
        if (entries.isEmpty()) item {
            Text("Nog niets gelogd.", color = Color.Gray, modifier = Modifier.padding(8.dp))
        }
        items(entries.reversed(), key = { it.id }) { e -> EntryCard(e) }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(GreenLight).padding(8.dp)) {
        Text(label, fontSize = 12.sp, color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, color = GreenDark)
    }
}

@Composable
fun EntryCard(e: Entry) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.time, fontWeight = FontWeight.Bold, color = GreenDark)
                Spacer(Modifier.width(8.dp))
                val tag = when {
                    e.foods.isNotEmpty() && e.moment != null -> "Moment ${e.moment}"
                    e.foods.isNotEmpty() -> "Buiten schema"
                    e.exercises.isNotEmpty() -> "Sport"
                    e.weight != null -> "Gewicht"
                    else -> "Notitie"
                }
                Text(
                    tag, fontSize = 12.sp,
                    color = if (tag == "Buiten schema") Red else GreenDark,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (tag == "Buiten schema") Red.copy(alpha = 0.1f) else GreenLight)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                if (e.kcal > 0) Text("${e.kcal} kcal", fontWeight = FontWeight.Bold)
            }
            e.photo?.let { p ->
                Photos.thumb(p)?.let { img ->
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.Image(img, contentDescription = "Foto",
                        modifier = Modifier.fillMaxWidth().height(if (expanded) 260.dp else 120.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Spacer(Modifier.height(6.dp))
                }
            }
            e.foods.forEach { f ->
                Text("• ${f.name}${if (f.amount.isNotBlank()) " (${f.amount})" else ""} — ${f.kcal} kcal, ${f.protein} g eiwit", fontSize = 14.sp)
            }
            e.exercises.forEach { x ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏋 ${x.name} ${x.detail} — ${x.minutes} min, ~${x.kcal} kcal", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { openVideo(ctx, "${x.name} techniek uitleg") }) { Text("▶ Voorbeeld", fontSize = 12.sp) }
                }
            }
            e.weight?.let { Text("⚖️ $it kg", fontSize = 14.sp) }
            if (e.reply.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(e.reply, fontSize = 14.sp, color = Color(0xFF33691E))
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text("Ingesproken: “${e.text}”", fontSize = 12.sp, color = Color.Gray)
                TextButton(onClick = { confirm = true }) { Text("Verwijderen", color = Red) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { Repo.delete(e); confirm = false }) { Text("Verwijderen") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuleren") } },
        title = { Text("Regel verwijderen?") },
        text = { Text(e.text.take(120)) },
    )
}

// ---------------------------------------------------------------- Schema

@Composable
fun PlanScreen() {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(Plans.defaultDate()) }
    val plan = Plans.plans[date]
    val busy = Plans.busy.value
    var error by Plans.error
    var wish by rememberSaveable { mutableStateOf("") }
    var showShop by remember { mutableStateOf(false) }
    val done = Repo.sum(date).moments
    val ctx = LocalContext.current
    var wishListening by remember { mutableStateOf(false) }
    val wishDictation = remember {
        Dictation(ctx, onFinal = { t -> wish = if (wish.isBlank()) t else "$wish $t" }, onPartial = {},
            onState = { wishListening = it }, onError = { error = it })
    }
    DisposableEffect(Unit) { onDispose { wishDictation.release() } }
    val startWishMic = rememberMicPermission { wishDictation.start() }
    val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl"))

    fun run(label: String, block: () -> Unit) = Plans.launch(label, block)
    val open = Plans.openMoments(date)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("◀") }
                Text(
                    when (date) {
                        LocalDate.now() -> "Eetschema vandaag"
                        LocalDate.now().plusDays(1) -> "Eetschema morgen"
                        else -> date.format(fmt).replaceFirstChar { it.uppercase() }
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                TextButton(onClick = { if (date < LocalDate.now().plusDays(1)) date = date.plusDays(1) }) { Text("▶") }
            }
        }
        if (Repo.apiKey.isBlank()) item {
            Text("Stel eerst je API-sleutel in bij Instellingen, dan maakt de coach elke dag je schema.", color = Red)
        }
        busy?.let { b ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("$b (duurt ±30 sec, je kunt intussen iets anders doen)")
                }
            }
        }
        if (plan == null && busy == null) item {
            Text(
                if (date == LocalDate.now() && open.isEmpty()) "De eetmomenten van vandaag zijn voorbij. Tik op ▶ voor morgen."
                else if (date == LocalDate.now() && open.size < Repo.moments.size)
                    "Ik plan alleen de momenten die nog komen (" + open.joinToString(", ") { it.second.format(HM) } + ") en houd rekening met wat je al at."
                else "Nog geen schema voor deze dag. Geef eventueel een wens op en tik op Maak schema.",
                color = Color.DarkGray,
            )
        }
        error?.let { item { Text(it, color = Red) } }

        if (plan != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(plan.theme.ifBlank { "Dagmenu" }, fontWeight = FontWeight.Bold, color = GreenDark, fontSize = 18.sp)
                        Text("${plan.kcal} kcal · ${plan.protein} g eiwit · ${done.size}/${plan.meals.size} gegeten", color = Color.DarkGray)
                        if (plan.tip.isNotBlank()) {
                            Spacer(Modifier.height(6.dp)); Text("💡 ${plan.tip}", fontSize = 14.sp)
                        }
                    }
                }
            }
            items(plan.meals, key = { "${plan.date}-${it.moment}-${it.title}" }) { m ->
                val eaten = m.moment in done
                Card(colors = CardDefaults.cardColors(containerColor = if (eaten) GreenLight else Color.White)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${m.moment} · ${m.time}", fontWeight = FontWeight.Bold, color = GreenDark)
                            Spacer(Modifier.width(8.dp))
                            Text(m.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${m.kcal} kcal", fontWeight = FontWeight.Bold)
                        }
                        m.foods.forEach { f ->
                            Text("• ${f.name} — ${f.amount}  (${f.kcal} kcal, ${f.protein} g eiwit)", fontSize = 14.sp)
                        }
                        if (m.note.isNotBlank()) Text(m.note, fontSize = 13.sp, color = Color(0xFF33691E))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            if (eaten) Text("✓ Gegeten", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                            else if (date == LocalDate.now()) Button(onClick = {
                                val now = System.currentTimeMillis()
                                Repo.add(Entry(now, now, "Volgens schema: ${m.title}", m.moment, m.foods, emptyList(), null,
                                    "Volgens schema gegeten. Nog ${Repo.kcalTarget - Repo.sum(date).kcal - m.kcal} kcal over vandaag."))
                            }) { Text("✓ Gegeten") }
                            OutlinedButton(onClick = {
                                wishDictation.stop()
                                run("Ander voorstel voor moment ${m.moment}…") { Plans.swap(plan, m.moment, wish) }
                            }, enabled = busy == null && !eaten) { Text("↻ Iets anders") }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showShop = !showShop }, modifier = Modifier.weight(1f)) {
                        Text(if (showShop) "Verberg lijst" else "🛒 Boodschappen")
                    }
                }
                if (showShop) Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.padding(top = 8.dp)) {
                    Text(Plans.shoppingList(plan), modifier = Modifier.padding(14.dp), fontSize = 14.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (plan == null) "Schema maken" else "Heel nieuw schema", fontWeight = FontWeight.Bold)
                    OutlinedTextField(wish, { wish = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Wens (optioneel), bijv. 'vandaag vis', 'uit eten 's avonds'") },
                        trailingIcon = {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(if (wishListening) Red else GreenLight)
                                    .clickable { if (wishListening) wishDictation.stop() else startWishMic() },
                                contentAlignment = Alignment.Center,
                            ) { Text(if (wishListening) "■" else "🎤", color = if (wishListening) Color.White else GreenDark) }
                        })
                    if (wishListening) Text("Ik luister… vertel je wens en tik op ■", fontSize = 12.sp, color = Color.DarkGray)
                    Button(onClick = { wishDictation.stop(); run("Schema maken…") { Plans.generate(date, wish) } },
                        enabled = busy == null && Repo.apiKey.isNotBlank() && !date.isBefore(LocalDate.now()) && open.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) { Text(if (plan == null) "Maak schema" else "Maak nieuw schema") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Inspreken

@Composable
fun rememberMicPermission(onGranted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) onGranted()
    }
    return {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) onGranted() else launcher.launch(missing.toTypedArray())
    }
}

@Composable
fun SpeakScreen(autoSpeak: Int, autoPhoto: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<Entry?>(null) }

    val dictation = remember {
        Dictation(
            ctx,
            onFinal = { t -> text = if (text.isBlank()) t else "$text $t" },
            onPartial = { partial = it },
            onState = { listening = it },
            onError = { error = it },
        )
    }
    DisposableEffect(Unit) { onDispose { dictation.release() } }
    val startMic = rememberMicPermission { error = null; dictation.start() }
    LaunchedEffect(autoSpeak) { if (autoSpeak > 0) startMic() }

    var photoPath by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val t = text.trim()
        val photo = photoPath
        if ((t.isEmpty() && photo == null) || busy) return
        dictation.stop()
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Coach.process(t, photoPath = photo) } }
            busy = false
            r.onSuccess { e -> Repo.add(e); last = e; text = ""; photoPath = null }
                .onFailure { error = it.message ?: "Er ging iets mis" }
        }
    }

    fun gotPhoto(uri: android.net.Uri) {
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Photos.store(ctx, uri) } }
            busy = false
            r.onSuccess { photoPath = it; submit() }.onFailure { error = "Foto mislukt: ${it.message}" }
        }
    }

    var cameraTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraTarget
        if (ok && u != null) gotPhoto(u)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) gotPhoto(u)
    }
    fun openCamera() {
        dictation.stop()
        runCatching {
            val (_, uri) = Photos.newCameraTarget(ctx)
            cameraTarget = uri
            camera.launch(uri)
        }.onFailure { error = "Camera openen lukte niet: ${it.message}" }
    }
    LaunchedEffect(autoPhoto) { if (autoPhoto > 0) openCamera() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Wat ga je eten of doen?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Praat zo lang als je wilt, of maak een foto van je bord. Je kunt ook eerst iets inspreken (bijv. 'half opgegeten') en dan de foto maken.",
            color = Color.DarkGray, textAlign = TextAlign.Center, fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.size(140.dp).clip(CircleShape)
                .background(if (listening) Red else Green)
                .clickable { if (listening) dictation.stop() else startMic() },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (listening) "■" else "🎤", fontSize = if (listening) 48.sp else 56.sp, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (listening) "Ik luister… tik om te stoppen" else "Tik om in te spreken", color = Color.DarkGray)
        if (partial.isNotBlank()) Text(partial, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { openCamera() }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text("📷  Foto maken") }
            OutlinedButton(onClick = {
                dictation.stop()
                gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text("🖼  Uit galerij") }
        }
        photoPath?.let { p ->
            Photos.thumb(p)?.let { img ->
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.Image(img, contentDescription = "Foto van je eten",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Text(if (photoPath != null) "Foto bekijken en calorieën schatten…" else "Bezig…", color = Color.DarkGray)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Jouw tekst (kun je aanpassen of typen)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { text = ""; partial = ""; photoPath = null }, modifier = Modifier.weight(1f)) { Text("Wissen") }
            Button(onClick = { submit() }, enabled = (text.isNotBlank() || photoPath != null) && !busy, modifier = Modifier.weight(2f)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Opslaan & berekenen")
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Red, textAlign = TextAlign.Center)
        }
        last?.let {
            Spacer(Modifier.height(16.dp))
            Text("Genoteerd", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            EntryCard(it)
        }
    }
}

// ---------------------------------------------------------------- Coach

@Composable
fun CoachScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val dictation = remember {
        Dictation(ctx, onFinal = { t -> input = if (input.isBlank()) t else "$input $t" }, onPartial = {},
            onState = { listening = it }, onError = { Repo.addChat(ChatMsg(false, it)) })
    }
    DisposableEffect(Unit) { onDispose { dictation.release() } }
    val startMic = rememberMicPermission { dictation.start() }
    LaunchedEffect(Repo.chat.size) { if (Repo.chat.isNotEmpty()) listState.animateScrollToItem(Repo.chat.size - 1) }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        dictation.stop()
        input = ""
        Repo.addChat(ChatMsg(true, q))
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Coach.ask(q) } }
            busy = false
            Repo.addChat(ChatMsg(false, r.getOrElse { it.message ?: "Er ging iets mis" }))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Coach", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { Repo.clearChat() }) { Text("Wissen") }
        }
        if (Repo.chat.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vraag wat je wilt. Bijvoorbeeld:", color = Color.DarkGray)
                listOf(
                    "Wat kan ik vanavond nog eten met wat ik over heb?",
                    "Geef me een calisthenics-training van 20 minuten met voorbeelden.",
                    "Welke oefeningen kan ik thuis doen zonder spullen?",
                    "Hoe ging mijn week?",
                ).forEach { s ->
                    AssistChip(onClick = { input = s }, label = { Text(s) })
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(Repo.chat) { m ->
                val (body, videos) = if (m.fromMe) m.text to emptyList() else splitVideos(m.text)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (m.fromMe) Green else Color.White).padding(12.dp)
                    ) {
                        Text(body, color = if (m.fromMe) Color.White else Color.Black)
                        if (videos.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Bekijk hoe het moet:", fontSize = 12.sp, color = Color.DarkGray)
                            videos.forEach { v ->
                                OutlinedButton(
                                    onClick = { openVideo(ctx, v.query) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) { Text("▶  ${v.name}", modifier = Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
            if (busy) item { Text("Coach denkt na…", color = Color.Gray, modifier = Modifier.padding(8.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (listening) Red else GreenLight)
                    .clickable { if (listening) dictation.stop() else startMic() },
                contentAlignment = Alignment.Center,
            ) { Text(if (listening) "■" else "🎤", color = if (listening) Color.White else GreenDark) }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Typ of spreek je vraag") }, maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = input.isNotBlank() && !busy) { Text("➤") }
        }
    }
}

// ---------------------------------------------------------------- Instellingen

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var listening by remember { mutableStateOf(Repo.listening && ListenService.running) }
    var sens by remember { mutableFloatStateOf(Repo.sensitivity.toFloat()) }
    var kw by remember { mutableStateOf(Repo.keywords) }
    var hf by remember { mutableStateOf(Repo.handsfree) }
    var apiKey by remember { mutableStateOf(Repo.apiKey) }
    var model by remember { mutableStateOf(Repo.model) }
    var name by remember { mutableStateOf(Repo.name) }
    var kcal by remember { mutableStateOf(Repo.kcalTarget.toString()) }
    var prot by remember { mutableStateOf(Repo.proteinTarget.toString()) }
    var moments by remember { mutableStateOf(Repo.momentsCsv) }
    var protocol by remember { mutableStateOf(Repo.protocol) }
    var foodPrefs by remember { mutableStateOf(Repo.foodPrefs) }
    var qs by remember { mutableStateOf(Repo.quietStart.toString()) }
    var qe by remember { mutableStateOf(Repo.quietEnd.toString()) }
    var reminders by remember { mutableStateOf(Repo.reminders) }
    var saved by remember { mutableStateOf(false) }

    val enableListening = rememberMicPermission {
        Repo.listening = true
        runCatching { ListenService.start(ctx) }
        listening = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Instellingen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Meeluisteren", fontWeight = FontWeight.Bold)
                        Text("Hoort eetgeluiden en wat je zegt. Alles wordt op je telefoon herkend, nooit opgenomen. Alleen bij handsfree loggen gaat de tekst (niet het geluid) naar de coach.",
                            fontSize = 12.sp, color = Color.DarkGray)
                    }
                    Switch(checked = listening, onCheckedChange = { on ->
                        if (on) enableListening() else { Repo.listening = false; ListenService.stop(ctx); listening = false }
                    })
                }
                Text("Status: ${ListenService.status.value}", fontSize = 13.sp)
                Text("Hoort nu: ${ListenService.heard.value}", fontSize = 13.sp, color = Color.DarkGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reageren als ik iets zeg", fontWeight = FontWeight.Medium)
                        Text("Ping + pop-up bij o.a. “ik ga eten”, “honger”, “trek”, “lunch”, “ontbijt”, “snack”, “sporten”.",
                            fontSize = 12.sp, color = Color.DarkGray)
                    }
                    Switch(checked = kw, onCheckedChange = { kw = it; Repo.keywords = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Handsfree loggen", fontWeight = FontWeight.Medium)
                        Text("Na de ping gewoon doorpraten (“…twee boterhammen met kaas”). FitMaatje noteert het zelf.",
                            fontSize = 12.sp, color = Color.DarkGray)
                    }
                    Switch(checked = hf, onCheckedChange = { hf = it; Repo.handsfree = it })
                }
                Text("Gevoeligheid eetgeluiden: " + when (sens.toInt()) { 1 -> "laag (minder valse meldingen)"; 2 -> "normaal"; else -> "hoog (mist minder)" }, fontSize = 13.sp)
                Slider(value = sens, onValueChange = { sens = it; Repo.sensitivity = it.toInt() }, valueRange = 1f..3f, steps = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qs, { qs = it.filter(Char::isDigit).take(2) }, label = { Text("Nachtrust vanaf (uur)") },
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(qe, { qe = it.filter(Char::isDigit).take(2) }, label = { Text("tot (uur)") },
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedButton(onClick = { Notifs.eatingDetected(ctx) }) { Text("Testmelding: 'eten gehoord'") }
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Batterijbesparing uitzetten voor FitMaatje") }
                Text("Tip (Samsung): zet FitMaatje bij Batterij op 'Onbeperkt', anders stopt meeluisteren soms.", fontSize = 12.sp, color = Color.DarkGray)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Coach (Claude)", fontWeight = FontWeight.Bold)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Anthropic API-sleutel (sk-ant-…)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Sleutel aanmaken op console.anthropic.com") }
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jouw plan", fontWeight = FontWeight.Bold)
                OutlinedTextField(name, { name = it }, label = { Text("Naam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(kcal, { kcal = it.filter(Char::isDigit) }, label = { Text("kcal per dag") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(prot, { prot = it.filter(Char::isDigit) }, label = { Text("eiwit (g)") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(moments, { moments = it }, label = { Text("Eetmomenten (bijv. 07:30,10:00,12:30)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(protocol, { protocol = it }, label = { Text("Afspraken met je coach / protocol") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                OutlinedTextField(foodPrefs, { foodPrefs = it }, label = { Text("Wat ik lekker vind / niet eet (voor het eetschema)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Herinneringen (ochtend, eetmomenten, avond)", modifier = Modifier.weight(1f))
                    Switch(checked = reminders, onCheckedChange = { reminders = it })
                }
            }
        }

        Button(onClick = {
            Repo.apiKey = apiKey; Repo.model = model.ifBlank { "claude-sonnet-5-5" }; Repo.name = name.ifBlank { "Nick" }
            Repo.kcalTarget = kcal.toIntOrNull() ?: 1560; Repo.proteinTarget = prot.toIntOrNull() ?: 130
            Repo.momentsCsv = moments; Repo.protocol = protocol; Repo.foodPrefs = foodPrefs
            Repo.quietStart = (qs.toIntOrNull() ?: 23).coerceIn(0, 23); Repo.quietEnd = (qe.toIntOrNull() ?: 7).coerceIn(0, 23)
            Repo.reminders = reminders
            Reminders.scheduleNext(ctx)
            saved = true
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Opslaan") }
        if (saved) Text("Opgeslagen ✓", color = Green)
        Spacer(Modifier.height(24.dp))
    }
}
