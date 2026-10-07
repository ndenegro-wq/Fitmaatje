package nl.denegro.fitmaatje

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Long-form dictation on top of Android's speech recogniser.
 * The recogniser stops after each pause; we restart it automatically until the user taps stop,
 * so you can talk as long as you like. Must be used from the main thread.
 */
class Dictation(
    private val ctx: Context,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var sr: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    var active = false
        private set
    private var emptyInARow = 0

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("Spraakherkenning is niet beschikbaar. Installeer/activeer de Google-app of typ je tekst.")
            return
        }
        active = true
        emptyInARow = 0
        ListenService.holdMic = true
        onState(true)
        // Give the listening service a moment to release the microphone.
        main.postDelayed({ if (active) begin() }, if (ListenService.running) 900L else 100L)
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { sr?.stopListening() }
        onState(false)
        main.postDelayed({
            runCatching { sr?.destroy() }
            sr = null
            if (!active) ListenService.holdMic = false
        }, 1500)
    }

    fun release() {
        active = false
        runCatching { sr?.destroy() }
        sr = null
        ListenService.holdMic = false
    }

    private fun begin() {
        runCatching { sr?.destroy() }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "nl-NL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        }
        r.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) { emptyInARow = 0; onFinal(t) } else emptyInARow++
            onPartial("")
            if (active && emptyInARow < 3) begin() else if (active) stop()
        }

        override fun onError(error: Int) {
            onPartial("")
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    emptyInARow++
                    if (emptyInARow < 3) main.postDelayed({ if (active) begin() }, 200) else stop()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    main.postDelayed({ if (active) begin() }, 600)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stop(); onError("Geef FitMaatje toestemming voor de microfoon.") }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> { stop(); onError("Geen internet voor spraakherkenning. Typ je tekst of probeer opnieuw.") }
                else -> { stop(); onError("Spraakherkenning gestopt (code $error). Tik opnieuw op de microfoon.") }
            }
        }
    }
}
