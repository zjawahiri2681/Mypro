package com.mypro.familytalk.sip

import android.content.Context
import android.util.Log
import com.mypro.familytalk.core.sip.SipAccount
import com.mypro.familytalk.core.sip.SipTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.MediaEncryption as LinphoneEncryption
import com.mypro.familytalk.core.sip.MediaEncryption as FtEncryption

data class SipCallUi(val key: String, val remote: String, val state: String, val incoming: Boolean, val connectedAt: Long?, val encrypted: Boolean, val paused: Boolean)
data class SipUi(val configured: Boolean = false, val registration: String = "Not set up", val registered: Boolean = false, val calls: List<SipCallUi> = emptyList(), val conference: Boolean = false, val micMuted: Boolean = false, val speaker: Boolean = false)

/**
 * SIP calling through the Linphone SDK: register any SIP account, call SIP addresses
 * and phone numbers (via the provider), receive SIP calls, and merge several SIP calls
 * into one local conference on this phone.
 */
class SipEngine(private val context: Context) {
    private val _ui = MutableStateFlow(SipUi())
    val ui: StateFlow<SipUi> = _ui.asStateFlow()
    var onIncoming: (SipCallUi) -> Unit = {}
    var onEnded: () -> Unit = {}

    /**
     * Linphone 5.3+ requires every Core call on the thread that created it, and that
     * thread needs a Looper for auto-iterate. Everything below runs on this thread.
     */
    private val thread = android.os.HandlerThread("ft-sip").apply { start() }
    private val handler = android.os.Handler(thread.looper)
    private fun onSip(block: () -> Unit) { handler.post { runCatching(block).onFailure { Log.e(TAG, "sip", it) } } }

    @Volatile private var callCount = 0
    private var core: Core? = null
    private var localConference: org.linphone.core.Conference? = null
    /** SDES-SRTP keys travel inside SIP signalling, so voice is only protected when signalling is TLS. */
    private var tlsSignalling = false
    private val connectedAt = mutableMapOf<Call, Long>()

