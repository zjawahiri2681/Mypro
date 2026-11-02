package com.mypro.familytalk.core

import com.mypro.familytalk.core.relay.MiniMqttRelay
import com.mypro.familytalk.core.relay.MultiRelay
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tiny in-process MQTT broker (optionally behind WebSocket) to exercise the client's wire format. */
class FakeBroker(private val websocket: Boolean) : AutoCloseable {
    private val server = ServerSocket(0)
    val port get() = server.localPort
    private val subs = CopyOnWriteArrayList<Pair<String, OutputStream>>()
    val connects = LinkedBlockingQueue<String>()

    init {
        Thread { while (!server.isClosed) runCatching { val s = server.accept(); Thread { serve(s) }.apply { isDaemon = true }.start() } }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) = runCatching {
        var input: InputStream = s.getInputStream()
        var output: OutputStream = s.getOutputStream()
        if (websocket) {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            val key = head.lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n".toByteArray()); output.flush()
            input = WsServerIn(input); output = WsServerOut(output)
        }
        val din = DataInputStream(input)
        while (true) {
            val type = din.readUnsignedByte()
            var mult = 1; var len = 0
            while (true) { val d = din.readUnsignedByte(); len += (d and 0x7f) * mult; mult *= 128; if (d and 0x80 == 0) break }
            val body = ByteArray(len); din.readFully(body)
            when (type and 0xF0) {
                0x10 -> { // CONNECT: grab client id
                    val pl = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                    var o = 2 + pl + 1 + 1 + 2
                    val cl = ((body[o].toInt() and 0xff) shl 8) or (body[o + 1].toInt() and 0xff)
                    connects += String(body, o + 2, cl); write(output, 0x20, byteArrayOf(0, 0))
                }
                0x80 -> { // SUBSCRIBE
                    val tl = ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
                    subs += String(body, 4, tl) to output
                    write(output, 0x90, byteArrayOf(body[0], body[1], 1))
                }
                0x30 -> { // PUBLISH: forward
                    val qos = (type ushr 1) and 3
                    val tl = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                    val topic = String(body, 2, tl)
                    if (qos == 1) write(output, 0x40, byteArrayOf(body[2 + tl], body[3 + tl]))
                    subs.filter { it.first == topic }.forEach { (_, o) -> write(o, type, body) }
                }
                0xC0 -> write(output, 0xD0, ByteArray(0))
                0xE0 -> return@runCatching
            }
        }
    }

    private fun write(o: OutputStream, header: Int, body: ByteArray) {
        val b = ByteArrayOutputStream(); b.write(header)
        var len = body.size
        do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; b.write(d) } while (len > 0)
        b.write(body)
        synchronized(o) { o.write(b.toByteArray()); o.flush() }
    }

    override fun close() = server.close()

    private class WsServerOut(val raw: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val h = ByteArrayOutputStream(); h.write(0x82)
            when { len < 126 -> h.write(len); len < 65536 -> { h.write(126); h.write(len ushr 8); h.write(len and 0xff) }
                else -> { h.write(127); for (i in 7 downTo 0) h.write(((len.toLong() ushr (8 * i)) and 0xff).toInt()) } }
            h.write(b, off, len); raw.write(h.toByteArray()); raw.flush()
        }
    }

    private class WsServerIn(raw: InputStream) : InputStream() {
        val din = DataInputStream(raw); var buf = ByteArray(0); var pos = 0
        override fun read(): Int {
            while (pos >= buf.size) {
                din.readUnsignedByte(); val b1 = din.readUnsignedByte()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong() else if (len == 127L) len = din.readLong()
                require(b1 and 0x80 != 0) { "client frames must be masked" }
                val m = ByteArray(4); din.readFully(m)
                buf = ByteArray(len.toInt()); din.readFully(buf)
                for (i in buf.indices) buf[i] = (buf[i].toInt() xor m[i and 3].toInt()).toByte()
                pos = 0
            }
            return buf[pos++].toInt() and 0xff
        }
    }
}

class MiniMqttTest {
    private val closers = mutableListOf<AutoCloseable>()
    @AfterTest fun tearDown() = closers.forEach { runCatching { it.close() } }

    private fun waitFor(cond: () -> Boolean) { val end = System.currentTimeMillis() + 5000; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20); assertTrue(cond()) }

    private fun roundTrip(ws: Boolean) {
        val broker = FakeBroker(ws).also { closers += it }
        val url = (if (ws) "ws" else "tcp") + "://127.0.0.1:${broker.port}" + (if (ws) "/mqtt" else "")
        val got = LinkedBlockingQueue<ByteArray>()
        val a = MiniMqttRelay(url, "client-a").also { closers += AutoCloseable { it.stop() } }
        val b = MiniMqttRelay(url, "client-b").also { closers += AutoCloseable { it.stop() } }
        a.start("inbox/a") {}
        b.start("inbox/b") { got += it }
        waitFor { a.connected.value && b.connected.value }
        Thread.sleep(200) // let SUBSCRIBE land
        val small = "hello".toByteArray()
        val big = ByteArray(200_000) { (it % 251).toByte() } // > 64 KiB: 8-byte WebSocket length + 3-byte MQTT length
        assertTrue(a.publish("inbox/b", small, queueIfOffline = true))
        assertTrue(a.publish("inbox/b", big, queueIfOffline = false))
        assertContentEquals(small, got.poll(5, TimeUnit.SECONDS))
        assertContentEquals(big, got.poll(5, TimeUnit.SECONDS))
        assertEquals(setOf("client-a", "client-b"), setOf(broker.connects.poll(1, TimeUnit.SECONDS), broker.connects.poll(1, TimeUnit.SECONDS)))
    }

    @Test fun mqttOverTcp() = roundTrip(ws = false)
    @Test fun mqttOverWebSocket() = roundTrip(ws = true)

    @Test fun multiRelayDeliversIfAnyOneRelayWorks() {
        val broker = FakeBroker(false).also { closers += it }
        val dead = "tcp://127.0.0.1:1" // nothing listens here
        val ok = "tcp://127.0.0.1:${broker.port}"
        val got = LinkedBlockingQueue<ByteArray>()
        val a = MultiRelay(listOf(MiniMqttRelay(dead, "a1"), MiniMqttRelay(ok, "a2"))).also { closers += AutoCloseable { it.stop() } }
        val b = MultiRelay(listOf(MiniMqttRelay(ok, "b2"))).also { closers += AutoCloseable { it.stop() } }
        a.start("in/a") {}; b.start("in/b") { got += it }
        waitFor { a.connected.value && b.connected.value }
        Thread.sleep(200)
        assertTrue(a.publish("in/b", "x".toByteArray(), true))
        assertContentEquals("x".toByteArray(), got.poll(5, TimeUnit.SECONDS))
        waitFor { a.relays[0].lastError.value != null }
    }

    @Test fun refusesPlainTextToRealServers() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { MiniMqttRelay("tcp://broker.example.com:1883", "x") }
    }
}
