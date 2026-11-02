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

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        family = FamilyRepo(this)
        chats = ChatRepo(this, family)
        calls = CallManager(this, family, chats)
        sip = SipEngine(this)
        calls.externalBusy = { sip.busy }
        chats.onNewMessage = { msg, name -> Notifications.message(this, msg, name) }
        sip.onIncoming = { c ->
            if (calls.inCall) sip.decline(c.key) else Notifications.incomingSip(this, c)
        }
        sip.onEnded = { Notifications.cancelIncoming(this) }
        family.scope.launch {
            family.data.map { it.sip }.distinctUntilChanged().collect { acc -> runCatching { sip.configure(acc) } }
        }
        if (family.hasFamily) {
            family.connect()
            LinkService.start(this)
        }
    }

    companion object {
        lateinit var instance: FamilyTalkApp; private set
    }
}
