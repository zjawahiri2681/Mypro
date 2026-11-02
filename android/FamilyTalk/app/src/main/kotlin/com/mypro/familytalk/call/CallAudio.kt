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

    fun startRingtone() {
        stopRingtone()
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                isLooping = true
                play()
            }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        }
    }

    fun stopRingtone() {
        ringtone?.stop(); ringtone = null
        vibrator?.cancel()
    }

    fun startRingback() {
        stopRingback()
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun stopRingback() { ringback?.stopTone(); ringback?.release(); ringback = null }

    fun enterCall(speaker: Boolean) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

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
    }
}
