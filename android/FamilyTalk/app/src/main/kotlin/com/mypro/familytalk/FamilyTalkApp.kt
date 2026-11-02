package com.mypro.familytalk

import android.app.Application
import com.mypro.familytalk.call.CallManager
import com.mypro.familytalk.data.ChatRepo
import com.mypro.familytalk.data.FamilyRepo
import com.mypro.familytalk.service.LinkService
import com.mypro.familytalk.service.Notifications
import com.mypro.familytalk.sip.SipEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FamilyTalkApp : Application() {
    lateinit var family: FamilyRepo; private set
    lateinit var chats: ChatRepo; private set
    lateinit var calls: CallManager; private set
    lateinit var sip: SipEngine; private set
    lateinit var updater: com.mypro.familytalk.update.Updater; private set

    @Volatile private var visibleActivities = 0
    val inForeground: Boolean get() = visibleActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visibleActivities++ }
            override fun onActivityStopped(a: android.app.Activity) { visibleActivities = maxOf(0, visibleActivities - 1) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        Notifications.createChannels(this)
        family = FamilyRepo(this)
        chats = ChatRepo(this, family)
        calls = CallManager(this, family, chats)
        sip = SipEngine(this)
        calls.externalBusy = { sip.busy }
        chats.onNewMessage = { msg, name -> Notifications.message(this, msg, name, family.data.value.notifPreview) }
        sip.onIncoming = { c ->
            if (calls.inCall) sip.decline(c.key) else Notifications.incomingSip(this, c)
        }
        sip.onEnded = { Notifications.cancelIncoming(this) }
        family.onJoinRequested = { name -> Notifications.joinRequest(this, name) }
        updater = com.mypro.familytalk.update.Updater(this, family.scope)
        updater.onReady = { info -> Notifications.updateReady(this, info.version, info.build) }
        family.scope.launch {
            kotlinx.coroutines.delay(20_000) // let startup and the relay settle first
            while (true) {
                if (family.data.value.autoUpdate && !calls.inCall) updater.check(download = true)
                kotlinx.coroutines.delay(12 * 60 * 60 * 1000L)
            }
        }
        family.scope.launch {
            family.data.map { it.sip }.distinctUntilChanged().collect { acc -> runCatching { sip.configure(acc) } }
        }
        if (family.hasFamily) {
            family.connect()
            family.resumeJoinIfPending()
            LinkService.start(this)
        }
        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = family.onNetworkAvailable()
        })
    }

    companion object {
        lateinit var instance: FamilyTalkApp; private set
    }
}
