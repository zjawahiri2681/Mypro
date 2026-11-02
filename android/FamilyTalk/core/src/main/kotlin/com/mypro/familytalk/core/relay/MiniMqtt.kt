package com.mypro.familytalk.core.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Small MQTT 3.1.1 client (just what FamilyTalk needs: one QoS-1 subscription,
 * QoS 0/1 publish, persistent session, keep-alive), written so the connection is
 * fully under our control:
 *  - tries IPv4 addresses first, then IPv6 (many home networks have broken IPv6 routes),
 *  - TLS with SNI and strict host-name verification,
 *  - plain MQTT over TLS (ssl://host:8883) or MQTT over secure WebSocket
 *    (wss://host:443/mqtt) for networks that only allow web ports,
 *  - reconnects forever with backoff, and reports the last error in plain words.
 */
class MiniMqttRelay(
    private val url: String,
    private val clientId: String,
    private val user: String? = null,
    private val password: String? = null,
    private val log: (String) -> Unit = {},
    private val keepAliveSec: Int = 600,
) : Relay {
    override val connected = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val name: String get() = uri.host + ":" + port

    private val uri = URI(url)
    private val tls = uri.scheme == "ssl" || uri.scheme == "wss" || uri.scheme == "mqtts"
    private val ws = uri.scheme == "wss" || uri.scheme == "ws"
    private val port = if (uri.port > 0) uri.port else if (ws) (if (tls) 443 else 80) else (if (tls) 8883 else 1883)

    private val exec = Executors.newScheduledThreadPool(1) { Thread(it, "ft-mqtt-$name").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var inbox: String = ""
    private var onMessage: (ByteArray) -> Unit = {}
    private var packetId = 0
    private var backoffMs = 2_000L
    private var pinger: ScheduledFuture<*>? = null
    @Volatile private var lastInbound = 0L

    init {
        require(tls || uri.host == "localhost" || uri.host == "127.0.0.1") { "Relay must use TLS (ssl:// or wss://)" }
    }

    override fun start(inbox: String, onMessage: (ByteArray) -> Unit) {
        this.inbox = inbox; this.onMessage = onMessage
        running = true
        runCatching { exec.execute { connectLoop() } }
    }

    override fun stop() {
        running = false
        runCatching { synchronized(this) { out?.write(byteArrayOf(0xE0.toByte(), 0)); out?.flush() } }
        closeSocket()
        exec.shutdownNow()
    }

    /** Wake-up alarm: if connected, ping (and reconnect if the link went quiet); else retry now. */
    fun keepAlive() {
        if (!running) return
        if (connected.value) runCatching { exec.execute { ping() } } else nudge()
    }

    /** Network changed: retry now instead of waiting for the backoff. */
    fun nudge() { if (running && !connected.value) { backoffMs = 1_000; runCatching { exec.execute { if (!connected.value) connectLoop() } } } }

    override fun publish(topic: String, data: ByteArray, queueIfOffline: Boolean): Boolean {
        val o = out ?: return false
        if (!connected.value) return false
        val qos = if (queueIfOffline) 1 else 0
        val body = ByteArrayOutputStream()
        writeString(body, topic)
        if (qos == 1) { val id = nextId(); body.write(id ushr 8); body.write(id and 0xff) }
        body.write(data)
        return try { send(o, 0x30 or (qos shl 1), body.toByteArray()); true } catch (e: Exception) { onBroken(e); false }
    }

    // ---- connection ----------------------------------------------------------------------
    @Synchronized private fun nextId(): Int { packetId = if (packetId >= 65535) 1 else packetId + 1; return packetId }

    private fun connectLoop() {
        if (!running || connected.value) return
        try {
            val s = openSocket()
            var input: InputStream = s.getInputStream()
            var output: OutputStream = s.getOutputStream()
            if (ws) {
                val (i, o) = WebSocket.handshake(s, uri.host, port, uri.rawPath.ifEmpty { "/mqtt" })
                input = i; output = o
            }
            socket = s
            sendConnect(output)
            val din = DataInputStream(input)
            val (type, body) = readPacket(din)
            if (type != 0x20 || body.size < 2) throw IllegalStateException("unexpected reply from relay")
            if (body[1].toInt() != 0) throw IllegalStateException(connackReason(body[1].toInt()))
            out = output
            // (Re)subscribe every time; harmless with a persistent session.
            val sub = ByteArrayOutputStream()
            val id = nextId(); sub.write(id ushr 8); sub.write(id and 0xff)
            writeString(sub, inbox); sub.write(1)
            send(output, 0x82, sub.toByteArray())
            s.soTimeout = 0
            lastInbound = System.currentTimeMillis()
            connected.value = true
            lastError.value = null
            backoffMs = 2_000
            log("relay connected: $name via ${s.inetAddress.hostAddress}")
            pinger?.cancel(false)
            pinger = exec.scheduleWithFixedDelay({ ping() }, keepAliveSec / 2L, keepAliveSec / 2L, TimeUnit.SECONDS)
            Thread({ readLoop(din, s) }, "ft-mqtt-read-$name").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            closeSocket()
            lastError.value = describe(e)
            log("relay $name failed: ${lastError.value}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running) return
        val d = backoffMs
        backoffMs = minOf(backoffMs * 2, 30_000)
        runCatching { exec.schedule({ connectLoop() }, d, TimeUnit.MILLISECONDS) }
    }

    /** IPv4 first, then IPv6; each address gets its own short timeout. */
    private fun openSocket(): Socket {
        val addrs = InetAddress.getAllByName(uri.host).sortedBy { if (it is Inet4Address) 0 else 1 }
        var last: Exception? = null
        for (a in addrs) {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(a, port), 8_000)
                plain.soTimeout = 15_000
                plain.tcpNoDelay = true
                if (!tls) return plain
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, uri.host, port, true) as SSLSocket
                runCatching {
                    val p = ssl.sslParameters
                    p.serverNames = listOf(SNIHostName(uri.host))
                    p.endpointIdentificationAlgorithm = "HTTPS"
                    ssl.sslParameters = p
                }
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.host, ssl.session)) {
                    ssl.close(); throw javax.net.ssl.SSLPeerUnverifiedException("certificate is not for ${uri.host}")
                }
                return ssl
            } catch (e: Exception) {
                runCatching { plain.close() }
                last = e
            }
        }
        throw last ?: java.net.UnknownHostException(uri.host)
    }

    private fun sendConnect(o: OutputStream) {
        val b = ByteArrayOutputStream()
        writeString(b, "MQTT"); b.write(4)
        var flags = 0 // cleanSession = false: the broker keeps our subscription and queues QoS-1 messages
        if (!user.isNullOrBlank()) flags = flags or 0x80
        if (!password.isNullOrBlank()) flags = flags or 0x40
        b.write(flags)
        b.write(keepAliveSec ushr 8); b.write(keepAliveSec and 0xff)
        writeString(b, clientId)
        if (!user.isNullOrBlank()) writeString(b, user)
        if (!password.isNullOrBlank()) writeString(b, password)
        send(o, 0x10, b.toByteArray())
    }

    private fun readLoop(din: DataInputStream, s: Socket) {
        try {
            while (running && socket === s) {
                val (type, body) = readPacket(din)
                lastInbound = System.currentTimeMillis()
                when (type and 0xF0) {
                    0x30 -> {
                        val qos = (type ushr 1) and 3
                        val tl = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                        val topic = String(body, 2, tl, Charsets.UTF_8)
                        var off = 2 + tl
                        if (qos > 0) {
                            val idHi = body[off].toInt() and 0xff; val idLo = body[off + 1].toInt() and 0xff
                            off += 2
                            out?.let { send(it, 0x40, byteArrayOf(idHi.toByte(), idLo.toByte())) }
                        }
                        val payload = body.copyOfRange(off, body.size)
                        if (topic == inbox) runCatching { onMessage(payload) }.onFailure { log("handler: ${it.message}") }
                    }
                    else -> {} // SUBACK, PUBACK, PINGRESP
                }
            }
        } catch (e: Exception) {
            if (running && socket === s) onBroken(e)
        }
    }

    private fun ping() {
        val o = out ?: return
        // A whole keep-alive period without any reply (not even PINGRESP): the link is dead.
        if (System.currentTimeMillis() - lastInbound > keepAliveSec * 1000L) { onBroken(EOFException("relay stopped answering")); return }
        runCatching { send(o, 0xC0, ByteArray(0)) }.onFailure { onBroken(it as Exception) }
    }

    private fun onBroken(e: Exception) {
        if (!connected.value && socket == null) return
        log("relay $name lost: ${e.message}")
        lastError.value = describe(e)
        closeSocket()
        scheduleRetry()
    }

    private fun closeSocket() {
        connected.value = false
        pinger?.cancel(false)
        out = null
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun send(o: OutputStream, header: Int, body: ByteArray) {
        val b = ByteArrayOutputStream(body.size + 5)
        b.write(header)
        var len = body.size
        do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; b.write(d) } while (len > 0)
        b.write(body)
        synchronized(this) { o.write(b.toByteArray()); o.flush() }
    }

    private fun readPacket(din: DataInputStream): Pair<Int, ByteArray> {
        val type = din.readUnsignedByte()
        var mult = 1; var len = 0; var i = 0
        while (true) {
            val d = din.readUnsignedByte()
            len += (d and 0x7f) * mult; mult *= 128
            if (d and 0x80 == 0) break
            if (++i > 3) throw IllegalStateException("bad packet length")
        }
        if (len > MAX_PACKET) throw IllegalStateException("packet too large")
        val body = ByteArray(len); din.readFully(body)
        return type to body
    }

    private fun writeString(b: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        b.write(bytes.size ushr 8); b.write(bytes.size and 0xff); b.write(bytes)
    }

    private fun connackReason(code: Int) = when (code) {
        1 -> "relay refused: protocol version"
        2 -> "relay refused the client id"
        3 -> "relay unavailable"
        4 -> "wrong relay username/password"
        5 -> "not authorised by relay"
        else -> "relay refused ($code)"
    }

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val kind = when (root) {
            is java.net.UnknownHostException -> "can't look up $name (DNS / no internet)"
            is java.net.SocketTimeoutException -> "$name timed out (port $port may be blocked)"
            is java.net.ConnectException -> "$name refused or unreachable (port $port may be blocked)"
            is javax.net.ssl.SSLException -> "secure connection to $name failed (${root.message?.take(60)})"
            else -> "$name: ${root.message?.take(80) ?: root.javaClass.simpleName}"
        }
        return kind
    }

    companion object { const val MAX_PACKET = 2 * 1024 * 1024 }
}

