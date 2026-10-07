package nl.denegro.fitmaatje

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.time.LocalTime

/**
 * Foreground service that listens to ambient sound and recognises eating sounds
 * entirely on the phone (YAMNet via MediaPipe). No audio is stored or sent anywhere:
 * only sound-category names and scores are used.
 */
class ListenService : Service() {

    companion object {
        const val ACTION_PAUSE = "nl.denegro.fitmaatje.PAUSE"
        const val ACTION_RESUME = "nl.denegro.fitmaatje.RESUME"
        const val ACTION_STOP = "nl.denegro.fitmaatje.STOP"
        const val ACTION_NO = "nl.denegro.fitmaatje.NO"

        @Volatile var running = false
        /** Set by the dictation screen so the speech recogniser can use the microphone. */
        @Volatile var holdMic = false
        @Volatile var pausedUntil = 0L

        /** Live debug info for the settings screen. */
        val heard = mutableStateOf("—")
        val status = mutableStateOf("Uit")

        /** Sound categories (YAMNet / AudioSet names) and how strongly they point to eating. */
        val WEIGHTS = mapOf(
            "Chewing, mastication" to 1.0,
            "Biting" to 1.0,
            "Cutlery, silverware" to 0.8,
            "Dishes, pots, and pans" to 0.6,
            "Crumpling, crinkling" to 0.5,
            "Microwave oven" to 0.5,
            "Frying (food)" to 0.4,
            "Chopping (food)" to 0.4,
            "Gulp, gulping" to 0.3,
        )

        val NL = mapOf(
            "Chewing, mastication" to "kauwen",
            "Biting" to "bijten",
            "Cutlery, silverware" to "bestek",
            "Dishes, pots, and pans" to "borden/pannen",
            "Crumpling, crinkling" to "ritselen (verpakking)",
            "Microwave oven" to "magnetron",
            "Frying (food)" to "bakken",
            "Chopping (food)" to "snijden",
            "Gulp, gulping" to "slikken",
            "Speech" to "praten",
            "Silence" to "stilte",
            "Music" to "muziek",
            "Television" to "tv",
            "Inside, small room" to "binnen",
        )

        /** Words/phrases that make FitMaatje react when you say them. */
        val KEYWORDS = listOf(
            "eten", "honger", "trek", "lunch", "lunchen", "ontbijt", "ontbijten", "avondeten",
            "snack", "tussendoortje", "hapje", "maaltijd", "dineren", "sporten", "trainen", "workout",
        )
        private val GRAMMAR = org.json.JSONArray(
            KEYWORDS + listOf(
                "ik ga eten", "ik ga nu eten", "ik heb honger", "ik heb trek", "ik ga lunchen",
                "ik ga ontbijten", "ik ga sporten", "ik ga trainen", "[unk]",
            )
        ).toString()

        fun start(c: Context) {
            pausedUntil = 0
            ContextCompat.startForegroundService(c, Intent(c, ListenService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ListenService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Repo.listening = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pausedUntil = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
                NotificationManagerCompat.from(this).cancel(Notifs.ID_EAT)
                refreshNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                pausedUntil = 0
                refreshNotification()
                return START_STICKY
            }
            ACTION_NO -> {
                NotificationManagerCompat.from(this).cancel(Notifs.ID_EAT)
                return START_STICKY
            }
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, Notifs.ID_LISTEN, Notifs.listening(this, statusText()), type)
        } catch (e: Exception) {
            // Android refuses a microphone service started from the background.
            setStatus("Kon niet starten: open de app en zet meeluisteren opnieuw aan")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (worker == null) {
            alive = true
            worker = Thread({ loop() }, "fitmaatje-listen").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        worker?.interrupt()
        worker = null
        setStatus("Uit")
        super.onDestroy()
    }

    private fun statusText(): String {
        val now = System.currentTimeMillis()
        return when {
            now < pausedUntil -> "Gepauzeerd tot " + java.time.Instant.ofEpochMilli(pausedUntil)
                .atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(HM)
            inQuietHours() -> "Nachtrust tot ${"%02d".format(Repo.quietEnd)}:00"
            else -> "Ik hoor het als je gaat eten. Geluid blijft op je telefoon."
        }
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(Notifs.ID_LISTEN, Notifs.listening(this, statusText())) }
        setStatus(statusText())
    }

    private fun setStatus(s: String) = main.post { status.value = s }
    private fun setHeard(s: String) = main.post { heard.value = s }

    private fun inQuietHours(): Boolean {
        val h = LocalTime.now().hour
        val s = Repo.quietStart; val e = Repo.quietEnd
        return if (s == e) false else if (s < e) h in s until e else (h >= s || h < e)
    }

    @SuppressLint("MissingPermission")
    private fun newRecord(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, 16000 * 4 * 2)
        )
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    } catch (e: Exception) { null }

    private fun loop() {
        val classifier = try {
            val base = BaseOptions.builder().setModelAssetPath("yamnet.tflite").build()
            val opts = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setMaxResults(10)
                .setScoreThreshold(0.05f)
                .build()
            AudioClassifier.createFromOptions(this, opts)
        } catch (e: Throwable) {
            setStatus("Geluidsmodel laden mislukt: ${e.message}")
            return
        }

        prepareVosk()
        val win = 15600 // 0.975 s at 16 kHz, YAMNet's native window
        val buf = FloatArray(win)
        val format = AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(16000f).build()
        val hits = ArrayDeque<Long>()
        var lastTrigger = 0L
        var record: AudioRecord? = null
        var lastStatus = ""

        try {
            while (alive) {
                val now = System.currentTimeMillis()
                val st = statusText()
                if (st != lastStatus) { lastStatus = st; refreshNotification() }

                if (holdMic || now < pausedUntil || inQuietHours()) {
                    record?.let { runCatching { it.stop() }; it.release() }
                    record = null
                    if (holdMic) setHeard("(even stil: je bent aan het inspreken)")
                    Thread.sleep(1000)
                    continue
                }
                if (record == null) {
                    record = newRecord()
                    if (record == null) {
                        setStatus("Microfoon niet beschikbaar (in gebruik door andere app?)")
                        Thread.sleep(5000)
                        continue
                    }
                    record.startRecording()
                }

                var filled = 0
                while (filled < win && alive && !holdMic) {
                    val n = record.read(buf, filled, win - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < win) continue

                // ---- Spoken keywords ("ik ga eten", "honger", ...) via offline Vosk ----
                if (vosk != null && Repo.keywords) {
                    for (i in 0 until win) sbuf[i] = (buf[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                    val cap = capture
                    if (cap != null) {
                        if (cap.acceptWaveForm(sbuf, win)) {
                            val t = JSONObject(cap.result).optString("text").trim()
                            if (t.isNotEmpty()) { captured.append(t).append(' '); spokeAfter = true }
                            if (spokeAfter && now - captureStart > 3500) finishCapture()
                        } else {
                            val p = JSONObject(cap.partialResult).optString("partial").trim()
                            if (p.isNotEmpty()) setHeard("🎙 $p")
                        }
                        if (capture != null && (now - captureStart > 25_000 || (!spokeAfter && now - captureStart > 9_000))) finishCapture()
                        continue
                    }
                    val k = kwRec
                    if (k != null && k.acceptWaveForm(sbuf, win)) {
                        val t = JSONObject(k.result).optString("text").replace("[unk]", "").trim()
                        if (t.isNotEmpty()) {
                            setHeard("gezegd: “$t”")
                            if (KEYWORDS.any { t.contains(it) } && now - lastKeyword > 45_000) {
                                lastKeyword = now
                                onKeyword(t)
                            }
                        }
                    }
                }

                val data = AudioData.create(format, win)
                data.load(buf)
                val result = classifier.classify(data)
                val cats = result.classificationResults().firstOrNull()
                    ?.classifications()?.firstOrNull()?.categories() ?: emptyList()

                var score = 0.0
                cats.forEach { c -> WEIGHTS[c.categoryName()]?.let { w -> score += w * c.score() } }
                setHeard(cats.take(3).joinToString("  ·  ") { c ->
                    (NL[c.categoryName()] ?: c.categoryName()) + " " + (c.score() * 100).toInt() + "%"
                })

                val sens = Repo.sensitivity.coerceIn(1, 3)
                val threshold = when (sens) { 1 -> 0.35; 2 -> 0.22; else -> 0.12 }
                val needed = when (sens) { 1 -> 5; 2 -> 4; else -> 3 }
                if (score >= threshold) hits.addLast(now)
                while (hits.isNotEmpty() && now - hits.first() > 60_000) hits.removeFirst()

                if (hits.size >= needed && now - lastTrigger > 25 * 60_000L) {
                    lastTrigger = now
                    hits.clear()
                    main.post { Notifs.eatingDetected(this) }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            setStatus("Fout: ${e.message}")
        } finally {
            record?.let { runCatching { it.stop() }; it.release() }
            runCatching { classifier.close() }
            runCatching { capture?.close() }; capture = null
            runCatching { kwRec?.close() }; kwRec = null
            runCatching { vosk?.close() }; vosk = null
        }
    }

    // ------------------------------------------------------------------ keywords

    private var vosk: Model? = null
    private var kwRec: Recognizer? = null
    private var capture: Recognizer? = null
    private val sbuf = ShortArray(15600)
    private val captured = StringBuilder()
    private var captureStart = 0L
    private var spokeAfter = false
    private var lastKeyword = 0L

    private fun prepareVosk() {
        try {
            val dir = File(filesDir, "vosk-nl")
            val marker = File(dir, ".ok-1")
            if (!marker.exists()) {
                setStatus("Spraakmodel installeren (eenmalig)…")
                dir.deleteRecursively()
                copyAssets("model-nl", dir)
                marker.writeText("ok")
            }
            val m = Model(dir.absolutePath)
            vosk = m
            kwRec = Recognizer(m, 16000f, GRAMMAR)
        } catch (e: Throwable) {
            vosk = null
            setStatus("Spraakwoorden niet beschikbaar: ${e.message}")
        }
    }

    private fun copyAssets(path: String, out: File) {
        val list = assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            out.parentFile?.mkdirs()
            assets.open(path).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } else {
            out.mkdirs()
            list.forEach { copyAssets("$path/$it", File(out, it)) }
        }
    }

    private fun onKeyword(said: String) {
        ping()
        val m = vosk
        if (Repo.handsfree && Repo.apiKey.isNotBlank() && m != null) {
            capture = Recognizer(m, 16000f)
            captureStart = System.currentTimeMillis()
            captured.setLength(0)
            captured.append(said).append(' ')
            spokeAfter = false
            main.post { Notifs.capturing(this, said) }
        } else {
            main.post { Notifs.keywordHeard(this, said) }
        }
    }

    private fun finishCapture() {
        val cap = capture ?: return
        capture = null
        runCatching {
            val t = JSONObject(cap.finalResult).optString("text").trim()
            if (t.isNotEmpty()) { captured.append(t); spokeAfter = true }
        }
        runCatching { cap.close() }
        kwRec?.reset()
        val text = captured.toString().trim()
        val said = spokeAfter
        if (!said) {
            main.post { Notifs.keywordHeard(this, text) }
            return
        }
        ping(double = true)
        setHeard("verwerken: “$text”")
        Thread({
            try {
                val e = Coach.process("$text (handsfree ingesproken)")
                Repo.add(e)
                main.post { Notifs.logged(this, e) }
            } catch (ex: Throwable) {
                main.post { Notifs.remind(this, "Niet gelukt om te loggen", "“$text” — ${ex.message}. Tik om het in de app in te spreken.", true) }
            }
        }, "fitmaatje-log").start()
    }

    private fun ping(double: Boolean = false) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(if (double) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 250)
            main.postDelayed({ tg.release() }, 800)
        }
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
