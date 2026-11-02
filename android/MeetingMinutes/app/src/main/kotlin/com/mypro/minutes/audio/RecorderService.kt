package com.mypro.minutes.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
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

/**
 * Records meeting audio in a foreground service so it keeps going with the screen off.
 *
 * Format is AAC in an ADTS stream (.aac), not MP4: an MP4 file is unplayable if the app is killed
 * before it is finalised, while ADTS stays playable up to the last frame written.
 */
class RecorderService : Service() {

    data class State(
        val file: String? = null,
        val recording: Boolean = false,
        val paused: Boolean = false,
        /** Audio captured before the current running stretch, in ms. */
        val accumulatedMs: Long = 0,
        /** elapsedRealtime when the current running stretch began. */
        val runningSince: Long = 0,
        val error: String? = null,
    ) {
        fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long =
            accumulatedMs + if (recording && !paused) (now - runningSince).coerceAtLeast(0) else 0
    }

    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_FILE)?.let { begin(it) } ?: stopSelf()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish()
            else -> if (recorder == null) stopSelf() // restarted by the system with no recorder: nothing to do
        }
        return START_NOT_STICKY
    }

    private fun begin(path: String) {
        if (recorder != null) return
        goForeground()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            File(path).parentFile?.mkdirs()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
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
        } catch (e: Exception) {
            runCatching { r.release() }
            _state.value = State(error = "Could not start recording: ${e.message ?: e.javaClass.simpleName}")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun pause() {
        val r = recorder ?: return
        val s = _state.value
        if (!s.recording || s.paused) return
        runCatching { r.pause() }.onSuccess {
            _state.value = s.copy(paused = true, accumulatedMs = s.positionMs(), runningSince = 0)
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

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Meeting recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Recording meeting")
            .setContentText("Tap to open Meeting Minutes")
            .setOngoing(true)
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
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context, file: File) {
            _state.value = State()
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_FILE, file.absolutePath),
            )
        }

        fun command(context: Context, action: String) {
            if (action != ACTION_STOP && !_state.value.recording) return
            runCatching { context.startService(Intent(context, RecorderService::class.java).setAction(action)) }
        }
    }
}
