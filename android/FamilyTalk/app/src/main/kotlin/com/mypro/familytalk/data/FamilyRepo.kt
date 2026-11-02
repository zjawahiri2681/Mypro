package com.mypro.familytalk.data

import android.content.Context
import android.util.Log
import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.crypto.PublicIdentity
import com.mypro.familytalk.core.family.FamilyAdmin
import com.mypro.familytalk.core.family.Invite
import com.mypro.familytalk.core.family.Member
import com.mypro.familytalk.core.family.RelayConfig
import com.mypro.familytalk.core.family.Role
import com.mypro.familytalk.core.family.Roster
import com.mypro.familytalk.core.family.RosterCodec
import com.mypro.familytalk.core.family.RosterRejected
import com.mypro.familytalk.core.family.SignedRoster
import com.mypro.familytalk.core.protocol.JoinRequest
import com.mypro.familytalk.core.protocol.PrekeyAnnounce
import com.mypro.familytalk.core.crypto.Prekeys
import com.mypro.familytalk.core.crypto.SignedPrekey
import com.mypro.familytalk.core.protocol.Payload
import com.mypro.familytalk.core.protocol.Ping
import com.mypro.familytalk.core.protocol.Pong
import com.mypro.familytalk.core.protocol.ReplayGuard
import com.mypro.familytalk.core.protocol.RosterUpdate
import com.mypro.familytalk.core.relay.Incoming
import com.mypro.familytalk.core.relay.Messenger
import com.mypro.familytalk.core.relay.MqttRelay
import com.mypro.familytalk.core.sip.SipAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The single source of truth for identity, family roster and relay connection.
 * Everything is persisted encrypted through [Vault].
 */
