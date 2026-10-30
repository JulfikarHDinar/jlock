package com.jlock.app

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Thin wrapper over the framework BiometricPrompt (no androidx dependency — keeps jLock light). Used for
 * the "Fingerprint" method only.
 *
 * jLock credentials are deliberately independent of the device's own screen lock, so the prompt allows
 * BIOMETRIC_STRONG **only** (no DEVICE_CREDENTIAL). The negative button ("Use another method") returns to
 * jLock's lock screen on the user's own PIN/pattern/password — never the device PIN.
 */
object BiometricAuth {
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    /** A strong biometric (e.g. fingerprint) is enrolled and usable. */
    fun canFingerprint(c: Context): Boolean {
        val bm = c.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Show the system fingerprint prompt. [onResult] fires once: true when authenticated, null when the user taps
     * [negative] ("Use another method"), false on any other cancel or error. A single wrong finger keeps the prompt
     * open. Must be called from an Activity. [onFailed] fires for each finger that isn't recognised (for the log).
     */
    fun authenticate(
        activity: Context, title: String, subtitle: String, negative: String,
        onFailed: () -> Unit = {}, onResult: (Boolean?) -> Unit,
    ) {
        var done = false
        fun finish(r: Boolean?) { if (!done) { done = true; onResult(r) } }
        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(STRONG)
                // Android reports this button only here (not as an error): without it the caller never hears back.
                .setNegativeButton(negative, activity.mainExecutor) { _, _ -> finish(null) }
                .build()
            prompt.authenticate(CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true)
                    override fun onAuthenticationFailed() {
                        try { onFailed() } catch (_: Throwable) {}
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(false)
                })
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
