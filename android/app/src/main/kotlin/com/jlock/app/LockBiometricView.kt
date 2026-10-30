package com.jlock.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The lock screen for a biometric method ("Fingerprint" / "Same as device"): the app's identity and a
 * tap target that asks the overlay to show the system prompt (via [BiometricProxyActivity]). The overlay
 * manager also auto-triggers once when the lock appears. No secret is handled here — the OS verifies.
 */
@SuppressLint("ViewConstructor")
class LockBiometricView(
    context: Context,
    private val c: LockColors,
    private val onTrigger: () -> Unit,
) : LinearLayout(context), LockFace {

    override var onUnlock: () -> Unit = {} // success is driven by the proxy result in the overlay manager

    private val header = LockHeader(context, c)

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private fun sp(v: Float, t: TextView) = t.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(c.bg)
        setPadding(px(26f), 0, px(26f), px(12f))

        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Flexible gap pushes the fingerprint target down to the bottom of the face (just above the
        // "More options" row), matching the in-app unlock screen and easier to reach one-handed.
        addView(spacer(1f))

        // Big tappable affordance to (re)show the prompt.
        addView(ImageView(context).apply {
            val s = px(96f)
            layoutParams = LayoutParams(s, s)
            setImageDrawable(tinted(R.drawable.ic_fingerprint, c.accent))
            scaleType = ImageView.ScaleType.FIT_CENTER // scale the 24dp glyph up to 46dp, same as the in-app gate
            setPadding(px(25f), px(25f), px(25f), px(25f))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.surface) }
            isClickable = true
            setOnClickListener { Haptics.key(context); onTrigger() }
        })
        addView(TextView(context).apply {
            text = "Tap the fingerprint to unlock"
            setTextColor(c.muted); sp(13f, this); gravity = Gravity.CENTER
            setPadding(0, px(16f), 0, px(20f))
        })
    }

    override fun bind(label: String, icon: Drawable?) = header.bind(label, icon)

    override fun reset() {}

    private fun spacer(weight: Float) = android.view.View(context).apply { layoutParams = LayoutParams(0, 0, weight) }

    private fun tinted(resId: Int, color: Int): Drawable? =
        context.getDrawable(resId)?.mutate()?.also { it.setTint(color) }
}