class FamilyRepo(private val context: Context) {
    private val vault = Vault(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    val roster: StateFlow<Roster?> = data.map { d -> d.roster?.let { runCatching { it.roster() }.getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, _data.value.roster?.let { runCatching { it.roster() }.getOrNull() })

    val relayConnected = MutableStateFlow(false)
    val relayError = MutableStateFlow<String?>(null)
    /** Time the join request was last sent (joiner side), for the waiting screen. */
    val joinSentAt = MutableStateFlow<Long?>(null)
    private var joinLoop: kotlinx.coroutines.Job? = null

    /** Payload handlers registered by the call and chat layers. */
    val handlers = mutableListOf<(Incoming) -> Boolean>()

    private var messenger: Messenger? = null
    private val startedAt = System.currentTimeMillis()

    /** Join requests waiting for the admin's "Allow" on screen. */
    val pendingJoins = MutableStateFlow<List<PendingJoin>>(emptyList())

    /** Called with (old, new) roster so the chat can record "X joined / was removed". */
    var onRosterChanged: (Roster?, Roster) -> Unit = { _, _ -> }
    var onJoinRequested: (String) -> Unit = {}
    private var relay: MqttRelay? = null
    private val guard = ReplayGuard().also { it.restore(_data.value.replay) }

    val identity: IdentityKeys by lazy {
        val existing = _data.value.identity
        if (existing != null) IdentityKeys.import(Bytes.unb64(existing))
        else IdentityKeys.generate().also { k -> save { it.copy(identity = Bytes.b64(k.exportPrivate())) } }
    }
    val myId: String get() = identity.memberId

    val me: Member? get() = roster.value?.byId(myId)
    val isAdmin: Boolean get() = me?.role == Role.ADMIN
    val hasFamily: Boolean get() = _data.value.roster != null || _data.value.familyId != null

    // ---- persistence -------------------------------------------------------------------
    private fun load(): AppData = try {
        vault.read(STATE)?.let { json.decodeFromString(AppData.serializer(), String(it)) } ?: AppData(clientId = "ft-" + Bytes.hex(Bytes.random(8)))
    } catch (e: Exception) {
        Log.e(TAG, "vault unreadable", e); AppData(clientId = "ft-" + Bytes.hex(Bytes.random(8)))
    }

    @Synchronized
    fun save(change: (AppData) -> AppData) {
        _data.update(change)
        vault.write(STATE, json.encodeToString(AppData.serializer(), _data.value).toByteArray())
    }

    // ---- family setup -------------------------------------------------------------------
    fun createFamily(familyName: String, myName: String, relay: RelayConfig = RelayConfig()) {
        val signed = FamilyAdmin(identity).create(familyName, myName, relay)
        save { it.copy(myName = myName, pinnedAdmin = identity.public, roster = signed, familyId = signed.roster().familyId, removed = false) }
        connect()
    }

    /** Joiner side: pin the admin from the QR and ask to be admitted. */
    fun join(invite: Invite, myName: String) {
        require(System.currentTimeMillis() <= invite.expiresAt) { "This invite has expired. Ask for a new one." }
        save {
            it.copy(myName = myName, pinnedAdmin = invite.admin, familyId = invite.familyId, joinRelay = invite.relay, joinInvite = invite, roster = null, removed = false)
        }
        connect()
        resumeJoinIfPending()
    }

    /** Keeps asking until the admin's phone answers (it may be offline or restarted). */
    fun resumeJoinIfPending() {
        if (_data.value.roster != null || _data.value.joinInvite == null) return
        sendJoinRequest()
        joinLoop?.cancel()
        joinLoop = scope.launch {
            while (_data.value.roster == null && _data.value.joinInvite != null) {
                kotlinx.coroutines.delay(30_000)
                if (relayConnected.value) sendJoinRequest()
            }
        }
    }

    fun sendJoinRequest() {
        val invite = _data.value.joinInvite ?: return
        if (_data.value.roster != null) return
        val name = _data.value.myName
        messenger?.send(invite.admin, JoinRequest(name, identity.public, invite.inviteId, invite.proof(name, identity.public)), joining = true)
        joinSentAt.value = System.currentTimeMillis()
    }

    fun newInvite(forName: String?): Invite {
        val r = roster.value ?: error("no family")
        val inv = FamilyAdmin(identity).invite(r, forName)
        save { d -> d.copy(invites = d.invites.filter { it.expiresAt > System.currentTimeMillis() } + inv) }
        return inv
    }

    fun removeMember(memberId: String) {
        val r = roster.value ?: return
        val removed = r.byId(memberId)
        publishRoster(FamilyAdmin(identity).remove(r, memberId), extra = listOfNotNull(removed?.identity))
    }

    fun renameMember(memberId: String, name: String) {
        val r = roster.value ?: return
        publishRoster(FamilyAdmin(identity).update(r) { ro -> ro.copy(members = ro.members.map { if (it.id == memberId) it.copy(name = name.trim().take(40)) else it }) })
    }

    fun setRole(memberId: String, role: Role) {
        val r = roster.value ?: return
        publishRoster(FamilyAdmin(identity).update(r) { ro -> ro.copy(members = ro.members.map { if (it.id == memberId) it.copy(role = role) else it }) })
    }

    fun updateRelay(relayConfig: RelayConfig) {
        val r = roster.value ?: return
        publishRoster(FamilyAdmin(identity).update(r) { it.copy(relay = relayConfig) })
    }

    private fun publishRoster(signed: SignedRoster, extra: List<PublicIdentity> = emptyList()) {
        val before = roster.value
        val next = RosterCodec.verify(signed, before, null)
        save { it.copy(roster = signed) }
        onRosterChanged(before, next)
        (next.members.map { it.identity } + extra).filter { it.memberId != myId }.forEach { send(it, RosterUpdate(signed)) }
        if (relayConfigChanged(next)) connect()
    }

    private var connectedWith: RelayConfig? = null
    private fun relayConfigChanged(r: Roster) = r.relay != connectedWith

    fun setSip(account: SipAccount?) = save { it.copy(sip = account) }
    fun setRelayOnly(on: Boolean) = save { it.copy(relayOnly = on) }
    fun setSecureScreen(on: Boolean) = save { it.copy(secureScreen = on) }
    fun setNotifPreview(on: Boolean) = save { it.copy(notifPreview = on) }
    fun setAutoUpdate(on: Boolean) = save { it.copy(autoUpdate = on) }
    fun markVerified(memberId: String, verified: Boolean) =
        save { it.copy(verified = if (verified) it.verified + memberId else it.verified - memberId) }

    fun leaveAndWipe() {
        joinLoop?.cancel()
        joinSentAt.value = null
        messenger?.stop(); messenger = null
        vault.wipe()
        java.io.File(context.filesDir, "received").deleteRecursively()
        _data.value = AppData(clientId = "ft-" + Bytes.hex(Bytes.random(8)))
    }

    // ---- relay --------------------------------------------------------------------------
    fun trusted(): Map<String, PublicIdentity> {
        val r = roster.value
        if (r != null) return r.members.associate { it.id to it.identity }
        val admin = _data.value.pinnedAdmin ?: return emptyMap()
        return mapOf(admin.memberId to admin)
    }

    @Synchronized
    fun connect() {
        val d = _data.value
        val familyId = d.familyId ?: roster.value?.familyId ?: return
        val cfg = roster.value?.relay ?: d.joinRelay ?: RelayConfig()
        messenger?.stop()
        val r = MqttRelay(cfg.broker, d.clientId, cfg.brokerUser, cfg.brokerPassword) { Log.i(TAG, it) }
        val m = Messenger(
            identity, { familyId }, ::trusted, r, guard, log = { Log.i(TAG, it) },
            signalNotBefore = startedAt - 5_000,
            myPrekeys = { id -> _data.value.prekeys.firstOrNull { it.id == id } },
            peerPrekey = { member -> _data.value.peerPrekeys[member] },
        )
        m.onIncoming = ::dispatch
        relay = r; messenger = m; connectedWith = cfg
        m.start()
        connectionJob?.cancel()
        scope.launch { r.lastError.collect { relayError.value = it } }
        connectionJob = scope.launch {
            r.connected.collect { up ->
                relayConnected.value = up
                if (up) { m.flushOutbox(); maintainPrekeys() }
            }
        }
    }

    private var connectionJob: kotlinx.coroutines.Job? = null

    /** Network came back: reconnect now instead of waiting for the backoff timer. */
    fun onNetworkAvailable() { relay?.nudge() }

    fun disconnect() { messenger?.stop(); messenger = null; connectionJob?.cancel() }

    /** Weekly prekey rotation; old private prekeys are deleted after three weeks. */
    @Synchronized
    fun maintainPrekeys(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val d = _data.value
        val rotate = force || Prekeys.needsRotation(d.prekeys, now)
        val current = if (rotate) Prekeys.generate(identity, now) else null
        val kept = Prekeys.prune(d.prekeys + listOfNotNull(current?.first), now)
        if (rotate || kept.size != d.prekeys.size) save { it.copy(prekeys = kept) }
        if (current != null) announcePrekey(current.second)
    }

    private fun announcePrekey(signed: SignedPrekey) {
        save { it.copy(myPrekey = signed) }
        roster.value?.members?.filter { it.id != myId }?.forEach { send(it.identity, PrekeyAnnounce(signed)) }
    }

    /** Re-sends our current prekey to one member (e.g. someone who just joined). */
    private fun announcePrekeyTo(member: PublicIdentity) {
        val newest = _data.value.prekeys.maxByOrNull { it.created } ?: return
        val signed = _data.value.myPrekey?.takeIf { it.id == newest.id } ?: return
        send(member, PrekeyAnnounce(signed))
    }

    fun send(to: PublicIdentity, payload: Payload) {
        messenger?.send(to, payload) ?: Log.w(TAG, "not connected; dropped ${payload::class.simpleName}")
    }

    fun sendToMember(memberId: String, payload: Payload) {
        val id = roster.value?.byId(memberId)?.identity ?: return
        send(id, payload)
    }

    private fun dispatch(msg: Incoming) {
        when (val p = msg.payload) {
            is RosterUpdate -> onRoster(p)
            is JoinRequest -> onJoinRequest(msg.from, p)
            is Ping -> send(msg.from, Pong(p.at))
            is PrekeyAnnounce -> if (msg.fromKnownMember && p.prekey.verify(msg.from)) {
                val prev = _data.value.peerPrekeys[msg.from.memberId]
                if (prev == null || p.prekey.created > prev.created) save { it.copy(peerPrekeys = it.peerPrekeys + (msg.from.memberId to p.prekey)) }
            }
            else -> handlers.firstOrNull { it(msg) }
        }
        if (Messenger.isDurable(msg.payload)) scheduleReplaySave()
    }

    private fun onRoster(update: RosterUpdate) {
        val d = _data.value
        try {
            val next = RosterCodec.verify(update.roster, roster.value, if (roster.value == null) d.pinnedAdmin else null)
            if (d.familyId != null && next.familyId != d.familyId) return
            val stillIn = next.byId(myId) != null
            val old = roster.value
            save { d2 ->
                d2.copy(roster = update.roster, familyId = next.familyId, joinRelay = null, joinInvite = null, removed = !stillIn,
                    peerPrekeys = d2.peerPrekeys.filterKeys { next.byId(it) != null },
                    verified = d2.verified.filter { next.byId(it) != null }.toSet())
            }
            if (relayConfigChanged(next)) connect()
            if (!stillIn) { events.tryEmit("You were removed from the family"); return }
            onRosterChanged(old, next)
            if (old == null) maintainPrekeys(force = true)
            else next.members.filter { old.byId(it.id) == null && it.id != myId }.forEach { announcePrekeyTo(it.identity) }
        } catch (e: RosterRejected) {
            Log.w(TAG, "roster rejected: ${e.message}")
        }
    }

    private fun onJoinRequest(from: PublicIdentity, req: JoinRequest) {
        if (!isAdmin) return
        // Already admitted but they missed the directory: send it again.
        roster.value?.byId(from.memberId)?.let { _data.value.roster?.let { r -> send(from, RosterUpdate(r)) }; return }
        val invite = _data.value.invites.firstOrNull { it.inviteId == req.inviteId } ?: return
        if (req.identity != from || System.currentTimeMillis() > invite.expiresAt) return
        if (!invite.checkProof(req.name, from, req.proof)) return
        if (pendingJoins.value.any { it.identity == from }) return
        val safety = com.mypro.familytalk.core.crypto.SafetyNumber.forPair(identity.public, from)
        pendingJoins.update { it + PendingJoin(req.name, from, req, safety) }
        events.tryEmit("${req.name.take(40)} wants to join the family")
        onJoinRequested(req.name.take(40))
    }

    /** The admin tapped Allow (true) or Deny (false) on a pending join. */
    fun decideJoin(p: PendingJoin, allow: Boolean) {
        pendingJoins.update { list -> list.filter { it.identity != p.identity } }
        if (!allow) {
            save { d -> d.copy(invites = d.invites.filter { it.inviteId != p.request.inviteId }) }
            return
        }
        val from = p.identity
        val req = p.request
        val r = roster.value ?: return
        val invite = _data.value.invites.firstOrNull { it.inviteId == req.inviteId } ?: return
        try {
            val signed = FamilyAdmin(identity).admit(r, invite, req.name, from, req.proof)
            save { d -> d.copy(invites = d.invites.filter { it.inviteId != invite.inviteId }) }
            publishRoster(signed)
            val m = signed.roster().byId(from.memberId)
            events.tryEmit("${m?.name} joined as extension ${m?.ext}")
            announcePrekeyTo(from)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "join rejected: ${e.message}")
        }
    }

    private var replaySaveJob: kotlinx.coroutines.Job? = null

    /** Persists the replay cache at most every 5 s (a 25 MB file is ~500 messages). */
    private fun scheduleReplaySave() {
        if (replaySaveJob?.isActive == true) return
        replaySaveJob = scope.launch {
            kotlinx.coroutines.delay(5_000)
            save { it.copy(replay = guard.snapshot()) }
        }
    }

    /** One-line toasts for the UI ("Mum joined as 102"). */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    companion object {
        private const val TAG = "FamilyRepo"
        private const val STATE = "state.v1"
    }
}
