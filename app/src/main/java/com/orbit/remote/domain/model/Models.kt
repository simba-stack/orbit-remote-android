package com.orbit.remote.domain.model

/** Connection state of the agent to the signaling server / a controller. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    REGISTERED,   // connected to signaling, has a device id, waiting
    IN_SESSION,   // a controller is connected and streaming
    ERROR
}

/** Identity assigned to this device by the signaling server. */
data class DeviceIdentity(
    val deviceId: String,
    val code: String
)

/** Static + live device information shown in the UI and reported to controllers. */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val batteryPercent: Int,
    val usedMemoryMb: Long,
    val totalMemoryMb: Long,
    val ipAddress: String?
)

/** Aggregate UI state for the agent. */
data class AgentState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val identity: DeviceIdentity? = null,
    val sessionId: String? = null,
    val controllerInfo: String? = null,
    val lastConnectedAt: Long? = null,
    val errorMessage: String? = null,
    val mediaProjectionGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
)
