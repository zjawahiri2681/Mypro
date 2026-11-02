package com.mypro.minutes.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mypro.minutes.MainActivity
import com.mypro.minutes.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.sqrt

/**
 * Records meeting audio in a foreground service so it keeps going with the screen off.
 *
 * Format is AAC in an ADTS stream (.aac), not MP4: an MP4 file is unplayable if the app is killed
 * before it is finalised, while ADTS stays playable up to the last frame written.
 *
 * In voice-activated mode the service first opens a lightweight [AudioRecord] only to measure the
 * input level (no file written); as soon as speech is heard it releases that and starts the
 * recorder. This is why the file begins at the first voice rather than at a wall of silence.
 *
 * Noise-suppression / voice-focus: recording uses the VOICE_COMMUNICATION audio source, which asks
 * the platform for echo-cancel, noise-suppression and gain-control tuned for speech; the listening
 * phase additionally attaches the NoiseSuppressor / AGC effects where the device offers them.
 *
 * A foreground-service microphone notification is mandatory on Android (and the OS shows a mic
 * indicator regardless), so recording is never silent to the person whose phone it is.
 */
class RecorderService : Service() {

    data class State(
        val file: String? = null,
        val recording: Boolean = false,
        val paused: Boolean = false,
        /** Voice-activated and still waiting for the first voice (no file being written yet). */
        val waitingForVoice: Boolean = false,
        /** Input loudness 0..100, for a live meter. */
        val level: Int = 0,
        /** Audio captured before the current running stretch, in ms. */
        val accumulatedMs: Long = 0,
        /** elapsedRealtime when the current running stretch began. */
        val runningSince: Long = 0,
        val error: String? = null,
    ) {
        val active: Boolean get() = recording || waitingForVoice
        fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long =
            accumulatedMs + if (recording && !paused) (now - runningSince).coerceAtLeast(0) else 0
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var meterTick: Runnable? = null

    // Voice-activation listening phase.
    private var vadThread: HandlerThread? = null
    @Volatile private var vadStop = false
    private var pendingFile: String? = null
    private var noiseSuppression = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_FILE)
                if (path == null) { stopSelf(); return START_NOT_STICKY }
                noiseSuppression = intent.getBooleanExtra(EXTRA_NOISE_SUPPRESSION, true)
                if (intent.getBooleanExtra(EXTRA_VOICE_ACTIVATED, false)) listenForVoice(path) else begin(path)
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish()
            else -> if (recorder == null && vadThread == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- voice activation -----------------------------------------------------------------

    private fun listenForVoice(path: String) {
        if (recorder != null || vadThread != null) return
        goForeground(waiting = true)
        pendingFile = path
        vadStop = false
        _state.value = State(file = path, waitingForVoice = true)
        val thread = HandlerThread("vad").also { it.start() }
        vadThread = thread
        Handler(thread.looper).post { runVad() }
    }

    /** Reads short PCM buffers and watches the RMS level until speech is confirmed. */
    private fun runVad() {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { main.post { startRecorderFromVad() }; return } // can't measure: just record
        val bufSize = maxOf(minBuf, 4096)
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) {
            main.post { startRecorderFromVad() }; return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }; main.post { startRecorderFromVad() }; return
        }
        val effects = if (noiseSuppression) attachEffects(record.audioSessionId) else emptyList()
        val buf = ShortArray(bufSize)
        var voiceFrames = 0
        try {
            record.startRecording()
            while (!vadStop) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                val level = (rms / 320.0).toInt().coerceIn(0, 100) // ~32767 full-scale -> ~100
                _state.value = _state.value.copy(level = level)
                if (rms >= VOICE_RMS_THRESHOLD) {
                    if (++voiceFrames >= VOICE_FRAMES_TO_START) break
                } else {
                    voiceFrames = 0
                }
            }
        } catch (_: Exception) {
            // fall through to start recording anyway
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            runCatching { record.release() }
        }
        if (!vadStop) main.post { startRecorderFromVad() }
    }

    private fun startRecorderFromVad() {
        vadThread?.quitSafely()
        vadThread = null
        val path = pendingFile ?: return
        pendingFile = null
        if (vadStop) return
        begin(path)
    }

    private fun attachEffects(sessionId: Int): List<android.media.audiofx.AudioEffect> {
        val list = mutableListOf<android.media.audiofx.AudioEffect>()
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true }?.let { list += it } }
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }?.let { list += it } }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true }?.let { list += it } }
        return list
    }

    // ---- recording ------------------------------------------------------------------------

    private fun begin(path: String) {
        if (recorder != null) return
        goForeground(waiting = false)
        // Try the speech-tuned source first (echo-cancel + noise-suppression + gain), then plainer ones.
        val sources = if (noiseSuppression)
            intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        else
            intArrayOf(MediaRecorder.AudioSource.MIC)
        var lastError: Exception? = null
        for (source in sources) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                File(path).parentFile?.mkdirs()
                r.setAudioSource(source)
                r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(32_000)
                r.setAudioEncodingBitRate(64_000) // about 29 MB per hour
                r.setOutputFile(path)
                r.prepare()
                r.start()
                recorder = r
                _state.value = State(file = path, recording = true, runningSince = SystemClock.elapsedRealtime())
                startMeter()
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { r.release() }
            }
        }
        _state.value = State(error = "Could not start recording: ${lastError?.message ?: lastError?.javaClass?.simpleName ?: "unknown"}")
        stopForegroundCompat()
        stopSelf()
    }

    private fun startMeter() {
        meterTick?.let { main.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                val r = recorder ?: return
                val s = _state.value
                if (s.recording && !s.paused) {
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    _state.value = s.copy(level = (amp / 328).coerceIn(0, 100))
                }
                main.postDelayed(this, 200)
            }
        }
        meterTick = tick
        main.postDelayed(tick, 200)
    }

    private fun pause() {
        val r = recorder ?: return
        val s = _state.value
        if (!s.recording || s.paused) return
        runCatching { r.pause() }.onSuccess {
            _state.value = s.copy(paused = true, level = 0, accumulatedMs = s.positionMs(), runningSince = 0)
        }
    }

    private fun resume() {
        val r = recorder ?: return
        val s = _state.value
        if (!s.paused) return
        runCatching { r.resume() }.onSuccess {
            _state.value = s.copy(paused = false, runningSince = SystemClock.elapsedRealtime())
        }
    }

    private fun finish() {
        release()
        stopForegroundCompat()
        stopSelf()
    }

    private fun release() {
        vadStop = true
        vadThread?.quitSafely()
        vadThread = null
        pendingFile = null
        meterTick?.let { main.removeCallbacks(it) }
        meterTick = null
        recorder?.let { r ->
            runCatching { r.stop() } // throws if nothing was captured; the file is still usable or empty
            runCatching { r.release() }
        }
        recorder = null
        val s = _state.value
        _state.value = State(file = s.file, accumulatedMs = s.positionMs())
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    // ---- notification ---------------------------------------------------------------------

    private fun goForeground(waiting: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // MIN importance: no sound, collapsed. A notification is still mandatory for a mic service.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Meeting recording", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (waiting) "Listening for the meeting" else "Recording meeting")
            .setContentText("Tap to open Meeting Minutes")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "com.mypro.minutes.record.START"
        const val ACTION_PAUSE = "com.mypro.minutes.record.PAUSE"
        const val ACTION_RESUME = "com.mypro.minutes.record.RESUME"
        const val ACTION_STOP = "com.mypro.minutes.record.STOP"
        const val EXTRA_FILE = "file"
        const val EXTRA_VOICE_ACTIVATED = "voiceActivated"
        const val EXTRA_NOISE_SUPPRESSION = "noiseSuppression"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7

        // Voice-activation tuning. RMS is over 16-bit samples (full scale 32767).
        private const val VOICE_RMS_THRESHOLD = 1400.0
        private const val VOICE_FRAMES_TO_START = 3

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context, file: File, voiceActivated: Boolean, noiseSuppression: Boolean) {
            _state.value = State()
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_FILE, file.absolutePath)
                    .putExtra(EXTRA_VOICE_ACTIVATED, voiceActivated)
                    .putExtra(EXTRA_NOISE_SUPPRESSION, noiseSuppression),
            )
        }

        fun command(context: Context, action: String) {
            if (action != ACTION_STOP && !_state.value.active) return
            runCatching { context.startService(Intent(context, RecorderService::class.java).setAction(action)) }
        }
    }
}
