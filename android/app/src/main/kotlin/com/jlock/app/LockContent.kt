package com.jlock.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The shared lock UI: the current method's face (PIN / pattern / password / fingerprint) plus the
 * "More options" switcher. Built into any container, so both the accessibility overlay
 * ([LockOverlayManager]) and the usage-access screen ([LockActivity]) show the exact same lock with no
 * duplicated layout code.
 *
 * The host supplies two things, because they differ per host:
 *  - [onUnlock]: what to do when a method verifies (overlay hides; the activity finishes).
 *  - [onBiometric]: how to raise the system fingerprint prompt for the current method (the overlay
 *    hosts it via a proxy Activity since it sits above the prompt; the activity shows it directly).
 */
class LockContent(
    private val context: Context,
    root: FrameLayout,
    private val onUnlock: () -> Unit,
    private val onBiometric: () -> Unit,
) {
    val colors: LockColors = LockColors.from(context)
    private val dp = context.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    private val holder = FrameLayout(context)
    private val footer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    }
    private var lockFace: LockFace? = null
    private var shownMethod: String = LockStore.defaultMethod(context)
    private var boundPkg: String = ""
    private var boundLabel: String? = null
    private var boundIcon: Drawable? = null
    private var footerMethods: List<String> = emptyList() // the enabled methods the "More options" row was built for
    private var gen = 0 // bumped on every show and method switch: a check still running from before is then ignored
    private var fingerprintOk = true // false while the system prompt can't be shown over this lock

    val currentMethod: String get() = shownMethod

    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.setBackgroundColor(colors.bg)
        root.defaultFocusHighlightEnabled = false // never veil the lock with a focus highlight
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setMethod(LockStore.defaultMethod(context))
    }

    /** Reset to the default method and bind the given app's identity. [allowFingerprint] false: leave fingerprint out
     *  (start on the first other method, no fingerprint chip). */
    fun showFor(pkg: String, label: String, icon: Drawable?, allowFingerprint: Boolean = true) {
        gen++
        boundPkg = pkg
        val changed = fingerprintOk != allowFingerprint
        fingerprintOk = allowFingerprint
        val default = startMethod()
        if (shownMethod != default) setMethod(default)
        else if (changed || methods() != footerMethods) rebuildFooter() // a method was added or removed
        lockFace?.reset()
        lockFace?.lockout(LockStore.lockoutRemainingMs(context))
        bind(label, icon)
    }

    fun bind(label: String, icon: Drawable?) {
        boundLabel = label; boundIcon = icon
        lockFace?.bind(label, icon)
    }

    fun reset() { lockFace?.reset() }

    /** "Use another method" on the fingerprint prompt: switch to the first other method (PIN, pattern or password). */
    fun useOtherMethod() {
        if (shownMethod == LockStore.FINGERPRINT) methods().firstOrNull { it != LockStore.FINGERPRINT }?.let { setMethod(it) }
    }

    private fun buildFace(method: String): LockFace {
        // Checked off the main thread (the key derivation takes a moment), so the last dot shows at once and the lock
        // never freezes; the answer comes back on the main thread unless the lock has moved on meanwhile.
        val verify: (String, (Boolean) -> Unit) -> Unit = { secret, done ->
            val g = gen
            CryptoManager.verifyAsync(secret, LockStore.verifierFor(context, method)) { ok -> if (g == gen) done(ok) }
        }
        val getFails = { LockStore.fails(context) }
        val addFail = {
            LockStore.logEvent(context, LockStore.LOG_FAIL, boundPkg)
            LockStore.recordFailure(context)
        }
        val resetFails = { LockStore.recordSuccess(context) }
        return when (method) {
            LockStore.FINGERPRINT -> LockBiometricView(context, colors, onTrigger = { onBiometric() })
            LockStore.PATTERN -> LockPatternView(context, colors, verify, getFails, addFail, resetFails)
            LockStore.PASSWORD -> LockPasswordView(context, colors, verify, getFails, addFail, resetFails)
            else -> LockScreenView(context, colors, verify, getFails, addFail, resetFails, pinLength = { LockStore.pinLength(context) })
        }
    }

    /** Swap the shown method's face in place, keeping the bound app and refreshing the footer. */
    private fun setMethod(method: String) {
        gen++
        holder.removeAllViews()
        val face = buildFace(method)
        face.onUnlock = onUnlock
        holder.addView(face as View, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lockFace = face
        shownMethod = method
        face.lockout(LockStore.lockoutRemainingMs(context)) // a wait started on another method still applies
        boundLabel?.let { face.bind(it, boundIcon) }
        rebuildFooter()
    }

    private fun methods() = LockStore.enabledMethods(context).let { m -> if (fingerprintOk) m else m - LockStore.FINGERPRINT }
    private fun startMethod() = LockStore.defaultMethod(context).let { d -> if (d in methods()) d else methods().firstOrNull() ?: d }

    private fun rebuildFooter() {
        footer.removeAllViews()
        footerMethods = methods()
        val others = footerMethods.filter { it != shownMethod }
        if (others.isEmpty()) { footer.setPadding(0, 0, 0, 0); return }
        footer.setPadding(px(16f), px(4f), px(16f), px(18f))
        footer.addView(TextView(context).apply {
            text = "More options"; setTextColor(colors.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, 0, 0, px(8f))
            gravity = Gravity.CENTER
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (m in others) row.addView(chip(m))
        footer.addView(row)
    }

    private fun chip(method: String): View {
        val bg = GradientDrawable()
        bg.cornerRadius = px(20f).toFloat()
        bg.setColor(colors.surface)
        bg.setStroke(px(1f), colors.line)
        return TextView(context).apply {
            text = methodLabel(method)
            setTextColor(colors.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(px(16f), px(10f), px(16f), px(10f))
            background = bg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(px(5f), 0, px(5f), 0) }
            isClickable = true
            setOnClickListener {
                setMethod(method)
                if (method == LockStore.FINGERPRINT) onBiometric() // picked fingerprint: open its prompt right away
            }
        }
    }

    private fun methodLabel(method: String) = when (method) {
        LockStore.PATTERN -> "Pattern"
        LockStore.PASSWORD -> "Password"
        LockStore.FINGERPRINT -> "Fingerprint"
        else -> "PIN"
    }
}
