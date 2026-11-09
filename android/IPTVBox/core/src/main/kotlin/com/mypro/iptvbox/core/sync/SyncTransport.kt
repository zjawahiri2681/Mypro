package com.mypro.iptvbox.core.sync

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reads one line, giving up on lines longer than [max] chars so a peer cannot exhaust memory. */
internal fun BufferedReader.readBoundedLine(max: Int = 16_384): String? {
    val sb = StringBuilder()
    while (true) {
        val c = read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString()
        if (c != '\r'.code) sb.append(c.toChar())
        if (sb.length > max) throw java.io.IOException("Line too long")
    }
}

/** Delivers encrypted [SyncMessage]s between participants. Callbacks arrive on background threads. */
interface SyncTransport {
    var onMessage: (SyncMessage) -> Unit
    var onStatus: (String) -> Unit
    fun start()
    fun send(msg: SyncMessage)
    fun stop()
    val isConnected: Boolean
}

/** Internet mode: public MQTT broker as a dumb relay. Payload is AES-GCM encrypted. */
class MqttTransport(
    private val brokerUri: String,
    private val room: String,
    private val crypto: SyncCrypto,
    private val selfId: String,
) : SyncTransport {
    override var onMessage: (SyncMessage) -> Unit = {}
    override var onStatus: (String) -> Unit = {}
    private var client: MqttClient? = null
    private val topic = "iptvbox/v1/room/$room"
    private val exec = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)

    override val isConnected: Boolean get() = client?.isConnected == true

    override fun start() {
        exec.execute {
            try {
                val c = MqttClient(brokerUri, "iptvbox-$selfId", MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 15
                    keepAliveInterval = 30
                }
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        onStatus(if (reconnect) "Reconnected to relay" else "Connected to relay")
                        try { c.subscribe(topic, 1) } catch (e: Exception) { onStatus("Subscribe failed: ${e.message}") }
                    }
                    override fun connectionLost(cause: Throwable?) { onStatus("Relay connection lost, reconnecting…") }
                    override fun messageArrived(t: String?, message: MqttMessage) {
                        val plain = crypto.decrypt(String(message.payload, Charsets.UTF_8)) ?: return
                        val msg = runCatching { SyncMessage.decode(plain) }.getOrNull() ?: return
                        if (msg.from != selfId) onMessage(msg)
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                onStatus("Connecting to relay…")
                c.connect(opts)
                client = c
            } catch (e: Exception) {
                onStatus("Relay error: ${e.message}")
            }
        }
    }

    override fun send(msg: SyncMessage) {
        if (stopped.get()) return
        exec.execute {
            try {
                val c = client ?: return@execute
                if (!c.isConnected) return@execute
                c.publish(topic, MqttMessage(crypto.encrypt(msg.encode()).toByteArray(Charsets.UTF_8)).apply { qos = 1; isRetained = false })
            } catch (e: Exception) {
                onStatus("Send failed: ${e.message}")
            }
        }
    }

    override fun stop() {
        stopped.set(true)
        exec.execute {
            try { client?.disconnectForcibly(1000) } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
            client = null
        }
        exec.shutdown()
    }
}

/** LAN mode, host side: accepts TCP clients and relays every message to all others. */
class LanHostTransport(
    private val port: Int,
    private val crypto: SyncCrypto,
    private val selfId: String,
) : SyncTransport {
    override var onMessage: (SyncMessage) -> Unit = {}
    override var onStatus: (String) -> Unit = {}
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Peer>()
    private val running = AtomicBoolean(false)
    private val exec = Executors.newCachedThreadPool()

    override val isConnected: Boolean get() = running.get()
    val clientCount: Int get() = clients.size

    private inner class Peer(val socket: Socket) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        @Synchronized fun write(line: String) { writer.write(line); writer.write("\n"); writer.flush() }
    }

    override fun start() {
        exec.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                running.set(true)
                onStatus("Hosting on port $port")
                while (running.get()) {
                    val s = try { ss.accept() } catch (e: Exception) { break }
                    s.tcpNoDelay = true
                    val peer = Peer(s)
                    clients += peer
                    onStatus("Viewer joined (${clients.size} connected)")
                    exec.execute { readLoop(peer) }
                }
            } catch (e: Exception) {
                onStatus("Host error: ${e.message}")
            }
        }
    }

    private fun readLoop(peer: Peer) {
        try {
            val reader = BufferedReader(InputStreamReader(peer.socket.getInputStream(), Charsets.UTF_8))
            while (running.get()) {
                val line = reader.readBoundedLine() ?: break
                val plain = crypto.decrypt(line) ?: continue
                val msg = runCatching { SyncMessage.decode(plain) }.getOrNull() ?: continue
                // relay to other viewers so they can see each other's commands
                for (p in clients) if (p !== peer) runCatching { p.write(line) }
                if (msg.from != selfId) onMessage(msg)
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(peer)
            runCatching { peer.socket.close() }
            onStatus("Viewer left (${clients.size} connected)")
        }
    }

    override fun send(msg: SyncMessage) {
        if (!running.get()) return
        val line = crypto.encrypt(msg.encode())
        exec.execute { for (p in clients) runCatching { p.write(line) }.onFailure { clients.remove(p) } }
    }

    override fun stop() {
        running.set(false)
        runCatching { server?.close() }
        for (p in clients) runCatching { p.socket.close() }
        clients.clear()
        exec.shutdownNow()
    }
}

/** LAN mode, viewer side. */
class LanClientTransport(
    private val host: String,
    private val port: Int,
    private val crypto: SyncCrypto,
    private val selfId: String,
) : SyncTransport {
    override var onMessage: (SyncMessage) -> Unit = {}
    override var onStatus: (String) -> Unit = {}
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val running = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    private val readerExec = Executors.newSingleThreadExecutor()

    override val isConnected: Boolean get() = socket?.isConnected == true && running.get()

    override fun start() {
        running.set(true)
        readerExec.execute {
            var attempt = 0
            while (running.get()) {
                try {
                    onStatus(if (attempt == 0) "Connecting to $host…" else "Reconnecting to $host…")
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 8000)
                    s.tcpNoDelay = true
                    socket = s
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                    onStatus("Connected to host")
                    attempt = 0
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    while (running.get()) {
                        val line = reader.readBoundedLine() ?: break
                        val plain = crypto.decrypt(line) ?: continue
                        val msg = runCatching { SyncMessage.decode(plain) }.getOrNull() ?: continue
                        if (msg.from != selfId) onMessage(msg)
                    }
                } catch (e: Exception) {
                    onStatus("Connection failed: ${e.message}")
                }
                runCatching { socket?.close() }
                socket = null
                if (!running.get()) break
                attempt++
                Thread.sleep((2000L * attempt).coerceAtMost(10_000L))
            }
        }
    }

    override fun send(msg: SyncMessage) {
        if (!running.get()) return
        val line = crypto.encrypt(msg.encode())
        exec.execute {
            try {
                val w = writer ?: return@execute
                synchronized(w) { w.write(line); w.write("\n"); w.flush() }
            } catch (e: Exception) {
                onStatus("Send failed: ${e.message}")
            }
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        exec.shutdownNow()
        readerExec.shutdownNow()
    }
}
