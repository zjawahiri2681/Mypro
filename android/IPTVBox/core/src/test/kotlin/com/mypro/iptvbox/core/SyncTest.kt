package com.mypro.iptvbox.core

import com.mypro.iptvbox.core.sync.ClockOffsetEstimator
import com.mypro.iptvbox.core.sync.LanClientTransport
import com.mypro.iptvbox.core.sync.LanHostTransport
import com.mypro.iptvbox.core.sync.MediaRef
import com.mypro.iptvbox.core.sync.SyncCrypto
import com.mypro.iptvbox.core.sync.SyncMath
import com.mypro.iptvbox.core.sync.SyncMessage
import com.mypro.iptvbox.core.sync.WatchLink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    @Test
    fun linkRoundTrip() {
        val link = WatchLink(
            room = WatchLink.newRoomId(), key = WatchLink.newKey(), mode = WatchLink.Mode.RELAY,
            broker = WatchLink.DEFAULT_BROKER, media = MediaRef(MediaRef.Kind.URL, url = "http://x/y.m3u8", name = "Test"), hostName = "Z",
        )
        val uri = link.toUri()
        assertTrue(uri.startsWith("iptvbox://join?d="))
        assertEquals(link, WatchLink.parse(uri))
        assertEquals(link, WatchLink.parse("  $uri  "))
        assertNull(WatchLink.parse("https://example.com"))
        assertNull(WatchLink.parse("iptvbox://join?d=!!!"))
    }

    @Test
    fun cryptoRoundTripAndTamper() {
        val c = SyncCrypto(WatchLink.newKey())
        val msg = SyncMessage(SyncMessage.Type.STATE, "me", 123L, playing = true, posMs = 4567L)
        val enc = c.encrypt(msg.encode())
        assertEquals(msg, SyncMessage.decode(c.decrypt(enc)!!))
        val other = SyncCrypto(WatchLink.newKey())
        assertNull(other.decrypt(enc))
        assertNull(c.decrypt("garbage"))
    }

    @Test
    fun expectedPositionAdvancesOnlyWhilePlaying() {
        val st = SyncMessage(SyncMessage.Type.STATE, "h", ts = 10_000L, playing = true, posMs = 60_000L, rate = 1f)
        assertEquals(63_000L, SyncMath.expectedPosition(st, nowLocalMs = 13_000L, clockOffsetMs = 0L))
        assertEquals(63_500L, SyncMath.expectedPosition(st, nowLocalMs = 13_000L, clockOffsetMs = 500L))
        val paused = st.copy(playing = false)
        assertEquals(60_000L, SyncMath.expectedPosition(paused, 99_999L, 0L))
    }

    @Test
    fun correctionsByDriftSize() {
        assertEquals(SyncMath.Correction.Seek(10_000L), SyncMath.correction(5_000L, 10_000L))
        val r = SyncMath.correction(9_000L, 10_000L)
        assertTrue(r is SyncMath.Correction.Rate && r.rate > 1f)
        val r2 = SyncMath.correction(11_000L, 10_000L)
        assertTrue(r2 is SyncMath.Correction.Rate && r2.rate < 1f)
        assertEquals(SyncMath.Correction.Rate(1f), SyncMath.correction(10_100L, 10_000L))
        assertEquals(SyncMath.Correction.None, SyncMath.correction(10_100L, 10_000L, hostPlaying = false))
        assertEquals(SyncMath.Correction.Seek(10_000L), SyncMath.correction(5_000L, 10_000L, hostPlaying = false))
    }

    @Test
    fun clockOffsetPrefersLowRtt() {
        val e = ClockOffsetEstimator()
        e.addSample(t0 = 1000, t1 = 1600, t2 = 1200) // rtt 200, offset 500
        e.addSample(t0 = 2000, t1 = 4000, t2 = 5000) // rtt 3000, offset 500
        e.addSample(t0 = 3000, t1 = 3550, t2 = 3100) // rtt 100, offset 500
        assertEquals(500L, e.offsetMs)
        assertEquals(100L, e.rttMs)
    }

    @Test
    fun lanTransportDeliversBothWays() {
        val key = WatchLink.newKey()
        val port = 48000 + (System.nanoTime() % 1000).toInt()
        val host = LanHostTransport(port, SyncCrypto(key), "host")
        val client = LanClientTransport("127.0.0.1", port, SyncCrypto(key), "client")
        val gotAtHost = CountDownLatch(1)
        val gotAtClient = CountDownLatch(1)
        var hostMsg: SyncMessage? = null
        var clientMsg: SyncMessage? = null
        host.onMessage = { hostMsg = it; gotAtHost.countDown() }
        client.onMessage = { clientMsg = it; gotAtClient.countDown() }
        val connected = CountDownLatch(1)
        client.onStatus = { if (it.startsWith("Connected")) connected.countDown() }
        host.start()
        Thread.sleep(300)
        client.start()
        assertTrue(connected.await(5, TimeUnit.SECONDS), "client should connect")
        Thread.sleep(100)
        client.send(SyncMessage(SyncMessage.Type.CMD, "client", 1L, cmd = SyncMessage.Cmd.PAUSE))
        assertTrue(gotAtHost.await(5, TimeUnit.SECONDS), "host should receive")
        assertEquals(SyncMessage.Cmd.PAUSE, hostMsg?.cmd)
        host.send(SyncMessage(SyncMessage.Type.STATE, "host", 2L, playing = true, posMs = 42L))
        assertTrue(gotAtClient.await(5, TimeUnit.SECONDS), "client should receive")
        assertEquals(42L, clientMsg?.posMs)
        assertNotNull(clientMsg)
        client.stop(); host.stop()
    }
}
