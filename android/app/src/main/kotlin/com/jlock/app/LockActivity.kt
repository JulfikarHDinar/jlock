package com.jlock.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.FrameLayout

/**
 * The lock screen for the USAGE-ACCESS engine: a full-screen Activity launched over a locked app by
 * [UsageAccessLockService]. Because it uses no accessibility, banking apps that refuse to run alongside
 * an accessibility service work normally. It reuses the shared [LockContent] UI.
 *
 * Unlocking marks the app unlocked for the session and finishes, revealing the app behind it. BACK goes
 * HOME and never reveals the app. If the user leaves without unlocking (HOME, screen off, app switch) it
 * finishes itself; the service relaunches it when the locked app comes forward again.
 */
class LockActivity : Activity() {
    companion object {
        /** The app this screen is guarding while it's resumed, so the service doesn't relaunch it. */
        @Volatile var showingFor: String? = null
        /** Set by the service as it asks for this screen, cleared once it is up. */
        @Volatile var pendingFor: String? = null
    }

    private var pkg: String = ""
    private var content: LockContent? = null
    private var unlocked = false
    private var reopen = false
    private var autoPrompt = false // fingerprint: open the phone's prompt once this lock is on screen
    private var prompting = false  // the phone's fingerprint prompt is open (never two at once)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val root = FrameLayout(this)
        setContentView(root)
        // Bars in the lock's own colour, with icons that contrast with it.
        LockColors.from(this).let { c ->
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = c.bg
            window.navigationBarColor = c.bg
            c.applyBarIcons(window.decorView)
        }
        content = LockContent(
            this, root,
            onUnlock = { onUnlocked() },
            onBiometric = { launchBiometric() },
        )
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it); handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val p = intent.getStringExtra("pkg") ?: ""
        reopen = intent.getBooleanExtra("reopen", false)
        if (p == pkg && !unlocked) return // the same lock asked for again: keep it as it is (digits typed, its prompt)
        pkg = p
        unlocked = false
        if (showingFor != null) showingFor = pkg
        content?.showFor(pkg, labelFor(pkg), iconFor(pkg))
        autoPrompt = content?.currentMethod == LockStore.FINGERPRINT
    }

    private fun onUnlocked() {
        if (unlocked) return
        unlocked = true
        LockStore.markUnlocked(pkg)
        if (reopen) try { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } } catch (_: Throwable) {}
        finish()
        overridePendingTransition(0, 0)
    }

    private fun launchBiometric() {
        if (prompting) return
        prompting = true
        BiometricAuth.authenticate(this, "Unlock ${labelFor(pkg)}", "Use your fingerprint", "Use another method",
            onFailed = { LockStore.logEvent(this, LockStore.LOG_FAIL, pkg) }) { ok ->
            prompting = false
            if (ok == true) onUnlocked() else if (ok == null) content?.useOtherMethod()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Never reveal the locked app via BACK — go HOME instead.
        goHome()
        finish()
    }

    override fun onResume() {
        super.onResume()
        showingFor = pkg
        pendingFor = null
        if (autoPrompt) { autoPrompt = false; window.decorView.post { if (!unlocked && !isFinishing) launchBiometric() } }
    }

    override fun onPause() {
        showingFor = null
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Left the lock without unlocking (HOME, screen off, app switch): finish so it doesn't linger;
        // the service relaunches it when the locked app comes forward again.
        if (!unlocked && !isFinishing) finish()
    }

    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Throwable) {}
    }

    private fun labelFor(p: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Throwable) { p }

    private fun iconFor(p: String): Drawable? =
        try { packageManager.getApplicationIcon(p) } catch (_: Throwable) { null }
}