/** Minimal RFC 6455 client: binary frames, client masking, ping/pong, close. */
internal object WebSocket {
    private val rng = SecureRandom()

    fun handshake(s: Socket, host: String, port: Int, path: String): Pair<InputStream, OutputStream> {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rng.nextBytes(it) })
        val hostHeader = if (port == 443 || port == 80) host else "$host:$port"
        val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n"
        val raw = s.getOutputStream()
        raw.write(req.toByteArray(Charsets.US_ASCII)); raw.flush()
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val c = input.read(); if (c < 0) throw EOFException("WebSocket handshake closed")
            head.append(c.toChar()); if (head.length > 8192) throw IllegalStateException("bad WebSocket reply")
        }
        val status = head.lineSequence().first()
        if (!status.contains(" 101")) throw IllegalStateException("WebSocket upgrade refused: $status")
        val expected = Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        val accept = head.lineSequence().firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
        if (accept != expected) throw IllegalStateException("WebSocket accept key mismatch")
        val out = FrameOut(raw)
        return FrameIn(input, out) to out
    }

    class FrameOut(private val raw: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = frame(0x2, b, off, len)
        @Synchronized fun frame(opcode: Int, b: ByteArray, off: Int, len: Int) {
            val h = ByteArrayOutputStream(len + 14)
            h.write(0x80 or opcode)
            when {
                len < 126 -> h.write(0x80 or len)
                len < 65536 -> { h.write(0x80 or 126); h.write(len ushr 8); h.write(len and 0xff) }
                else -> { h.write(0x80 or 127); for (i in 7 downTo 0) h.write(((len.toLong() ushr (8 * i)) and 0xff).toInt()) }
            }
            val mask = ByteArray(4).also { rng.nextBytes(it) }
            h.write(mask)
            for (i in 0 until len) h.write((b[off + i].toInt() xor mask[i and 3].toInt()) and 0xff)
            raw.write(h.toByteArray()); raw.flush()
        }
        override fun flush() = raw.flush()
    }

    class FrameIn(raw: InputStream, private val out: FrameOut) : InputStream() {
        private val din = DataInputStream(raw)
        private var buf = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            while (pos >= buf.size) nextFrame()
            return buf[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) nextFrame()
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n); pos += n
            return n
        }

        private fun nextFrame() {
            val b0 = din.readUnsignedByte(); val b1 = din.readUnsignedByte()
            val opcode = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = din.readUnsignedShort().toLong()
            else if (len == 127L) len = din.readLong()
            if (len < 0 || len > MiniMqttRelay.MAX_PACKET) throw IllegalStateException("WebSocket frame too large")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { din.readFully(it) } else null
            val data = ByteArray(len.toInt()); din.readFully(data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                0x0, 0x1, 0x2 -> { buf = data; pos = 0 }
                0x8 -> throw EOFException("WebSocket closed by relay")
                0x9 -> out.frame(0xA, data, 0, data.size) // ping -> pong
                else -> {} // pong / unknown: ignore
            }
        }
    }
}

