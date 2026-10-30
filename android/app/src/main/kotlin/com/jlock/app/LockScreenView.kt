package com.jlock.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Colors for the lock screen (defaults = jNote Graphite; Settings mirrors the chosen palette). */
data class LockColors(
    val bg: Int = Color.parseColor("#1C1A17"),
    val surface: Int = Color.parseColor("#26231F"),
    val raised: Int = Color.parseColor("#2F2B26"),
    val fg: Int = Color.parseColor("#EEEBE6"),
    val muted: Int = Color.parseColor("#A3A09B"),
    val accent: Int = Color.parseColor("#F2B24C"),
    val onAccent: Int = Color.parseColor("#2A1A00"),
    val line: Int = Color.parseColor("#3A352E"),
) {
    /** A light palette: the status/nav bar icons over the lock must be dark to stay visible. */
    val isLight: Boolean get() = Color.luminance(bg) > 0.4f

    /** Error text/marks: soft red on dark themes, deep red on light ones (so it stays readable on both). */
    val error: Int get() = if (isLight) Color.parseColor("#C62828") else Color.parseColor("#FF8A80")

    /** Ask for bar icons that contrast with the lock (honoured for the lock Activity's own bars). */
    fun applyBarIcons(view: View) {
        val both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        try { view.windowInsetsController?.setSystemBarsAppearance(if (isLight) both else 0, both) } catch (_: Throwable) {}
    }

    companion object {
        /** The user's chosen palette (mirrored from Settings), or Graphite until one is set. */
        fun from(c: Context) = LockColors(
            bg = LockStore.themeColor(c, "bg", Color.parseColor("#1C1A17")),
            surface = LockStore.themeColor(c, "surface", Color.parseColor("#26231F")),
            raised = LockStore.themeColor(c, "raised", Color.parseColor("#2F2B26")),
            fg = LockStore.themeColor(c, "text", Color.parseColor("#EEEBE6")),
            muted = LockStore.themeColor(c, "muted", Color.parseColor("#A3A09B")),
            accent = LockStore.themeColor(c, "accent", Color.parseColor("#F2B24C")),
            onAccent = LockStore.themeColor(c, "onAccent", Color.parseColor("#2A1A00")),
            line = LockStore.themeColor(c, "line", Color.parseColor("#3A352E")),
        )
    }
}

/**
 * The lock screen shown over a locked app: the app's identity on top, then a PIN keypad. Built ONCE
 * and reused (bind()/reset()), so showing it costs no inflation. Verifies against the stored verifier
 * with an escalating, never-blocking wait on the monotonic clock. Auto-checks when the entered PIN
 * reaches the stored length. Pattern / password / fingerprint methods layer on next.
 */