    val busy: Boolean get() = callCount > 0

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState?, message: String) {
            _ui.value = _ui.value.copy(
                registration = when (state) {
                    RegistrationState.Ok -> "Registered"
                    RegistrationState.Progress -> "Connecting..."
                    RegistrationState.Failed -> "Failed: $message"
                    RegistrationState.Cleared, RegistrationState.None -> "Off"
                    else -> state?.name ?: "?"
                },
                registered = state == RegistrationState.Ok,
            )
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            if (state == Call.State.StreamsRunning && call !in connectedAt) connectedAt[call] = System.currentTimeMillis()
            if (state == Call.State.Released || state == Call.State.End || state == Call.State.Error) connectedAt.remove(call)
            refresh()
            if (state == Call.State.IncomingReceived) {
                ui.value.calls.firstOrNull { it.key == key(call) }?.let(onIncoming)
            }
            if ((state == Call.State.Released) && core.callsNb == 0) { localConference = null; onEnded() }
        }
    }

    fun configure(account: SipAccount?) = onSip { configureNow(account) }

    private fun configureNow(account: SipAccount?) {
        if (account == null || !account.enabled || account.problems().isNotEmpty()) { shutdownNow(); _ui.value = SipUi(); return }
        val c = core ?: Factory.instance().createCore(null, null, context).also {
            it.addListener(listener)
            // Always check the provider's TLS certificate and host name.
            it.verifyServerCertificates(true)
            it.verifyServerCn(true)
            core = it
        }
        tlsSignalling = account.transport == SipTransport.TLS
        c.clearAccounts()
        c.clearAllAuthInfo()
        val f = Factory.instance()
        val auth = f.createAuthInfo(account.username, account.authUsername ?: account.username, account.password, null, null, account.domain, null)
        val params = c.createAccountParams()
        val identity = f.createAddress(account.identityUri) ?: return
        if (account.displayName.isNotBlank()) identity.displayName = account.displayName
        params.identityAddress = identity
        val serverHost = account.proxy?.takeIf { it.isNotBlank() }?.removePrefix("sips:")?.removePrefix("sip:") ?: account.domain
        val server = f.createAddress("sip:$serverHost") ?: return
        server.transport = when (account.transport) {
            SipTransport.TLS -> TransportType.Tls
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.UDP -> TransportType.Udp
        }
        account.port?.let { server.port = it }
        params.serverAddress = server
        params.isRegisterEnabled = true
        val acc = c.createAccount(params)
        c.addAuthInfo(auth)
        c.addAccount(acc)
        c.defaultAccount = acc
        c.mediaEncryption = when (account.mediaEncryption) {
            FtEncryption.NONE -> LinphoneEncryption.None
            else -> LinphoneEncryption.SRTP
        }
        c.isMediaEncryptionMandatory = account.mediaEncryption == FtEncryption.SRTP_MANDATORY
        c.isVideoCaptureEnabled = false
        c.isVideoDisplayEnabled = false
        runCatching { c.start() }.onFailure { Log.e(TAG, "linphone start", it) }
        _ui.value = _ui.value.copy(configured = true, registration = "Connecting...")
    }

    /** Returns false straight away if the account isn't registered; the call itself starts on the SIP thread. */
    fun call(uri: String): Boolean {
        if (!_ui.value.registered) return false
        onSip {
            val c = core ?: return@onSip
            val addr = Factory.instance().createAddress(uri) ?: return@onSip
            val params = c.createCallParams(null) ?: return@onSip
            params.mediaEncryption = c.mediaEncryption
            params.isVideoEnabled = false
            c.inviteAddressWithParams(addr, params)
        }
        return true
    }

    fun accept(key: String) = onSip { find(key)?.accept() }
    fun decline(key: String) = onSip { find(key)?.decline(Reason.Declined) }
    fun hangup(key: String) = onSip { find(key)?.terminate() }
    fun hangupAll() = onSip { core?.terminateAllCalls() }
    fun togglePause(key: String) = onSip { find(key)?.let { if (it.state == Call.State.Paused) it.resume() else it.pause() }; refresh() }

    fun toggleMic() = onSip { core?.let { it.isMicEnabled = !it.isMicEnabled }; refresh() }

    fun sendDtmf(key: String, digit: Char) = onSip { find(key)?.sendDtmf(digit) }

    /** Merges every SIP call on this phone into one local audio conference. */
    fun mergeAll() = onSip { mergeNow() }

    private fun mergeNow() {
        val c = core ?: return
        if (c.callsNb < 2) return
        val conf = localConference ?: run {
            val p = c.createConferenceParams(null)
            p.isVideoEnabled = false
            c.createConferenceWithParams(p)
        } ?: return
        localConference = conf
        c.calls.forEach { call -> if (call.conference == null) conf.addParticipant(call) }
        refresh()
    }


    private fun find(key: String): Call? = core?.calls?.firstOrNull { key(it) == key }
    private fun key(c: Call) = c.callLog.callId ?: c.remoteAddress.asStringUriOnly()

    private fun refresh() {
        val c = core ?: return
        callCount = c.callsNb
        _ui.value = _ui.value.copy(
            calls = c.calls.map { call ->
                SipCallUi(
                    key = key(call),
                    remote = call.remoteAddress.displayName?.takeIf { it.isNotBlank() } ?: call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly(),
                    state = when (call.state) {
                        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> "Incoming"
                        Call.State.OutgoingInit, Call.State.OutgoingProgress -> "Calling..."
                        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> "Ringing"
                        Call.State.Connected, Call.State.StreamsRunning, Call.State.Updating, Call.State.UpdatedByRemote -> "Connected"
                        Call.State.Paused, Call.State.Pausing -> "On hold"
                        Call.State.PausedByRemote -> "Held by other side"
                        Call.State.Error -> "Failed"
                        else -> "Ended"
                    },
                    incoming = call.dir == Call.Dir.Incoming,
                    connectedAt = connectedAt[call],
                    encrypted = tlsSignalling && call.currentParams.mediaEncryption != LinphoneEncryption.None,
                    paused = call.state == Call.State.Paused,
                )
            },
            conference = localConference != null && c.callsNb >= 2,
            micMuted = !c.isMicEnabled,
        )
    }

    fun shutdown() = onSip { shutdownNow() }

    private fun shutdownNow() {
        callCount = 0
        core?.let { runCatching { it.terminateAllCalls(); it.clearAccounts(); it.clearAllAuthInfo(); it.stop() } }
    }

    private companion object { const val TAG = "SipEngine" }
}
