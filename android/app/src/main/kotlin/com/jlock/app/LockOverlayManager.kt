package com.jlock.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the lock overlay window for both engines. The lock UI itself is the shared [LockContent]; this class
 * adds/removes the window and runs the biometric prompt via a proxy Activity.
 *
 * Accessibility engine: an ACCESSIBILITY overlay — Android never force-hides it (it does hide app overlays on
 * Settings), and it needs no "Display over other apps" permission. Usage-Access engine: an app overlay
 * ([windowType]), which appears ~200 ms sooner than launching a lock Activity; Settings still gets the Activity.
 * Either way it is an opaque window filling the screen between the system bars (see [params]), plus a strip behind
 * each system bar so the app can't show through there either ([strips]).
 *
 * Fail-safe: BACK goes HOME and never reveals the app; any error removes the overlay (fail open).
 */
class LockOverlayManager(
    private val context: Context,
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
) {
    // The owning service's WindowManager: for the accessibility engine it carries the token its overlay needs.
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    // App names and icons, loaded off the main thread (PackageManager calls and icon decoding take a few ms) so the
    // lock itself is never held up; the locked apps are preloaded when the engine starts.
    private val labelCache = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    /** Set by the service: a reliable HOME via performGlobalAction. */
    var onBack: (() -> Unit)? = null
    /** Set by the accessibility engine: false while stepping the lock aside could show a floating window of the app. */
    var canStepAside: () -> Boolean = { true }
    private var promptOnConfirm = false // accessibility: open the fingerprint prompt once the app's screen is confirmed
    private var screenConfirmed = false // the app's own screen is known to fill the screen (always so for app overlays)
    private var stepAsideLater = false  // the prompt is ready but the lock may only step aside once that's confirmed
    private var promptRefused = false   // this lock found it can't step aside for the prompt (see noFingerprintHere)

    private var frame: FrameLayout? = null
    private var content: LockContent? = null
    private var builtColors: LockColors? = null
    private var builtDefault: String? = null      // default method at build time (rebuild if it changes)
    private var added = false   // the lock is showing
    private var inWm = false    // the window is attached (it stays attached, hidden, between locks)
    private var shownFor: String? = null
    private var floating = false // this showing covers a floating window (see show())

    // Behind the status bar and the nav bar buttons: an app overlay sits below the bars, so it covers the app there
    // while the bars stay on top and usable. Two thin strips, never over the lock itself (a window added later would sit
    // above it). Needs "Display over other apps" (always granted in Usage Access mode). Added through the app's own
    // WindowManager: the accessibility service's one stamps its overlay token on every window.
    private val appWm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val strips = arrayOfNulls<View>(2) // [0] under the status bar, [1] under the nav bar

    // Biometric flow: the overlay hosts the system prompt via a proxy Activity, raised when the user taps.
    private var currentOnUnlock: (() -> Unit)? = null
    private var bioInFlight = false
    private var promptCovered = false // the try under way has its prompt screen up (it covers the app)
    private var promptAt = 0L         // when the try under way was started (uptime)

    val currentPackage: String? get() = if (added) shownFor else null
    val coversFloating: Boolean get() = added && floating
    /** A lock is up for an app, or stepped aside for its fingerprint prompt. */
    val active: Boolean get() = shownFor != null
    private val isAccessibility = windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun attach() {
        try { ensureBuilt(); frame?.let { ensureAttached(it) } } catch (_: Throwable) {}
        handler.postDelayed({ warmUp() }, 1_500) // after the name/icon preload above has had time to finish
        for (p in LockStore.lockedPackages(context)) loadIdentity(p)
        LockStore.watch(context, prefsListener)
    }

    /** The owning service is going away: stop listening and drop the window. */
    fun release() {
        LockStore.unwatch(context, prefsListener)
        detach()
    }

    // Rebuild the lock as soon as its look or default method changes, so showing it never pays for building it;
    // and fetch names/icons for newly locked apps ahead of time. (Held here: prefs keep listeners weakly.)
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == "locked_pkgs" -> for (p in LockStore.lockedPackages(context)) loadIdentity(p)
            key == "default_method" || key == "theme_id" || key?.startsWith("t_") == true -> handler.post { rebuildIfIdle() }
        }
    }

    private fun rebuildIfIdle() {
        // On screen or stepped aside for its fingerprint prompt (it rebuilds before its next show), or not built yet.
        if (shownFor != null || frame == null) return
        if (builtColors != LockColors.from(context) || builtDefault != LockStore.defaultMethod(context)) {
            detach()
            try { ensureBuilt(); frame?.let { ensureAttached(it) } } catch (_: Throwable) {}
            warmUp()
        }
    }

    /**
     * Draw the lock once, invisibly (alpha 0, untouchable, no focus), right after start-up or a rebuild. The first
     * frame a process draws pays for setting up rendering (~0.5 s measured on the emulator) — that must never land
     * on the first real lock. A real lock arriving meanwhile simply takes over the window.
     */
    private fun warmUp() {
        val f = frame ?: return
        if (!inWm || added) return
        try {
            // With a real locked app's name and icon, so their first drawing is done here too.
            LockStore.lockedPackages(context).firstOrNull()?.let { p -> content?.showFor(p, labelCache[p] ?: "", iconCache[p]) }
            f.visibility = View.VISIBLE
            wm.updateViewLayout(f, hiddenParams().apply { alpha = 0f })
            f.viewTreeObserver.registerFrameCommitCallback {
                handler.post { if (!added && frame === f && inWm) try { setShown(f, false) } catch (_: Throwable) {} }
            }
            showStrips(false, create = true)
        } catch (_: Throwable) {}
    }

    /**
     * Attach the window once, hidden (no surface, no touch, no focus, no orientation request), so showing the lock
     * later is just a visibility flip — no window to create, which gets the first frame out sooner.
     */
    private fun ensureAttached(f: FrameLayout) {
        if (inWm) return
        f.visibility = View.GONE
        wm.addView(f, hiddenParams())
        inWm = true
    }

    private fun setShown(f: FrameLayout, show: Boolean) {
        if (show) {
            f.visibility = View.VISIBLE
            wm.updateViewLayout(f, params())
        } else {
            f.visibility = View.GONE
            wm.updateViewLayout(f, hiddenParams())
        }
        showStrips(show)
    }

    /** Show/hide the strips under the bars ([create]: make them if missing — they're made hidden at warm-up, so the
     *  first lock pays nothing for them; skipped without overlay permission). */
    private fun showStrips(show: Boolean, create: Boolean = show) {
        for (i in strips.indices) try {
            val p = stripParams(i)
            var b = strips[i]
            if (b == null) {
                if (!create || p == null || !Settings.canDrawOverlays(context)) continue
                b = View(context).apply { setBackgroundColor(barColor()); visibility = View.GONE }
                appWm.addView(b, p)
                strips[i] = b
            }
            b.visibility = if (show && p != null) View.VISIBLE else View.GONE
            // Touchable while shown: Android caps a touch-through overlay at 80% opacity (the app would show
            // through). The bars sit above it, so they still get every touch.
            if (p != null) appWm.updateViewLayout(b, p.apply { if (!show) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE })
        } catch (_: Throwable) { removeStrips() }
    }

    private fun removeStrips() {
        for (i in strips.indices) {
            strips[i]?.let { try { appWm.removeViewImmediate(it) } catch (_: Throwable) {} }
            strips[i] = null
        }
    }

    /** Exactly over the status bar (0) or the nav bar (1) — the lock is always portrait — or null when there's none. */
    private fun stripParams(i: Int): WindowManager.LayoutParams? {
        val m = metrics()
        val bars = barInsets() ?: return null
        val h = if (i == 0) bars.top else bars.bottom
        if (h <= 0) return null
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = if (i == 0) 0 else m.bounds.height() - h
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * The strips' colour. The bar icons and buttons drawn on them keep the colour the app under the lock asked for
     * (Android takes it only from apps' own screens), and that can be light or dark. In dark mode apps draw light icons,
     * so a dark lock runs seamlessly under them and a light one gets near-black bars; in light mode icons vary (dark in
     * most apps, white in older ones), so a mid grey keeps either readable.
     * shortcut: guesses from dark mode; reading the app's real button colour needs an Activity, too slow for the lock.
     */
    private fun barColor(): Int {
        val lock = LockColors.from(context)
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when {
            night && !lock.isLight -> lock.bg
            night -> Color.parseColor("#121212")
            else -> Color.parseColor("#6F6F73")
        }
    }

    private fun hiddenParams() = params().apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Load [pkg]'s name and icon in the background; rebinds the lock if it is showing that app meanwhile. */
    private fun loadIdentity(pkg: String) {
        if (labelCache.containsKey(pkg) || !loading.add(pkg)) return
        Thread {
            val l = labelFor(pkg)
            iconFor(pkg)?.let { iconCache[pkg] = it }
            labelCache[pkg] = l
            loading.remove(pkg)
            handler.post { if (added && shownFor == pkg) content?.bind(l, iconCache[pkg]) }
        }.start()
    }

    private fun escape() { (onBack ?: ::goHome).invoke() }

    private fun ensureBuilt() {
        if (frame != null) return
        val colors = LockColors.from(context)
        val f = object : FrameLayout(context) {
            // On devices that deliver BACK as a key event (3-button nav): go HOME, never reveal the app.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) escape()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // Re-fit the window between the bars after a rotation/inset change (and the nav bar strip with it; a
            // dark-mode switch changes the strip's colour too).
            override fun onConfigurationChanged(newConfig: Configuration?) {
                super.onConfigurationChanged(newConfig)
                metrics = null
                if (inWm) try { wm.updateViewLayout(this, if (added) params() else hiddenParams()) } catch (_: Throwable) {}
                strips.forEach { it?.setBackgroundColor(barColor()) }
                showStrips(added)
            }
        }
        f.isFocusableInTouchMode = true
        // The root holds focus (so the locked app can't); without this, Android draws its focus highlight over the
        // whole lock whenever the phone leaves touch mode (any key press) — a grey veil on the lock.
        f.defaultFocusHighlightEnabled = false
        content = LockContent(
            context, f,
            onUnlock = { currentOnUnlock?.invoke() },
            onBiometric = { launchBiometricForCurrent() },
        )
        frame = f
        builtColors = colors
        builtDefault = LockStore.defaultMethod(context)
    }

    /**
     * Cover [pkg]. [floatingWin]: the app shows in a floating window (a chat bubble or chat head opened up) or shares the
     * screen with another app. Then, as an app overlay, the window is first re-added, making it the newest overlay —
     * a bubble's window added after it would otherwise stay on top (a frame or two slower, so only then). And the
     * fingerprint prompt is not opened by itself: stepping the lock aside for it would show the floating window.
     */
    fun show(pkg: String, floatingWin: Boolean = false, onUnlock: () -> Unit) {
        try {
            // Also "already" while stepped aside for its fingerprint prompt: showing again would cover the prompt.
            val already = shownFor == pkg && (added || bioInFlight)
            if (already && (!floatingWin || floating)) return
            val raise = floatingWin && !isAccessibility // an accessibility overlay is always on top already
            // Rebuild if the palette OR the default method changed since this view was built.
            if (!already && frame != null && (builtColors != LockColors.from(context) || builtDefault != LockStore.defaultMethod(context))) detach()
            ensureBuilt()
            currentOnUnlock = onUnlock
            val c = content ?: return
            val f = frame ?: return
            // Taken off before it is bound: detaching stops a running lockout countdown, which showFor restarts.
            if (raise && inWm) { wm.removeViewImmediate(f); inWm = false; removeStrips() }
            // Accessibility overlay over a floating window: the prompt would open hidden under it, so no fingerprint.
            c.showFor(pkg, labelCache[pkg] ?: "", iconCache[pkg], allowFingerprint = !(floatingWin && isAccessibility))
            if (raise) {
                f.visibility = View.VISIBLE
                wm.addView(f, params())
                inWm = true
                showStrips(true)
            } else {
                ensureAttached(f)
                setShown(f, true)
            }
            added = true
            if (floatingWin && !floating && bioInFlight) endPrompt() // its prompt couldn't show now
            floating = floatingWin
            f.requestFocus()
            shownFor = pkg
            loadIdentity(pkg)
            // Fingerprint: open the phone's fingerprint prompt right away (in-display sensors light up), no tap needed.
            // Accessibility: only once the app's own screen is confirmed full-screen (confirmFullScreen) — it may turn out
            // to be in a chat bubble, which stepping aside for the prompt would show.
            promptOnConfirm = false
            if (!already) { screenConfirmed = !isAccessibility; stepAsideLater = false; promptRefused = false }
            if (!already && !floatingWin && c.currentMethod == LockStore.FINGERPRINT) {
                if (isAccessibility) promptOnConfirm = true else handler.post { if (added && shownFor == pkg && !floating) launchBiometric(pkg) }
            }
        } catch (t: Throwable) {
            forceRemove()
        }
    }

    private fun launchBiometricForCurrent() { shownFor?.let { launchBiometric(it) } }

    /** Accessibility engine: [pkg]'s own screen is up and fills the screen, so the fingerprint prompt may open now. */
    fun confirmFullScreen(pkg: String) {
        if (shownFor != pkg || (floating && promptRefused)) return
        if (floating) {
            // Taken for a floating window, but it fills the screen (an app opening looks small for a moment, or a chat
            // bubble was opened full screen): fingerprint is back, and its prompt opens as on any full-screen lock.
            floating = false
            content?.showFor(pkg, labelCache[pkg] ?: "", iconCache[pkg])
            promptOnConfirm = true
        }
        screenConfirmed = true
        if (stepAsideLater && bioInFlight) { stepAsideLater = false; if (canStepAside()) stepAside() else noFingerprintHere() }
        if (!promptOnConfirm || !added) return
        promptOnConfirm = false
        if (content?.currentMethod == LockStore.FINGERPRINT) launchBiometric(pkg)
    }

    private fun launchBiometric(pkg: String, retry: Boolean = false) {
        // A try whose prompt screen still hasn't come up was lost (see the time-out below): a tap starts a new one now.
        if (bioInFlight && (promptCovered || SystemClock.uptimeMillis() - promptAt < 700)) return
        if (bioInFlight) endPrompt()
        val cb = currentOnUnlock ?: return
        bioInFlight = true
        promptCovered = false
        promptAt = SystemClock.uptimeMillis()
        val gen = ++BiometricProxyActivity.gen
        // The proxy covers the app with a themed screen, then calls stepAside() so the system prompt (which can sit
        // below this overlay) shows. A cancel puts the lock straight back before the proxy closes. Over a floating
        // window the lock stays up instead: that window floats above every app screen, so stepping aside would show
        // it; Android's own prompt sits above app overlays anyway.
        // Decided when the prompt is ready; until the app's screen is confirmed (accessibility), the lock waits for that.
        BiometricProxyActivity.covering = {
            promptCovered = true
            if (!floating && screenConfirmed && canStepAside()) stepAside()
            else if (!floating) {
                stepAsideLater = true
                // No confirmation yet: once a chat bubble would have shown up in the window list, go ahead if none did.
                handler.postDelayed({
                    if (stepAsideLater && bioInFlight && BiometricProxyActivity.gen == gen && !floating) {
                        stepAsideLater = false
                        if (canStepAside()) stepAside() else noFingerprintHere()
                    }
                }, 600)
            }
        }
        BiometricProxyActivity.pending = { ok ->
            bioInFlight = false
            if (ok == true) {
                if (floating) LockStore.keepThroughNextHome(pkg) // its chat bubble closed for the prompt
                handler.post { cb() }
            } else restoreAfterPrompt(otherMethod = ok == null)
        }
        try {
            BiometricProxyActivity.cancel()
            context.startActivity(
                Intent(context, BiometricProxyActivity::class.java).apply {
                    // Clear: a prompt screen left over from a lost try (one that never came up) would otherwise be brought
                    // back instead, with that try's number — it closed itself at once, so taps on the fingerprint did nothing.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("type", content?.currentMethod ?: LockStore.FINGERPRINT)
                    putExtra("label", labelCache[pkg] ?: pkg)
                    putExtra("pkg", pkg)
                    putExtra("gen", gen)
                }
            )
            // Lost: Android refused to start it (power saving, background limits), or the app brought up another screen of
            // its own as it started, which went over it. Drop this try and start once more by itself (a tap also can).
            handler.postDelayed({
                if (!promptCovered && bioInFlight && BiometricProxyActivity.gen == gen) {
                    endPrompt()
                    if (!retry && added && shownFor == pkg && !floating && content?.currentMethod == LockStore.FINGERPRINT) launchBiometric(pkg, retry = true)
                }
            }, 1_500)
        } catch (_: Throwable) {
            bioInFlight = false
        }
    }

    /** Drop the fingerprint try under way: its prompt closes, and anything it reports later is ignored. */
    private fun endPrompt() {
        BiometricProxyActivity.gen++
        bioInFlight = false
        stepAsideLater = false
        BiometricProxyActivity.cancel()
    }

    /** The prompt can't show here (the lock can't step aside without showing a floating window or ending split
     *  screen): close it and offer the other methods instead, as for a floating window. */
    private fun noFingerprintHere() {
        endPrompt()
        val p = shownFor ?: return
        floating = true
        promptRefused = true
        content?.showFor(p, labelCache[p] ?: "", iconCache[p], allowFingerprint = false)
    }

    private fun stepAside() {
        val f = frame ?: return
        if (added) try { setShown(f, false); added = false } catch (_: Throwable) { forceRemove() }
    }

    /** The prompt closed without unlocking. [otherMethod]: the user tapped "Use another method". */
    private fun restoreAfterPrompt(otherMethod: Boolean = false) {
        val f = frame ?: return
        // Only when the user is still in the lock flow (prompt cancelled); if they left, the engine takes over.
        if (!added && shownFor != null && !BiometricProxyActivity.stopped) {
            try { ensureAttached(f); setShown(f, true); added = true; f.requestFocus() } catch (_: Throwable) { forceRemove(); return }
        } else if (!added) {
            shownFor = null // they left during the prompt: this lock is over (it comes back when the app does)
            return
        }
        if (otherMethod) content?.useOtherMethod()
    }

    fun hide() {
        if (bioInFlight) endPrompt() // the lock is going away: its prompt goes too
        promptOnConfirm = false
        stepAsideLater = false
        val f = frame
        if (added && f != null) try { setShown(f, false) } catch (_: Throwable) { forceRemove(); return }
        added = false
        floating = false
        shownFor = null
    }

    /** Remove the window for good (rebuild or teardown). */
    fun detach() = forceRemove()

    private fun forceRemove() {
        val f = frame
        val wasIn = inWm
        added = false; inWm = false; floating = false; shownFor = null; frame = null; content = null
        if (f != null && wasIn) try { wm.removeViewImmediate(f) } catch (_: Throwable) {}
        removeStrips()
    }

    /**
     * An opaque window that fills the screen BETWEEN the status and nav bars, so the bars stay visible and usable
     * (BACK/HOME/Recents) while the app underneath stays covered. (An accessibility overlay sits above the bars, so it
     * mustn't cover them; the areas under the bars get their own [strips].)
     *
     * Focusable (with touches outside it passing on): the locked app loses input focus, so its keyboard closes and
     * nothing can be typed into it, and BACK comes here (goes HOME) instead of acting inside the app.
     *
     * Placed by hand from the live insets with system fitting switched off: stock Android ignores fitting for
     * accessibility overlays (it drew over both bars), while some OEM builds apply it — doing both shifted
     * the lock down on Samsung. With fitting off, every device places it exactly here.
     * No FLAG_SECURE: the user doesn't need screenshot-blocking, and it made the overlay render black
     * on some Samsung displays.
     */
    private fun params(): WindowManager.LayoutParams {
        val m = metrics()
        val bars = barInsets()
        val between = bars != null
        val b = m.bounds
        return WindowManager.LayoutParams(
            if (between) b.width() - bars!!.left - bars.right else WindowManager.LayoutParams.MATCH_PARENT,
            if (between) b.height() - bars!!.top - bars.bottom else WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (between) bars!!.left else 0
            y = if (between) bars!!.top else 0
            fitInsetsTypes = 0 // placed by hand above; never let the system shift it again
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT // the lock screen is always portrait
        }
    }

    // Screen and bar sizes: read once, and again after a rotation or inset change (each read asks the window manager).
    private var metrics: android.view.WindowMetrics? = null
    private fun metrics() = metrics ?: wm.currentWindowMetrics.also { metrics = it }

    private fun barInsets() = try {
        metrics().windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    } catch (_: Throwable) { null }

    private fun goHome() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } catch (_: Throwable) {}
    }

    private fun iconFor(pkg: String): Drawable? =
        try { context.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }

    private fun labelFor(pkg: String): String =
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
}
