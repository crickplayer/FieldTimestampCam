package com.radarviewer.ld2450

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Home network: WebSocket to the ESP32 (ws://<ip>:81/). Callbacks arrive on OkHttp threads. */
class LanClient(private val listener: Listener) {
    interface Listener {
        fun onLanOpen(host: String)
        fun onLanMessage(host: String, text: String)
        fun onLanClosed(host: String, reason: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)  // detects a dead connection when the ESP32 goes away
        .build()

    private var socket: WebSocket? = null
    @Volatile var host: String? = null
        private set

    fun connect(host: String) {
        close()
        this.host = host
        val request = Request.Builder().url("ws://$host:81/").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket === socket) listener.onLanOpen(host)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === socket) listener.onLanMessage(host, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) listener.onLanClosed(host, "closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === socket) listener.onLanClosed(host, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    fun close() {
        val s = socket
        socket = null
        host = null
        s?.cancel()
    }
}
