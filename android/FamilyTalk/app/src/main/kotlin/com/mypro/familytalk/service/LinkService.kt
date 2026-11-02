package com.mypro.familytalk.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.call.CallUi
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the encrypted relay connection open so family calls
 * ring even when the app is closed. No Google push (FCM) is used, so Google's push
 * service never learns when family members message or call each other.
 */
class LinkService : LifecycleService() {
    private var inCall = false
    private var video = false

    override fun onCreate() {
        super.onCreate()
        promote()
        val app = FamilyTalkApp.instance
        lifecycleScope.launch {
            app.family.relayConnected.collect { up ->
                if (!inCall) Notifications.post(this@LinkService, Notifications.ID_LINK, Notifications.link(this@LinkService, up))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_ENTER_CALL -> { inCall = true; video = intent.getBooleanExtra("video", false); promote() }
            ACTION_EXIT_CALL -> { inCall = false; promote() }
            else -> promote()
        }
        return START_STICKY
    }

    private fun promote() {
        // Android 11+ needs the microphone/camera type for audio to keep flowing with the screen off.
        var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        if (inCall && Build.VERSION.SDK_INT >= 30) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        val n = if (inCall) Notifications.ongoingCall(this) else Notifications.link(this, FamilyTalkApp.instance.family.relayConnected.value)
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_LINK, n, type) }
            .recoverCatching { ServiceCompat.startForeground(this, Notifications.ID_LINK, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0) }
    }

    companion object {
        private const val ACTION_ENTER_CALL = "enter_call"
        private const val ACTION_EXIT_CALL = "exit_call"

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java)) }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, LinkService::class.java))

        /** Adds microphone (and camera) to the service type so audio keeps flowing with the screen off. */
        fun enterCall(ctx: Context, video: Boolean) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_ENTER_CALL).putExtra("video", video)) }
        }

        fun exitCall(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_EXIT_CALL)) }
        }

        fun showIncoming(ctx: Context, call: CallUi) = Notifications.post(ctx, Notifications.ID_INCOMING, Notifications.incomingFamily(ctx, call))
        fun cancelIncoming(ctx: Context) = Notifications.cancelIncoming(ctx)
        fun notifyMissed(ctx: Context, who: String) = Notifications.missed(ctx, who)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (FamilyTalkApp.instance.family.hasFamily) LinkService.start(context)
    }
}

/** Decline buttons on the incoming-call notification. (Accept opens [com.mypro.familytalk.ui.CallActivity] directly.) */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = FamilyTalkApp.instance
        when (intent.action) {
            DECLINE -> app.calls.decline()
            SIP_DECLINE -> intent.getStringExtra("key")?.let(app.sip::decline)
        }
        Notifications.cancelIncoming(context)
    }

    companion object {
        const val DECLINE = "ft.decline"
        const val SIP_DECLINE = "ft.sip.decline"
    }
}
