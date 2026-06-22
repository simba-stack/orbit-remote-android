package com.orbit.remote.data.signaling

import kotlinx.serialization.json.JsonObject

/** ICE server entry as advertised by the signaling server. */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

/** Events emitted by [SignalingClient] from the server / connection lifecycle. */
sealed interface SignalingEvent {
    data object Connecting : SignalingEvent
    data object Disconnected : SignalingEvent
    data class Welcome(val connId: String, val iceServers: List<IceServerConfig>) : SignalingEvent
    data class Registered(val deviceId: String, val code: String) : SignalingEvent
    data class PeerJoin(val sessionId: String, val role: String, val fromIp: String?) : SignalingEvent
    /** Relayed WebRTC payload (offer/answer/candidate) for [sessionId]. */
    data class Signal(val sessionId: String, val data: JsonObject) : SignalingEvent
    data class SessionEnd(val sessionId: String, val reason: String?) : SignalingEvent
    data class Error(val code: String) : SignalingEvent
}
