package com.jlock.app

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Choreographer

/**
 * A no-UI Activity whose only job is to host the system BiometricPrompt over a locked app. The lock overlay sits
 * above the prompt, so this screen first covers the app in the lock's own colors, then tells the overlay to step
 * aside ([covering]) and raises the prompt — the app is never shown. Delivers the result to [pending] exactly
 * once and finishes.
 *
 * Each launch carries a number ([gen]); a screen whose number is no longer the overlay's (the overlay gave up on it,
 * or started another) closes without prompting or reporting, so a stale one can never hold up the next try.
 */
class BiometricProxyActivity : Activity() {
    companion object {
        /** The try the overlay is waiting for; bumped by the overlay to drop one. */
        @Volatile
        var gen = 0

        /** Set by the overlay just before launch; invoked once with the result (true = unlocked, null = "Use
         *  another method", false = cancelled), then cleared. */
        @Volatile
        var pending: ((Boolean?) -> Unit)? = null

        /** Set by the overlay; called once this screen covers the app. */
        @Volatile
        var covering: (() -> Unit)? = null

        /** True once this screen has stopped (the user left: HOME, Recents, screen off), so a cancel then must
         *  not put the lock back up over whatever they went to. Still false while a system prompt is over it. */
        @Volatile
        var stopped = false

        private var current: java.lang.ref.WeakReference<BiometricProxyActivity>? = null

        /** Close the prompt screen if one is open (its try was dropped or is no longer wanted). */
        fun cancel() { current?.get()?.let { if (!it.isFinishing) it.finish() } }
    }

    private var myGen = -1
    private var delivered = false
    private var started = false

    private val live get() = myGen == gen && !isFinishing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(LockColors.from(this).bg))
        myGen = intent.getIntExtra("gen", -1)
        stopped = false
        current?.get()?.let { if (it !== this && !it.isFinishing) it.finish() } // only ever one
        current = java.lang.ref.WeakReference(this)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    override fun onResume() {
        super.onResume()
        if (!live) { finish(); return }
        // Don't wait for window focus: the lock window above holds input focus, so this screen may never get it
        // while the lock is up (the prompt then stayed hidden under the lock). Once this screen has drawn — it now
        // covers the app — take the lock down and raise the prompt.
        window.decorView.post { Choreographer.getInstance().postFrameCallback { begin() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) begin()
    }

    override fun onStop() {
        if (!isFinishing) stopped = true
        super.onStop()
    }

    private fun begin() {
        if (started || !live) return
        started = true
        covering?.invoke()
        covering = null
        val label = intent.getStringExtra("label") ?: "this app"
        val pkg = intent.getStringExtra("pkg") ?: ""
        BiometricAuth.authenticate(this, "Unlock $label", "Use your fingerprint", "Use another method",
            onFailed = { LockStore.logEvent(this, LockStore.LOG_FAIL, pkg) }) { ok -> deliver(ok) }
    }

    private fun deliver(ok: Boolean?) {
        if (delivered) return
        delivered = true
        if (myGen == gen) {
            val cb = pending
            pending = null
            cb?.invoke(ok)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Android closes the system prompt itself once this screen is gone (also cancelling it here crashed System UI
        // on Android 13 when both happened at once). Safety net: leaving without a result reports a cancel, so the
        // overlay never hangs.
        if (!delivered) deliver(false)
        super.onDestroy()
    }
}
