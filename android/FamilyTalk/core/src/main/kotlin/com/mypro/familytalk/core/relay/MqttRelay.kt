package com.mypro.familytalk.core.relay

import kotlinx.coroutines.flow.MutableStateFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.atomic.AtomicReference

/**
 * MQTT (TLS) relay. A persistent session (cleanSession=false, QoS 1) lets the broker
 * hold sealed chat messages while a phone is offline; call signalling uses QoS 0.
 * The client id is random per install and reveals nothing about the user.
 */
class MqttRelay(
    private val broker: String,
    private val clientId: String,
    private val user: String? = null,
    private val password: String? = null,
    private val log: (String) -> Unit = {},
) : Relay {
    override val connected = MutableStateFlow(false)
    private val client = AtomicReference<MqttAsyncClient?>()

    override fun start(inbox: String, onMessage: (ByteArray) -> Unit) {
        stop()
        require(broker.startsWith("ssl://") || broker.startsWith("wss://") || broker.startsWith("tcp://localhost")) {
            "Broker must use TLS (ssl:// or wss://)"
        }
        val c = MqttAsyncClient(broker, clientId, MemoryPersistence())
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                connected.value = true
                runCatching { c.subscribe(inbox, 1) }.onFailure { log("subscribe failed: ${it.message}") }
            }
            override fun connectionLost(cause: Throwable?) { connected.value = false; log("relay lost: ${cause?.message}") }
            override fun messageArrived(topic: String?, message: MqttMessage) {
                if (topic == inbox) runCatching { onMessage(message.payload) }.onFailure { log("handler: ${it.message}") }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isCleanSession = false
            isAutomaticReconnect = true
            keepAliveInterval = 45
            connectionTimeout = 15
            maxInflight = 64
            if (!user.isNullOrBlank()) userName = user
            val pw = this@MqttRelay.password
            if (!pw.isNullOrBlank()) setPassword(pw.toCharArray())
        }
        client.set(c)
        runCatching { c.connect(opts) }.onFailure { log("connect failed: ${it.message}") }
    }

    override fun publish(topic: String, data: ByteArray, queueIfOffline: Boolean) {
        val c = client.get() ?: return
        if (!c.isConnected) return
        runCatching { c.publish(topic, MqttMessage(data).apply { qos = if (queueIfOffline) 1 else 0 }) }
            .onFailure { log("publish failed: ${it.message}") }
    }

    override fun stop() {
        client.getAndSet(null)?.let { c -> runCatching { if (c.isConnected) c.disconnect(1000).waitForCompletion(1500) }; runCatching { c.close() } }
        connected.value = false
    }
}
