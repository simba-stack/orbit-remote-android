package com.orbit.remote.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Minimal input method (keyboard) used only to bridge the system clipboard while
 * remote control is active.
 *
 * Android forbids background apps from writing the system clipboard, but an IME is
 * exempt. When the user selects "Orbit Keyboard", this service runs and the
 * streaming service routes clipboard-set / clipboard-get through it, so text copied
 * on the PC lands in the phone's real clipboard (paste chip works system-wide).
 *
 * Text typing itself still goes through the accessibility service; this IME does not
 * interfere with it. The running instance is exposed via [instance].
 */
class OrbitImeService : InputMethodService() {

    companion object {
        @Volatile
        var instance: OrbitImeService? = null
            private set

        fun isActive(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Simple informational bar shown in place of a keyboard. */
    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "Orbit Remote — управление с ПК. Буфер обмена синхронизируется."
            setPadding(48, 40, 48, 40)
            gravity = Gravity.CENTER
            setTextColor(0xFFE8EDF6.toInt())
            setBackgroundColor(0xFF0A0D14.toInt())
        }
    }

    /** Insert text at the cursor of the focused field (used as a fallback path). */
    fun commitRemoteText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    fun backspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    /** Set the system clipboard — allowed because this is an IME. */
    fun setClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("orbit", text))
    }

    fun readClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    }
}
