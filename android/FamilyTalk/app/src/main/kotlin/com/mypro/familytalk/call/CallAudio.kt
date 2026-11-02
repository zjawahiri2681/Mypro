package com.mypro.familytalk.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Ringtone, ringback tone and speaker / earpiece routing. */
class CallAudio(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    private var beeper: ToneGenerator? = null
    private var beepThread: Thread? = null

    /**
     * [loud] = "family calls ring even on silent": the ring then uses the alarm stream,
     * which silent mode and Do Not Disturb let through by default.
     * Wi-Fi tablets often have no ringtone at all, so we fall back to the notification
     * sound, the alarm sound, and finally a generated tone.
     */
    fun startRingtone(loud: Boolean) {
        stopRingtone()
        val normal = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
        if (normal || loud) {
            val usage = if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            val uri = listOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)
                .firstNotNullOfOrNull { RingtoneManager.getActualDefaultRingtoneUri(context, it) ?: RingtoneManager.getDefaultUri(it) }
            ringtone = uri?.let { RingtoneManager.getRingtone(context, it) }?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(usage).build()
                isLooping = true
                runCatching { play() }
            }
            if (ringtone == null || ringtone?.isPlaying == false) startBeeping(if (loud) AudioManager.STREAM_ALARM else AudioManager.STREAM_RING)
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT || loud) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        }
    }

    private fun startBeeping(stream: Int) {
        val tg = runCatching { ToneGenerator(stream, 100) }.getOrNull() ?: return
        beeper = tg
        beepThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 900)
                    Thread.sleep(2_000)
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    fun stopRingtone() {
        ringtone?.stop(); ringtone = null
        beepThread?.interrupt(); beepThread = null
        beeper?.let { runCatching { it.stopTone(); it.release() } }; beeper = null
        vibrator?.cancel()
    }

    fun startRingback() {
        stopRingback()
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun stopRingback() { ringback?.stopTone(); ringback?.release(); ringback = null }

    private var focus: android.media.AudioFocusRequest? = null

    fun enterCall(speaker: Boolean) {
        if (focus == null) {
            focus = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build().also { am.requestAudioFocus(it) }
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        // Tablets have no earpiece: always use the loudspeaker there.
        setSpeaker(speaker || !hasEarpiece())
    }

    private fun hasEarpiece(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            // Prefer a connected headset / Bluetooth device over the earpiece.
            val devices = am.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET) }
            val target = if (!on && headset != null) headset else devices.firstOrNull { it.type == wanted }
            if (target != null) am.setCommunicationDevice(target)
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun exitCall() {
        stopRingtone(); stopRingback()
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
    }
}
