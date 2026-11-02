package com.mypro.minutes.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous speech-to-text on top of Android's SpeechRecognizer.
 *
 * The platform recogniser handles one utterance and then stops, so this class restarts it after
 * every result or "no speech" timeout, with a growing back-off for real errors so a broken engine
 * cannot spin in a tight loop. Must be used from the main thread.
 */
class LiveTranscriber(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String, confidence: Float?)
        /** [offlineProblem] is true when switching to online recognition may fix it. */
        fun onProblem(message: String, fatal: Boolean, offlineProblem: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var failures = 0
    private var lastStartAt = 0L

    var language: String = ""
    var preferOffline: Boolean = true

    val isRunning: Boolean get() = wanted

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (wanted) return
        if (!isAvailable()) {
            listener.onProblem("No speech recognition service on this phone. Install or enable the Google app, or type entries instead.", fatal = true, offlineProblem = false)
            return
        }
        wanted = true
        failures = 0
        listenSoon(0)
        listener.onListening(true)
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
        listener.onPartial("")
        listener.onListening(false)
    }

    private fun listenSoon(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listenNow() }, delayMs)
    }

    private fun listenNow() {
        if (!wanted) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints only; many engines ignore them, which is why we restart on every stop.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
            if (language.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
        }
        lastStartAt = SystemClock.elapsedRealtime()
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            recreateAndRetry()
        }
    }

    private fun recreateAndRetry() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        retryWithBackoff()
    }

    private fun retryWithBackoff() {
        failures++
        if (failures > MAX_FAILURES) {
            stop()
            listener.onProblem("Speech recognition keeps failing. Check the microphone and the Google speech service, or type entries.", fatal = true, offlineProblem = preferOffline)
            return
        }
        listenSoon(minOf(250L shl (failures - 1), 4000L))
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) listener.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            listener.onPartial("")
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it > 0f && it <= 1f }
            if (text.isNotBlank()) {
                failures = 0
                listener.onFinal(text, conf)
            }
            listenSoon(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            listener.onPartial("")
            if (!wanted) return
            when (error) {
                // Normal in a meeting: silence, or nothing intelligible. Keep listening.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // If the engine gives up instantly every time, treat it as a failure to avoid a hot loop.
                    if (SystemClock.elapsedRealtime() - lastStartAt < 500) retryWithBackoff() else listenSoon(RESTART_DELAY_MS)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> recreateAndRetry()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stop()
                    listener.onProblem("Microphone permission is off. Allow it in Settings, or type entries.", fatal = true, offlineProblem = false)
                }
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                    stop()
                    listener.onProblem(
                        if (preferOffline) "This language is not available offline. Download it (Google app > Settings > Voice > Offline speech recognition) or allow online recognition."
                        else "This language is not supported by the speech service on this phone.",
                        fatal = true, offlineProblem = preferOffline,
                    )
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS -> {
                    if (failures >= 2) {
                        stop()
                        listener.onProblem(
                            if (preferOffline) "Offline recognition is not ready for this language and the engine tried the network. Download the offline language, or allow online recognition."
                            else "Speech service cannot reach the network.",
                            fatal = true, offlineProblem = preferOffline,
                        )
                    } else retryWithBackoff()
                }
                else -> retryWithBackoff() // ERROR_AUDIO and anything new
            }
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 120L
        private const val MAX_FAILURES = 6
        // Constants added in API 31; inlined so they compile against any SDK and are just ints on older phones.
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
