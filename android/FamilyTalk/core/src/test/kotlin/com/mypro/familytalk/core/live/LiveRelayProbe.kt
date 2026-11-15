package com.mypro.familytalk.core.live

import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.family.RelayConfig
import com.mypro.familytalk.core.files.FileTransfer
import com.mypro.familytalk.core.protocol.FileChunk
import com.mypro.familytalk.core.protocol.FileOffer
import com.mypro.familytalk.core.relay.Incoming
import com.mypro.familytalk.core.relay.Messenger
import com.mypro.familytalk.core.relay.MiniMqttRelay
import com.mypro.familytalk.core.relay.MultiRelay
import java.util.Collections
import kotlin.test.Test

/**
 * Talks to the REAL public relays. Skipped unless FAMILYTALK_LIVE=1 (run by the
 * "FamilyTalk live probe" workflow, which has internet). Prints findings; never fails the build.
 */
class LiveRelayProbe {
    private val live = System.getenv("FAMILYTALK_LIVE") == "1"
    private fun log(s: String) = println("PROBE $s")

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }

    @Test fun messageSizesPerRelay() {
        if (!live) return
        val urls = System.getenv("FAMILYTALK_PROBE_RELAYS")?.split(",")?.filter { it.isNotBlank() } ?: RelayConfig.PUBLIC_RELAYS
        for (url in urls) {
            val tag = Bytes.hex(Bytes.random(6))
            val inbox = "ftprobe/$tag"
            val got = Collections.synchronizedList(mutableListOf<Int>())
            val rx = MiniMqttRelay(url, "ftp-rx-$tag", log = { })
            val tx = MiniMqttRelay(url, "ftp-tx-$tag", log = { })
            rx.start(inbox) { got += it.size }
            tx.start("ftprobe/$tag-tx") { }
            if (!waitUntil(20_000) { rx.connected.value && tx.connected.value }) {
                log("$url CONNECT FAILED rx=${rx.lastError.value} tx=${tx.lastError.value}"); rx.stop(); tx.stop(); continue
            }
            Thread.sleep(1500)
            val sizes = listOf(1024, 16 * 1024, 32 * 1024, 64 * 1024 + 61, 128 * 1024 + 61, 256 * 1024 + 61, 512 * 1024)
            for (n in sizes) {
                val before = got.size
                val ok = tx.publish(inbox, ByteArray(n) { 7 }, true)
                val arrived = waitUntil(8_000) { got.size > before }
                log("$url size=$n published=$ok arrived=$arrived txConnected=${tx.connected.value} txErr=${tx.lastError.value}")
                if (!tx.connected.value) { waitUntil(15_000) { tx.connected.value } }
            }
            // Burst: 300 x 64 KiB back to back, like a 10 MB file.
            val before = got.size
            val t0 = System.currentTimeMillis()
            var published = 0
            repeat(300) { if (tx.publish(inbox, ByteArray(64 * 1024 + 61) { 1 }, true)) published++ }
            waitUntil(90_000) { got.size - before >= 300 }
            log("$url burst published=$published arrived=${got.size - before}/300 in ${System.currentTimeMillis() - t0} ms txConnected=${tx.connected.value} err=${tx.lastError.value}")
            rx.stop(); tx.stop()
        }
    }

    @Test fun endToEndFileOverAllRelays() {
        if (!live || System.getenv("FAMILYTALK_PROBE_RELAYS") != null) return
        val a = IdentityKeys.generate(); val b = IdentityKeys.generate()
        val fam = "probe-" + Bytes.hex(Bytes.random(4))
        val trusted = mapOf(a.memberId to a.public, b.memberId to b.public)
        fun multi(tag: String) = MultiRelay(RelayConfig.PUBLIC_RELAYS.mapIndexed { i, u -> MiniMqttRelay(u, "ftp-$tag-${Bytes.hex(Bytes.random(4))}-$i") })
        val ra = multi("a"); val rb = multi("b")
        val ma = Messenger(a, { fam }, { trusted }, ra)
        val mb = Messenger(b, { fam }, { trusted }, rb)
        val got = Collections.synchronizedList(mutableListOf<Incoming>())
        mb.onIncoming = { got += it }
        ma.start(); mb.start()
        waitUntil(25_000) { ra.relays.all { it.connected.value } && rb.relays.all { it.connected.value } }
        log("e2e connected a=${ra.connectedNames} b=${rb.connectedNames}")
        Thread.sleep(2000)
        val data = ByteArray(3 * 1024 * 1024) { (it * 13).toByte() }
        val (offer, chunks) = FileTransfer.prepare("probe.bin", "application/octet-stream", data)
        val t0 = System.currentTimeMillis()
        ma.send(b.public, offer)
        chunks.forEach { ma.send(b.public, it); Thread.sleep(15) }
        waitUntil(120_000) { got.count { it.payload is FileChunk } >= chunks.size }
        val idx = got.mapNotNull { (it.payload as? FileChunk)?.index }.toSet()
        log("e2e offer=${got.any { it.payload is FileOffer }} chunks=${idx.size}/${chunks.size} in ${System.currentTimeMillis() - t0} ms; missing=${(0 until chunks.size).filter { it !in idx }.take(40)}")
        ma.stop(); mb.stop()
    }
}
