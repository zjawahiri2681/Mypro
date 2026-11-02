package com.mypro.familytalk.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.mypro.familytalk.R
import com.mypro.familytalk.call.CallUi
import com.mypro.familytalk.data.ChatMsg
import com.mypro.familytalk.sip.SipCallUi
import com.mypro.familytalk.ui.CallActivity
import com.mypro.familytalk.ui.MainActivity

object Notifications {
    const val CH_LINK = "link"
    const val CH_CALLS = "calls"
    const val CH_MESSAGES = "messages"
    const val CH_MISSED = "missed"
    const val ID_LINK = 1
    const val ID_INCOMING = 2
    const val ID_MISSED = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_LINK, "Family link (always on)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps FamilyTalk reachable so calls can ring."; setShowBadge(false)
            },
            NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings for family and SIP calls."; setSound(null, null)
            },
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun open(ctx: Context, cls: Class<*>, code: Int, extra: Intent.() -> Unit = {}) = PendingIntent.getActivity(
        ctx, code, Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extra),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(ctx: Context, act: String, code: Int, key: String? = null) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(act).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun link(ctx: Context, connected: Boolean) = NotificationCompat.Builder(ctx, CH_LINK)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (connected) "Family link active" else "Connecting to family...")
        .setContentText(if (connected) "Ready for calls and messages" else "Waiting for internet")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(open(ctx, MainActivity::class.java, 10))
        .build()

    fun incomingFamily(ctx: Context, call: CallUi) = buildIncoming(
        ctx, call.title, if (call.video) "Family video call" else "Family voice call",
        open(ctx, CallActivity::class.java, 21) { putExtra(CallActivity.EXTRA_ACCEPT, "family") }, action(ctx, CallActionReceiver.DECLINE, 22),
    )

    fun incomingSip(ctx: Context, call: SipCallUi) {
        val n = buildIncoming(ctx, call.remote, "SIP call · caller ID not verified · not end-to-end encrypted",
            open(ctx, CallActivity::class.java, 23) { putExtra(CallActivity.EXTRA_ACCEPT, "sip:" + call.key) }, action(ctx, CallActionReceiver.SIP_DECLINE, 24, call.key))
        post(ctx, ID_INCOMING, n)
    }

    private fun buildIncoming(ctx: Context, who: String, what: String, accept: PendingIntent, decline: PendingIntent) =
        NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open(ctx, CallActivity::class.java, 20), true)
            .setContentIntent(open(ctx, CallActivity::class.java, 20))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(Person.Builder().setName(who).setImportant(true).build(), decline, accept))
            .setContentText(what)
            .build()

    fun cancelIncoming(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_INCOMING)

    fun message(ctx: Context, msg: ChatMsg, from: String, preview: Boolean) {
        val text = msg.text ?: msg.file?.let { "Sent a file: ${it.name}" } ?: return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            // By default only "New message from X" leaves the app (smart watches, notification history, other apps).
            .setContentTitle(if (preview) from else "New message from $from")
            .setContentText(if (preview) text else "Open FamilyTalk to read it")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, CH_MESSAGES).setSmallIcon(R.drawable.ic_notification).setContentTitle("New family message").build())
            .setAutoCancel(true)
            .setContentIntent(open(ctx, MainActivity::class.java, msg.conv.hashCode()) { putExtra("conv", msg.conv); putExtra("token", MainActivity.intentToken) })
            .build()
        post(ctx, 1000 + (msg.conv.hashCode() and 0xffff), n)
    }

    fun missed(ctx: Context, who: String) {
        val n = NotificationCompat.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Missed call")
            .setContentText(who)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(open(ctx, MainActivity::class.java, 30))
            .build()
        post(ctx, ID_MISSED, n)
    }

    fun post(ctx: Context, id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) } // SecurityException if POST_NOTIFICATIONS denied
    }
}