/**
 * Uses several relays at once and delivers through every one that is reachable.
 * Two phones can talk as long as they share at least one working relay; duplicate
 * deliveries are dropped by the replay guard (same envelope id).
 */
class MultiRelay(val relays: List<MiniMqttRelay>) : Relay {
    override val connected = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    init {
        relays.forEach { r ->
            scope.launch { r.connected.collect { connected.value = relays.any { it.connected.value }; update() } }
            scope.launch { r.lastError.collect { update() } }
        }
    }

    private fun update() {
        lastError.value = if (relays.any { it.connected.value }) null
        else relays.mapNotNull { it.lastError.value }.distinct().joinToString("; ").ifBlank { null }
    }

    /** e.g. "broker.hivemq.com:8883, mqtt.eclipseprojects.io:443" */
    val connectedNames: String get() = relays.filter { it.connected.value }.joinToString { it.name }

    override fun start(inbox: String, onMessage: (ByteArray) -> Unit) = relays.forEach { it.start(inbox, onMessage) }
    override fun publish(topic: String, data: ByteArray, queueIfOffline: Boolean): Boolean =
        relays.filter { it.connected.value }.map { it.publish(topic, data, queueIfOffline) }.any { it }
    override fun stop() { relays.forEach { it.stop() }; scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    fun nudge() = relays.forEach { it.nudge() }
    fun keepAlive() = relays.forEach { it.keepAlive() }
}
