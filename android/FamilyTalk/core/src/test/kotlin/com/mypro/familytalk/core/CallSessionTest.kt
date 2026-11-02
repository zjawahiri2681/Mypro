package com.mypro.familytalk.core

import com.mypro.familytalk.core.call.CallEvent
import com.mypro.familytalk.core.call.CallPhase
import com.mypro.familytalk.core.call.CallSession
import com.mypro.familytalk.core.call.Effect
import com.mypro.familytalk.core.call.Mesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallSessionTest {
    @Test fun outgoingHappyPath() {
        val s = CallSession("c", outgoing = true, startedAt = 0)
        assertEquals(listOf(Effect.StartRingback), s.start())
        s.on(CallEvent.PeerRinging, 1)
        assertEquals(CallPhase.RINGING_OUT, s.phase)
        assertTrue(Effect.StartMedia in s.on(CallEvent.PeerAccepted, 2))
        s.on(CallEvent.MediaConnected, 3)
        assertEquals(CallPhase.ACTIVE, s.phase)
        val fx = s.on(CallEvent.LocalHangup, 10)
        assertTrue(Effect.SendHangup("hangup") in fx && Effect.StopMedia in fx)
        assertEquals(emptyList(), s.on(CallEvent.MediaConnected, 11))
    }

    @Test fun incomingDeclineAndMissed() {
        val s = CallSession("c", outgoing = false, startedAt = 0)
        val fx = s.on(CallEvent.LocalDecline, 1)
        assertTrue(Effect.SendDecline("declined") in fx && Effect.StopRingtone in fx)
        val m = CallSession("d", outgoing = false, startedAt = 0)
        assertEquals(emptyList(), m.on(CallEvent.Tick(10_000), 10_000))
        assertTrue(Effect.Ended("missed") in m.on(CallEvent.Tick(46_000), 46_000))
    }

    @Test fun mediaDropGetsGraceThenEnds() {
        val s = CallSession("c", outgoing = false, startedAt = 0)
        s.on(CallEvent.LocalAccept, 1); s.on(CallEvent.MediaConnected, 2)
        assertEquals(listOf(Effect.RestartIce), s.on(CallEvent.MediaFailed, 100))
        s.on(CallEvent.MediaRecovered, 5_000)
        assertEquals(emptyList(), s.on(CallEvent.Tick(40_000), 40_000))
        s.on(CallEvent.MediaFailed, 50_000)
        assertTrue(s.on(CallEvent.Tick(66_000), 66_000).contains(Effect.Ended("connection lost")))
    }

    @Test fun meshOffersExactlyOncePerPair() {
        val ids = listOf("a1", "b2", "c3", "d4")
        val offers = ids.flatMap { me -> Mesh.peersFor(me, ids).filter { Mesh.shouldOffer(me, it) }.map { setOf(me, it) } }
        assertEquals(6, offers.size)
        assertEquals(6, offers.toSet().size)
        assertTrue(Mesh.canAdd(3, video = true)); assertTrue(!Mesh.canAdd(4, video = true))
    }
}
