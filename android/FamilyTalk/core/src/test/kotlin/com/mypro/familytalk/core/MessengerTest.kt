package com.mypro.familytalk.core

import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.crypto.PublicIdentity
import com.mypro.familytalk.core.crypto.Sealed
import com.mypro.familytalk.core.files.FileTransfer
import com.mypro.familytalk.core.protocol.CallHangup
import com.mypro.familytalk.core.protocol.ChatText
import com.mypro.familytalk.core.protocol.JoinRequest
import com.mypro.familytalk.core.protocol.PayloadCodec
import com.mypro.familytalk.core.protocol.ReplayGuard
import com.mypro.familytalk.core.relay.Incoming
import com.mypro.familytalk.core.relay.LoopbackRelay
import com.mypro.familytalk.core.relay.Messenger
import com.mypro.familytalk.core.relay.Topics
import com.mypro.familytalk.core.relay.Wire
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessengerTest {
    private val hub = LoopbackRelay.Hub()
    private val a = IdentityKeys.generate()
    private val b = IdentityKeys.generate()
    private val stranger = IdentityKeys.generate()
    private val family = mapOf(a.memberId to a.public, b.memberId to b.public)

    private fun messenger(k: IdentityKeys, trusted: Map<String, PublicIdentity> = family, inbox: MutableList<Incoming>) =
        Messenger(k, { "fam" }, { trusted }, LoopbackRelay(hub)).also { it.onIncoming = { m -> inbox += m }; it.start() }

    @Test fun deliversBetweenFamilyAndQueuesOffline() {
        val got = mutableListOf<Incoming>()
        val ma = messenger(a, inbox = mutableListOf())
        ma.send(b.public, ChatText("m1", "hi", 1)) // b offline -> broker queue
        messenger(b, inbox = got)
        assertEquals(listOf("hi"), got.map { (it.payload as ChatText).text })
        assertTrue(got.single().fromKnownMember)
    }

    @Test fun strangersCanOnlySendJoinRequests() {
        val got = mutableListOf<Incoming>()
        messenger(b, inbox = got)
        val ms = messenger(stranger, trusted = family + (b.memberId to b.public), inbox = mutableListOf())
        ms.send(b.public, ChatText("x", "spam", 1), joining = true)
        ms.send(b.public, JoinRequest("Guest", stranger.public, "inv", "proof"), joining = true)
        assertEquals(1, got.size)
        assertTrue(got.single().payload is JoinRequest)
        assertTrue(!got.single().fromKnownMember)
    }

    @Test fun replayedEnvelopeIsDropped() {
        val got = mutableListOf<Incoming>()
        val relay = LoopbackRelay(hub)
        val mb = Messenger(b, { "fam" }, { family }, relay).also { it.onIncoming = { m -> got += m }; it.start() }
        val env = Sealed.seal(PayloadCodec.encode(CallHangup("c1")), "fam", a, b.public)
        val wire = Sealed.sealOuter(Json.encodeToString(Wire.serializer(), Wire(env)).toByteArray(), b.public.dhKeyBytes)
        LoopbackRelay(hub).publish(Topics.inbox("fam", b.memberId), wire, false)
        LoopbackRelay(hub).publish(Topics.inbox("fam", b.memberId), wire, false)
        assertEquals(1, got.size)
        mb.stop()
    }

    @Test fun staleSignallingDroppedButOfflineChatKept() {
        val g = ReplayGuard()
        val now = 10_000_000_000L
        assertTrue(!g.accept("a", "1", now - Messenger.SIGNAL_WINDOW - 1, now, Messenger.SIGNAL_WINDOW))
        assertTrue(g.accept("a", "2", now - 3 * 24 * 3600 * 1000L, now, Messenger.DURABLE_WINDOW))
        assertTrue(!g.accept("a", "3", now + 10 * 60 * 1000L, now))
    }

    @Test fun relaySeesNoIdentitiesOrExactSizes() {
        val seen = mutableListOf<ByteArray>()
        val spy = object : com.mypro.familytalk.core.relay.Relay {
            override val connected = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun start(inbox: String, onMessage: (ByteArray) -> Unit) {}
            override fun publish(topic: String, data: ByteArray, queueIfOffline: Boolean) { seen += data }
            override fun stop() {}
        }
        val m = Messenger(a, { "fam-secret-id" }, { family }, spy)
        m.send(b.public, ChatText("m1", "hi", 1))
        m.send(b.public, ChatText("m2", "a slightly longer message here", 2))
        val text = String(seen[0], Charsets.ISO_8859_1)
        assertTrue(!text.contains(a.memberId) && !text.contains(b.memberId) && !text.contains("fam-secret-id"))
        assertEquals(seen[0].size, seen[1].size) // padded to the same bucket
        assertFailsWith<com.mypro.familytalk.core.crypto.SealException> { Sealed.openOuter(seen[0], stranger) }
    }

    @Test fun signallingFromBeforeRestartIsDropped() {
        val got = mutableListOf<Incoming>()
        val start = System.currentTimeMillis()
        Messenger(b, { "fam" }, { family }, LoopbackRelay(hub), signalNotBefore = start).also { it.onIncoming = { m -> got += m }; it.start() }
        val old = Sealed.seal(PayloadCodec.encode(CallHangup("c9")), "fam", a, b.public, nowMs = start - 30_000)
        LoopbackRelay(hub).publish(Topics.inbox("fam", b.memberId), Sealed.sealOuter(Json.encodeToString(Wire.serializer(), Wire(old)).toByteArray(), b.public.dhKeyBytes), false)
        assertEquals(0, got.size)
    }

    @Test fun topicsHideIdentity() {
        val t = Topics.inbox("fam", a.memberId)
        assertTrue(!t.contains(a.memberId))
        assertNotEquals(t, Topics.inbox("fam2", a.memberId))
    }

    @Test fun fileChunkRoundTripAndTamper() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val (offer, chunks) = FileTransfer.prepare("../../etc/passwd", "text/plain", data)
        assertEquals("passwd", offer.name)
        val asm = FileTransfer.Assembly(offer)
        chunks.reversed().forEach(asm::add)
        assertContentEquals(data, asm.finish())

        val bad = FileTransfer.Assembly(offer)
        chunks.forEach(bad::add)
        bad.add(chunks[0].copy(data = com.mypro.familytalk.core.crypto.Bytes.b64(ByteArray(10))))
        assertFailsWith<IllegalArgumentException> { bad.finish() }
        assertEquals("file", FileTransfer.sanitizeName("..."))
    }
}
