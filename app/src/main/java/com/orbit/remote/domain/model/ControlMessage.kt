package com.orbit.remote.domain.model

import kotlinx.serialization.Serializable

/**
 * Control commands sent by the desktop controller over the WebRTC data channel
 * and executed on the device by the accessibility service.
 *
 * Coordinates are normalised (0f..1f) relative to screen width/height so they are
 * independent of the controller's window size and the device resolution.
 */
@Serializable
data class ControlMessage(
    val type: String,
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long? = null,
    val text: String? = null,
    val key: String? = null,
    val packageName: String? = null
) {
    companion object {
        const val TAP = "tap"
        const val DOUBLE_TAP = "double_tap"
        const val LONG_PRESS = "long_press"
        const val SWIPE = "swipe"
        const val SCROLL = "scroll"
        const val TEXT = "text"
        const val KEY = "key"
        const val LAUNCH_APP = "launch_app"
        const val CLIPBOARD_SET = "clipboard_set"
        const val CLIPBOARD_GET = "clipboard_get"

        // Global key actions
        const val KEY_BACK = "back"
        const val KEY_HOME = "home"
        const val KEY_RECENTS = "recents"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_LOCK = "lock"
    }
}

/** Messages the agent sends back to the controller over the data channel. */
@Serializable
data class AgentEvent(
    val type: String,
    val text: String? = null
) {
    companion object {
        const val CLIPBOARD = "clipboard"
        const val ACK = "ack"
    }
}
