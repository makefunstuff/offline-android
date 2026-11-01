package com.lfmpixel.lfm.device

import android.content.Context
import android.hardware.biometrics.BiometricManager
import com.lfmpixel.lfm.settings.LockMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The optional lock (spec section 3): the whole app or only the journal, opened with the phone's own fingerprint,
 * face or screen lock. Once opened it stays open while Wren is in use, and closes again when Wren has been
 * out of sight for [GRACE_MS] (long enough to finish a hand-off in Messages and come back).
 */
class AppLock(private val context: Context) {
    /** The owner's choice, mirrored from Settings by the app graph. */
    @Volatile var mode: LockMode = LockMode.Off
        set(value) { field = value; if (value == LockMode.Off) _open.value = true }
    // Closed until the saved mode is known (start): with the lock on, nothing shows before it is opened.
    private val _open = MutableStateFlow(false)
    /** Opened since Wren last came on screen (always true with the lock off). */
    val open: StateFlow<Boolean> = _open
    private val _closings = MutableStateFlow(0)
    /** Counts each time the lock closed after a time away, even if it was not open: "it locked while you were here". */
    val closings: StateFlow<Int> = _closings
    @Volatile private var hiddenAt = 0L
    @Volatile private var hidden = false
    /** Asks the owner to unlock (BiometricPrompt, in the visible activity); null when Wren is not on screen. */
    var prompt: (suspend (title: String) -> Boolean)? = null

    /** Whether the phone has a fingerprint, face or screen lock to open Wren with. */
    fun canLock(): Boolean = context.getSystemService(BiometricManager::class.java)
        ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Whether [what] is behind the lock now. Away from the screen the lock counts as closed once the grace time has
     * passed, so an answer still running in the background no longer reads the journal.
     */
    fun locks(what: LockMode, now: Long = System.currentTimeMillis()): Boolean =
        mode != LockMode.Off && (mode == LockMode.App || mode == what) &&
            (!_open.value || (hidden && shouldClose(mode, hiddenAt, now)))

    /** Wren left the screen. */
    fun onHidden(now: Long = System.currentTimeMillis()) { hiddenAt = now; hidden = true }

    /** Wren is on screen again: closed if it was away too long (or never opened since start). */
    fun onShown(now: Long = System.currentTimeMillis()) {
        hidden = false
        if (shouldClose(mode, hiddenAt, now)) { _open.value = false; _closings.value++ }
    }

    /** Called when the app starts: a locked mode starts closed. */
    fun start(m: LockMode) { mode = m; _open.value = m == LockMode.Off }

    suspend fun unlock(title: String): Boolean {
        if (mode == LockMode.Off || _open.value) return true
        val ok = prompt?.invoke(title) == true
        if (ok) _open.value = true
        return ok
    }

    /** Settings: turning the lock on asks first, so no one can lock the owner out with a lock they can't open. */
    suspend fun confirm(title: String): Boolean = (prompt?.invoke(title) == true).also { if (it) _open.value = true }

    companion object {
        const val GRACE_MS = 60_000L
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        fun shouldClose(mode: LockMode, hiddenAt: Long, now: Long): Boolean =
            mode != LockMode.Off && hiddenAt > 0 && now - hiddenAt >= GRACE_MS
    }
}
