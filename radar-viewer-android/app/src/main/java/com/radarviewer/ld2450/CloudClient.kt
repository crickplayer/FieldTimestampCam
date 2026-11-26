package com.radarviewer.ld2450

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/** Remote: MQTT (EMQX) client. Plain Java, so it also runs in JVM tests. Callbacks arrive on Paho threads. */
class CloudClient(
    private val settings: AppSettings,
    private val tlsFactory: SSLSocketFactory?,
    private val listener: Listener,
) {
    interface Listener {
        fun onCloudConnected()
        fun onCloudMessage(topic: String, payload: String)
        fun onCloudLost(reason: String)
    }

    private var client: MqttAsyncClient? = null

    /** Port 1883 is plain MQTT; anything else (8883 on EMQX) is TLS. */
    val usesTls: Boolean get() = settings.mqttPort != 1883

    fun connect() {
        close()
        val uri = "${if (usesTls) "ssl" else "tcp"}://${settings.mqttHost.trim()}:${settings.mqttPort}"
        val c = MqttAsyncClient(uri, "radar-viewer-" + UUID.randomUUID().toString().take(8), MemoryPersistence())
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (client === c) listener.onCloudConnected()
            }

            override fun connectionLost(cause: Throwable?) {
                if (client === c) listener.onCloudLost(describe(cause))
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                if (client === c) listener.onCloudMessage(topic, String(message.payload, Charsets.UTF_8))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false  // RadarViewModel retries with a fresh client
            keepAliveInterval = 30
            connectionTimeout = 10
            if (settings.mqttUser.isNotBlank()) userName = settings.mqttUser.trim()
            if (settings.mqttPassword.isNotEmpty()) password = settings.mqttPassword.toCharArray()
            if (usesTls && tlsFactory != null) socketFactory = tlsFactory
        }
        client = c
        try {
            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (client === c) listener.onCloudLost(describe(exception))
                }
            })
        } catch (e: MqttException) {
            listener.onCloudLost(describe(e))
        }
    }

    fun subscribe(topic: String) = runQuietly { client?.subscribe(topic, 0) }
    fun unsubscribe(topic: String) = runQuietly { client?.unsubscribe(topic) }
    fun publish(topic: String, payload: String) =
        runQuietly { client?.publish(topic, payload.toByteArray(), 0, false) }

    val isConnected: Boolean get() = client?.isConnected == true

    fun close() {
        val c = client ?: return
        client = null
        Thread {
            try {
                if (c.isConnected) c.disconnectForcibly(500, 500)
                c.close(true)
            } catch (_: Exception) {
            }
        }.start()
    }

    private inline fun runQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Turns MQTT/TLS errors into something a person can act on. */
        fun describe(t: Throwable?): String {
            if (t == null) return "connection lost"
            var cause: Throwable? = t
            while (cause != null) {
                if (cause is SSLException) return "secure connection failed (${cause.message})"
                cause = cause.cause
            }
            if (t is MqttException) {
                return when (t.reasonCode.toShort()) {
                    MqttException.REASON_CODE_FAILED_AUTHENTICATION -> "wrong MQTT username or password"
                    MqttException.REASON_CODE_NOT_AUTHORIZED -> "MQTT user not authorised"
                    MqttException.REASON_CODE_SERVER_CONNECT_ERROR -> "cannot reach the broker"
                    MqttException.REASON_CODE_CLIENT_TIMEOUT -> "broker did not answer"
                    MqttException.REASON_CODE_CONNECTION_LOST -> "connection lost"
                    else -> t.message ?: "MQTT error ${t.reasonCode}"
                }
            }
            return t.message ?: t.javaClass.simpleName
        }
    }
}
