package com.orbit.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.orbit.remote.R
import com.orbit.remote.accessibility.OrbitAccessibilityService
import com.orbit.remote.data.device.DeviceInfoProvider
import com.orbit.remote.data.settings.SettingsStore
import com.orbit.remote.data.signaling.IceServerConfig
import com.orbit.remote.data.signaling.SignalingClient
import com.orbit.remote.data.signaling.SignalingEvent
import com.orbit.remote.domain.model.AgentEvent
import com.orbit.remote.domain.model.ConnectionState
import com.orbit.remote.domain.model.ControlMessage
import com.orbit.remote.ime.OrbitImeService
import com.orbit.remote.ui.MainActivity
import com.orbit.remote.webrtc.WebRtcManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.webrtc.EglBase
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ScreenCaptureService : LifecycleService() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var deviceInfo: DeviceInfoProvider
    @Inject lateinit var stateHolder: AgentStateHolder
    @Inject lateinit var json: Json

    private lateinit var eglBase: EglBase

    private var projectionData: Intent? = null
    private var iceServers: List<IceServerConfig> = emptyList()
    private var webRtc: WebRtcManager? = null
    private var currentSessionId: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private fun toast(text: String) {
        uiHandler.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val ACTION_START = "com.orbit.remote.START"
        const val ACTION_STOP = "com.orbit.remote.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val CHANNEL_ID = "orbit_session"
        private const val NOTIFICATION_ID = 1001
        // Long-edge cap. 1280 keeps phone UI/text readable while roughly halving
        // pixel count vs 1080p — noticeably lower end-to-end latency over a relay.
        private const val MAX_DIMEN = 1280

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }

    override fun onCreate() {
        super.onCreate()
        // Use the process-shared EglBase (same one the shared PeerConnectionFactory
        // is built on) so capture and the factory never mix EGL contexts.
        eglBase = WebRtcManager.sharedEglBase()
        observeSignaling()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopEverything()
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        startForegroundCompat()
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        if (data != null) projectionData = data
        connectSignaling()
    }

    private fun connectSignaling() {
        lifecycleScope.launch {
            val url = settings.signalingUrl.first()
            // Fall back to a stable device-derived id/code so they never reset on
            // reinstall or server restart (the agent proposes them; the server keeps
            // the same values).
            val savedId = settings.deviceId.first()?.takeIf { it.isNotBlank() }
                ?: deviceInfo.stableDeviceId()
            val savedCode = settings.code.first()?.takeIf { it.isNotBlank() }
                ?: deviceInfo.stableCode()
            signalingClient.connect(url)
            signalingClient.setRegistration(
                deviceId = savedId,
                code = savedCode,
                name = deviceInfo.displayName(),
                platform = "android"
            )
            stateHolder.update { it.copy(connection = ConnectionState.CONNECTING) }
        }
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connecting ->
                        stateHolder.update { it.copy(connection = ConnectionState.CONNECTING) }

                    is SignalingEvent.Welcome -> iceServers = event.iceServers

                    is SignalingEvent.Registered -> {
                        settings.saveIdentity(event.deviceId, event.code)
                        stateHolder.update {
                            it.copy(
                                connection = ConnectionState.REGISTERED,
                                identity = com.orbit.remote.domain.model.DeviceIdentity(
                                    event.deviceId, event.code
                                ),
                                errorMessage = null
                            )
                        }
                    }

                    is SignalingEvent.PeerJoin -> onPeerJoin(event)

                    is SignalingEvent.Signal -> webRtc?.handleRemoteSignal(event.data)

                    is SignalingEvent.SessionEnd -> endSession()

                    is SignalingEvent.Disconnected ->
                        stateHolder.update { it.copy(connection = ConnectionState.CONNECTING) }

                    is SignalingEvent.Error ->
                        stateHolder.update { it.copy(errorMessage = event.code) }
                }
            }
        }
    }

    private fun onPeerJoin(event: SignalingEvent.PeerJoin) {
        val data = projectionData ?: run {
            stateHolder.update { it.copy(errorMessage = "screen_permission_missing") }
            return
        }
        // Tear down any previous peer (controller reconnect / second controller).
        // Use close() NOT release(): close() frees the PeerConnection/capturer but
        // keeps the native factory alive. Disposing+recreating the factory per
        // session crashes the native WebRTC layer.
        webRtc?.close()
        webRtc = null
        currentSessionId = event.sessionId

        val manager = WebRtcManager(
            context = applicationContext,
            eglBase = eglBase,
            iceServers = iceServers,
            json = json,
            onLocalSignal = { signalData ->
                currentSessionId?.let { signalingClient.sendSignal(it, signalData) }
            },
            onControlMessage = { msg ->
                if (msg.type == ControlMessage.CLIPBOARD_SET) {
                    val text = msg.text ?: ""
                    val acc = OrbitAccessibilityService.instance
                    val ime = OrbitImeService.instance
                    when {
                        // Most reliable on normal fields: write straight into the
                        // focused node via the accessibility service (OEM-proof).
                        acc != null -> acc.pasteFromPc(text) { ok ->
                            when {
                                ok -> toast("Вставлено с ПК")
                                // Accessibility can't set text on WebView/password
                                // fields — fall back to the IME which can.
                                ime != null -> {
                                    ime.setClipboard(text)
                                    ime.commitRemoteText(text)
                                    toast("Вставлено с ПК")
                                }
                                else -> toast("Поставьте курсор в поле на телефоне")
                            }
                        }
                        // No accessibility service: try the IME path.
                        ime != null -> {
                            ime.setClipboard(text)
                            ime.commitRemoteText(text)
                            toast("Вставлено с ПК")
                        }
                        else -> toast("Включите доступ Orbit для вставки с ПК")
                    }
                } else {
                    OrbitAccessibilityService.instance?.execute(msg)
                }
            },
            onClipboardRequest = {
                // ClipboardManager must be read on the main thread (Android 10+),
                // and this callback fires on the WebRTC data-channel thread.
                uiHandler.post {
                    val text = OrbitImeService.instance?.readClipboard()
                        ?: OrbitAccessibilityService.instance?.readClipboard() ?: ""
                    webRtc?.sendAgentEvent(AgentEvent(AgentEvent.CLIPBOARD, text))
                }
            }
        )
        webRtc = manager

        val (w, h) = captureDimensions()
        val started = runCatching { manager.start(data, w, h, 30) }
            .onFailure { android.util.Log.e("Orbit", "capture start failed", it) }
            .isSuccess
        if (!started) {
            // Capture failed this attempt. Clean up the half-built session but KEEP
            // projectionData so the next connect can retry without re-approval.
            webRtc?.close()
            webRtc = null
            stateHolder.update { it.copy(errorMessage = "capture_failed") }
            return
        }

        stateHolder.update {
            it.copy(
                connection = ConnectionState.IN_SESSION,
                sessionId = event.sessionId,
                controllerInfo = event.fromIp,
                lastConnectedAt = System.currentTimeMillis()
            )
        }
    }

    private fun endSession() {
        webRtc?.close()
        webRtc = null
        currentSessionId = null
        stateHolder.update {
            it.copy(connection = ConnectionState.REGISTERED, sessionId = null, controllerInfo = null)
        }
    }

    private fun captureDimensions(): Pair<Int, Int> {
        // Use resources.displayMetrics for the CAPTURE size — it's the value that
        // ScreenCapturerAndroid accepts on every device (some OEM encoders reject the
        // raw physical size). Tap accuracy does NOT depend on this: the capturer
        // mirrors the whole display into this frame, so coordinates stay proportional;
        // the accessibility mapping uses the full physical size (DisplayMetricsCompat)
        // to land taps correctly regardless of the capture resolution.
        val metrics = resources.displayMetrics
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val longer = maxOf(w, h)
        if (longer > MAX_DIMEN) {
            val scale = MAX_DIMEN.toFloat() / longer
            w = (w * scale).roundToInt()
            h = (h * scale).roundToInt()
        }
        // Encoders prefer even dimensions.
        return Pair(w - (w % 2), h - (h % 2))
    }

    private fun stopEverything() {
        endSession()
        signalingClient.disconnect()
        stateHolder.update { it.copy(connection = ConnectionState.DISCONNECTED) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        webRtc?.release()
        webRtc = null
        // Do NOT release eglBase: it is process-shared and reused by later sessions.
        super.onDestroy()
    }

    // ---- Foreground notification -----------------------------------------

    private fun startForegroundCompat() {
        createChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}