@SuppressLint("ViewConstructor")
class LockScreenView(
    context: Context,
    private val c: LockColors,
    private val verify: (String, (Boolean) -> Unit) -> Unit, // checks off the main thread, answers on it
    private val getFails: () -> Int,
    private val addFail: () -> Int,
    private val resetFails: () -> Unit,
    private val pinLength: () -> Int,
) : LinearLayout(context), LockFace {

    override var onUnlock: () -> Unit = {}

    private val entered = StringBuilder()
    private val header = LockHeader(context, c)
    private val dots: LinearLayout
    private val message: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var waitUntil = 0L
    private var checking = false // a full PIN is being checked: input waits

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private fun sp(v: Float, t: TextView) = t.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    // Letters under the digits (declared before init so the keypad can read it while building).
    private val subs = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
        "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ")

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(c.bg)
        setPadding(px(26f), 0, px(26f), px(12f))

        // --- app identity ---
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(spacer(1f))

        // --- prompt + dots + message ---
        addView(TextView(context).apply { text = "Enter your PIN"; setTextColor(c.muted); sp(14f, this); gravity = Gravity.CENTER })
        dots = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34f)).apply { topMargin = px(14f) }
        }
        addView(dots)
        message = TextView(context).apply {
            setTextColor(c.error); sp(12.5f, this); gravity = Gravity.CENTER
            minHeight = px(20f); setPadding(0, px(6f), 0, 0)
        }
        addView(message)

        addView(spacer(0.25f))
        addView(keypad())
        refreshDots() // show the empty PIN slots right away, also when switched to from "More options"
        addView(spacer(0.4f))
    }

    override fun bind(label: String, icon: Drawable?) = header.bind(label, icon)

    override fun reset() {
        entered.setLength(0); refreshDots(); message.text = ""
        waitUntil = 0L; handler.removeCallbacks(ticker); checking = false
    }

    private fun spacer(weight: Float) = View(context).apply { layoutParams = LayoutParams(0, 0, weight) }

    private fun tinted(resId: Int, color: Int): Drawable? =
        context.getDrawable(resId)?.mutate()?.also { it.setTint(color) }

    // ---- dots ----
    /** One slot per PIN digit (when the length is known): hollow until typed, filled after. */
    private fun refreshDots() {
        dots.removeAllViews()
        val total = maxOf(pinLength().takeIf { it in 1..16 } ?: 0, entered.length)
        for (i in 0 until total) {
            val filled = i < entered.length
            dots.addView(View(context).apply {
                val s = px(12f)
                layoutParams = LayoutParams(s, s).apply { marginStart = px(8f); marginEnd = px(8f) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (filled) setColor(c.accent) else { setColor(Color.TRANSPARENT); setStroke(px(1.5f), c.muted) }
                }
            })
        }
    }

    // ---- keypad ----
    private fun keypad(): View {
        val grid = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(px(300f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<"))
        for (r in rows) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (label in r) row.addView(cell(label), LayoutParams(0, px(62f), 1f).apply {
                setMargins(px(5f), px(5f), px(5f), px(5f))
            })
            grid.addView(row)
        }
        return grid
    }

    private fun cell(label: String): View {
        if (label == "") return View(context) // ghost (reserved for the fingerprint key)
        val v: View = if (label == "<") {
            ImageView(context).apply {
                setImageDrawable(tinted(R.drawable.ic_backspace_key, c.muted))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(px(18f), px(18f), px(18f), px(18f))
                contentDescription = "Delete"
            }
        } else {
            LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = px(18f).toFloat(); setColor(c.surface) }
                addView(TextView(context).apply { text = label; setTextColor(c.fg); sp(26f, this); gravity = Gravity.CENTER })
                subs[label]?.let { s ->
                    addView(TextView(context).apply {
                        text = s; setTextColor(c.muted); sp(8.5f, this); gravity = Gravity.CENTER
                        letterSpacing = 0.14f
                    })
                }
            }
        }
        v.isClickable = true
        v.setOnClickListener { Haptics.key(context); if (label == "<") onBack() else onDigit(label) }
        return v
    }

    private fun onBack() {
        if (checking || SystemClock.elapsedRealtime() < waitUntil) return
        if (entered.isNotEmpty()) { entered.deleteCharAt(entered.length - 1); message.text = ""; refreshDots() }
    }

    private fun onDigit(d: String) {
        if (checking || SystemClock.elapsedRealtime() < waitUntil) return
        val target = pinLength()
        if (target in 1..16 && entered.length >= target) return
        if (entered.length >= 16) return
        entered.append(d); message.text = ""; refreshDots()
        if (target in 1..16) { if (entered.length >= target) attempt(true) }
        else if (entered.length >= 4) attempt(false) // legacy: PIN length unknown, verify incrementally
    }

    private fun attempt(complete: Boolean) {
        val pin = entered.toString()
        if (complete) checking = true
        verify(pin) { ok ->
            if (complete) checking = false
            if (ok) { resetFails(); onUnlock(); return@verify }
            if (!complete || entered.toString() != pin) return@verify // (unknown length) still typing
            entered.setLength(0); refreshDots()
            Haptics.error(context)
            val fails = addFail()
            val wait = requiredWaitMs(fails)
            if (wait > 0) startCooldown(wait) else message.text = "Wrong PIN"
        }
    }

    private fun requiredWaitMs(fails: Int): Long =
        if (fails < 5) 0 else minOf(30_000L * (fails - 4), 300_000L)

    private fun startCooldown(ms: Long) { waitUntil = SystemClock.elapsedRealtime() + ms; handler.removeCallbacks(ticker); tick() }
    override fun lockout(ms: Long) { if (ms > 0) startCooldown(ms) }
    private val ticker = object : Runnable { override fun run() { tick() } }
    private fun tick() {
        val remain = waitUntil - SystemClock.elapsedRealtime()
        if (remain <= 0) { message.text = ""; return }
        message.text = "Too many tries. Try again in ${(remain + 999) / 1000}s"
        handler.postDelayed(ticker, 500)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
