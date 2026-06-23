package com.orbit.remote.data.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket signaling client. Connects to the Orbit signaling server, parses the
 * JSON protocol into [SignalingEvent]s and auto-reconnects with exponential backoff.
 * After every (re)connection it re-sends the pending registration so the agent keeps
 * its device id across network drops.
 */
class SignalingClient(
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var url: String? = null
    private val manuallyClosed = AtomicBoolean(false)
    private var reconnectAttempts = 0

    // Registration parameters, re-applied on every reconnect.
    private data class Registration(val deviceId: String?, val code: String?, val name: String, val platform: String)
    @Volatile private var pendingRegistration: Registration? = null

    fun connect(url: String) {
        this.url = url
        manuallyClosed.set(false)
        openSocket()
    }

    private fun openSocket() {
        val target = url ?: return
        _events.tryEmit(SignalingEvent.Connecting)
        val request = Request.Builder().url(target).build()
        webSocket = httpClient.newWebSocket(request, listener)
    }

    fun setRegistration(deviceId: String?, code: String?, name: String, platform: String) {
        pendingRegistration = Registration(deviceId, code, name, platform)
        sendRegistration()
    }

    private fun sendRegistration() {
        val reg = pendingRegistration ?: return
        val obj = buildJsonObject {
            put("type", JsonPrimitive("register"))
            put("role", JsonPrimitive("agent"))
            reg.deviceId?.let { put("deviceId", JsonPrimitive(it)) }
            reg.code?.let { put("code", JsonPrimitive(it)) }
            put("name", JsonPrimitive(reg.name))
            put("platform", JsonPrimitive(reg.platform))
        }
        send(obj)
    }

    fun sendSignal(sessionId: String, data: JsonObject) {
        send(buildJsonObject {
            put("type", JsonPrimitive("signal"))
            put("sessionId", JsonPrimitive(sessionId))
            put("data", data)
        })
    }

    fun hangup(sessionId: String) {
        send(buildJsonObject {
            put("type", JsonPrimitive("hangup"))
            put("sessionId", JsonPrimitive(sessionId))
        })
    }

    private fun send(obj: JsonObject) {
        webSocket?.send(json.encodeToString(JsonObject.serializer(), obj))
    }

    fun disconnect() {
        manuallyClosed.set(true)
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            reconnectAttempts = 0
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
            when (type) {
                "welcome" -> {
                    val connId = obj["connId"]?.jsonPrimitive?.contentOrNull ?: ""
                    _events.tryEmit(SignalingEvent.Welcome(connId, parseIceServers(obj)))
                    // Re-register automatically after (re)connect.
                    sendRegistration()
                }
                "registered" -> _events.tryEmit(
                    SignalingEvent.Registered(
                        obj["deviceId"]?.jsonPrimitive?.contentOrNull ?: "",
                        obj["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                )
                "peer-join" -> _events.tryEmit(
                    SignalingEvent.PeerJoin(
                        obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: "",
                        obj["role"]?.jsonPrimitive?.contentOrNull ?: "controller",
                        obj["from"]?.jsonObject?.get("ip")?.jsonPrimitive?.contentOrNull
                    )
                )
                "signal" -> {
                    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                    val data = obj["data"]?.jsonObject ?: return
                    _events.tryEmit(SignalingEvent.Signal(sessionId, data))
                }
                "session-end" -> _events.tryEmit(
                    SignalingEvent.SessionEnd(
                        obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: "",
                        obj["reason"]?.jsonPrimitive?.contentOrNull
                    )
                )
                "error" -> _events.tryEmit(
                    SignalingEvent.Error(obj["code"]?.jsonPrimitive?.contentOrNull ?: "unknown")
                )
                "pong" -> Unit
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            _events.tryEmit(SignalingEvent.Disconnected)
            scheduleReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            _events.tryEmit(SignalingEvent.Disconnected)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed.get()) return
        reconnectAttempts++
        val backoff = minOf(30_000L, 1_000L * (1L shl minOf(reconnectAttempts, 5)))
        scope.launch {
            delay(backoff)
            if (!manuallyClosed.get()) openSocket()
        }
    }

    private fun parseIceServers(obj: JsonObject): List<IceServerConfig> {
        val arr = obj["iceServers"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val urls = o["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return@mapNotNull null
            IceServerConfig(
                urls = urls,
                username = o["username"]?.jsonPrimitive?.contentOrNull,
                credential = o["credential"]?.jsonPrimitive?.contentOrNull
            )
        }
    }
}
