package com.orbit.remote.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.orbit.remote.domain.model.ControlMessage
import kotlin.math.max

/**
 * Executes remote control commands on the device using the official Accessibility
 * APIs: [dispatchGesture] for taps/swipes/long-press and [performGlobalAction] for
 * navigation keys. Text is injected into the focused editable node.
 *
 * The running instance is exposed via [instance] so the streaming service can route
 * data-channel commands here.
 */
class OrbitAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        var instance: OrbitAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Do NOT null `instance` here: onUnbind can fire while the service is still
        // alive (e.g. transient rebind), which would silently kill control.
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }
    override fun onInterrupt() {}

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    /** Entry point for control commands coming from the controller. */
    fun execute(msg: ControlMessage) {
        main.post { dispatch(msg) }
    }

    private fun dispatch(msg: ControlMessage) {
        val w = screenWidth()
        val h = screenHeight()
        when (msg.type) {
            ControlMessage.TAP -> {
                tap((msg.x ?: 0f) * w, (msg.y ?: 0f) * h, 50)
            }
            ControlMessage.DOUBLE_TAP -> {
                val px = (msg.x ?: 0f) * w
                val py = (msg.y ?: 0f) * h
                tap(px, py, 40)
                main.postDelayed({ tap(px, py, 40) }, 120)
            }
            ControlMessage.LONG_PRESS -> {
                tap((msg.x ?: 0f) * w, (msg.y ?: 0f) * h, msg.durationMs ?: 600)
            }
            ControlMessage.SWIPE, ControlMessage.SCROLL -> {
                swipe(
                    (msg.x ?: 0f) * w, (msg.y ?: 0f) * h,
                    (msg.x2 ?: 0f) * w, (msg.y2 ?: 0f) * h,
                    msg.durationMs ?: 250
                )
            }
            ControlMessage.TEXT -> inputText(msg.text ?: "")
            ControlMessage.KEY -> globalKey(msg.key)
            ControlMessage.LAUNCH_APP -> launchApp(msg.packageName)
            ControlMessage.CLIPBOARD_SET -> setClipboard(msg.text ?: "")
        }
    }

    // dispatchGesture cannot run two gestures at once — a second call while one is
    // in flight is silently dropped, so bunched taps get lost. We serialize them in
    // a small queue and release strictly BY TIME (the gesture's own duration + a
    // margin). This is deliberately NOT tied to the completion callback, because a
    // missed callback would otherwise wedge the queue and block all input.
    private val gestureQueue = ArrayDeque<Pair<GestureDescription, Long>>()
    private var gestureRunning = false

    private fun enqueueGesture(gesture: GestureDescription, durationMs: Long) {
        gestureQueue.addLast(gesture to durationMs)
        if (gestureQueue.size > 24) gestureQueue.removeFirst() // drop stale under burst
        pumpGestures()
    }

    private fun pumpGestures() {
        if (gestureRunning) return
        val next = gestureQueue.removeFirstOrNull() ?: return
        gestureRunning = true
        runCatching { dispatchGesture(next.first, null, null) }
        main.postDelayed({ gestureRunning = false; pumpGestures() }, next.second + 60)
    }

    private fun tap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val dur = max(1, durationMs)
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        enqueueGesture(GestureDescription.Builder().addStroke(stroke).build(), dur)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val dur = max(1, durationMs)
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        enqueueGesture(GestureDescription.Builder().addStroke(stroke).build(), dur)
    }

    private fun globalKey(key: String?) {
        val action = when (key) {
            ControlMessage.KEY_BACK -> GLOBAL_ACTION_BACK
            ControlMessage.KEY_HOME -> GLOBAL_ACTION_HOME
            ControlMessage.KEY_RECENTS -> GLOBAL_ACTION_RECENTS
            ControlMessage.KEY_NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            ControlMessage.KEY_LOCK -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return
        }
        performGlobalAction(action)
    }

    private fun inputText(text: String) {
        val focused = findFocusedEditable(rootInActiveWindow) ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { f ->
            if (f.isEditable) return f
            @Suppress("DEPRECATION") runCatching { f.recycle() }
        }
        // Fallback: breadth-first search for an editable node. Recycle every node we
        // visit but do not return, to avoid leaking native AccessibilityNodeInfo
        // objects on every command (pre-API-33).
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isFocused) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (node !== root) @Suppress("DEPRECATION") runCatching { node.recycle() }
        }
        return null
    }

    /**
     * Paste text coming from the PC into the currently focused editable field.
     *
     * This is the OEM-proof path: it writes straight into the field via
     * ACTION_SET_TEXT (no system clipboard, no keyboard needed), so it works on
     * locked-down ROMs like realme/ColorOS where background clipboard writes and the
     * IME input-connection are unreliable. The existing field text is preserved and
     * the pasted text is appended, then the cursor is moved to the end.
     *
     * [onResult] is invoked on the main thread: true when a focused editable field
     * was found and updated, false when there was no field to paste into (so the UI
     * can tell the user to tap a text field first).
     */
    fun pasteFromPc(text: String, onResult: (Boolean) -> Unit) {
        main.post {
            val focused = findFocusedEditable(rootInActiveWindow)
            if (focused == null) {
                onResult(false)
                return@post
            }
            val existing = focused.text?.toString() ?: ""
            val combined = if (existing.isEmpty()) text else existing + text
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    combined
                )
            }
            val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            // Re-read the field: some inputs truncate at maxLength, so combined.length
            // may overshoot and make ACTION_SET_SELECTION fail.
            val newLen = focused.text?.length ?: combined.length
            runCatching {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newLen)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newLen)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
            // Best-effort: also drop it into the system clipboard for manual paste.
            runCatching { setClipboard(text) }
            onResult(ok)
        }
    }

    private fun launchApp(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun setClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("orbit", text))
    }

    fun readClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    }
}
