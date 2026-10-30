package com.jlock.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The lock screen for the PASSWORD method shown over a locked app. It draws its OWN compact QWERTY keyboard
 * (letters + ⇧ shift + a ?123 symbols layer) instead of using the system keyboard: it appears instantly with the
 * lock, works the same in every host window, and nothing typed can reach the locked app or the keyboard's
 * suggestions. The in-app gate uses a normal text field instead; only this overlay needs the custom pad.
 * Verifies against the stored verifier with the same escalating, never-blocking backoff as the others.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LockPasswordView(
    context: Context,
    private val c: LockColors,
    private val verify: (String, (Boolean) -> Unit) -> Unit, // checks off the main thread, answers on it
    private val getFails: () -> Int,
    private val addFail: () -> Int,
    private val resetFails: () -> Unit,
) : LinearLayout(context), LockFace {

    override var onUnlock: () -> Unit = {}

    private val entered = StringBuilder()
    private var shift = false
    private var symbols = false

    private val header = LockHeader(context, c)
    private val mask: TextView
    private val message: TextView
    private val kb: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var waitUntil = 0L
    private var checking = false // the password is being checked: input waits

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private fun sp(v: Float, t: TextView) = t.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    // Key layers — declared BEFORE init so buildKeyboard() (called from init) can read them.
    private val lettersRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val symbolRows = listOf("1234567890", "@#\$_&-+()/", "=*\"':;!?")

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(c.bg)
        setPadding(px(14f), 0, px(14f), px(12f))

        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(spacer(1f))

        addView(TextView(context).apply {
            text = "Enter your password"; setTextColor(c.muted); sp(14f, this); gravity = Gravity.CENTER
        })
        // Masked entry: shows a bullet per character, keeping the most recent visible if it overflows.
        mask = TextView(context).apply {
            setTextColor(c.fg); sp(20f, this); gravity = Gravity.CENTER
            setSingleLine(); ellipsize = TextUtils.TruncateAt.START
            minHeight = px(30f); setPadding(px(8f), px(10f), px(8f), px(6f))
            letterSpacing = 0.15f
        }
        addView(mask, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Hairline under the field.
        addView(View(context).apply {
            setBackgroundColor(c.line)
            layoutParams = LayoutParams(px(220f), px(1f))
        })
        message = TextView(context).apply {
            setTextColor(c.error); sp(12.5f, this); gravity = Gravity.CENTER
            minHeight = px(20f); setPadding(0, px(6f), 0, 0)
        }
        addView(message)

        addView(spacer(0.35f))

        kb = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(kb)
        buildKeyboard()

        addView(spacer(0.2f))
    }

    override fun bind(label: String, icon: Drawable?) = header.bind(label, icon)

    override fun reset() {
        entered.setLength(0); shift = false
        if (symbols) { symbols = false; buildKeyboard() } else refreshMask()
        message.text = ""; waitUntil = 0L; handler.removeCallbacks(ticker); checking = false
    }

    private fun spacer(weight: Float) = View(context).apply { layoutParams = LayoutParams(0, 0, weight) }

    private fun tinted(resId: Int, color: Int): Drawable? =
        context.getDrawable(resId)?.mutate()?.also { it.setTint(color) }

    private fun refreshMask() { mask.text = "•".repeat(entered.length) }

    // ---- keyboard ----
    private fun buildKeyboard() {
        kb.removeAllViews()
        if (symbols) {
            kb.addView(charRow(symbolRows[0]))
            kb.addView(charRow(symbolRows[1]))
            kb.addView(lastCharRowWithBackspace(symbolRows[2], leadWeight = 0.5f, backWeight = 1.5f))
        } else {
            kb.addView(charRow(lettersRows[0]))
            kb.addView(charRow(lettersRows[1], endSpacers = 0.5f))
            kb.addView(lettersRow3())
        }
        kb.addView(bottomRow())
    }

    private fun charRow(chars: String, endSpacers: Float = 0f): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        if (endSpacers > 0f) row.addView(spacerW(endSpacers))
        for (ch in chars) row.addView(charKey(ch))
        if (endSpacers > 0f) row.addView(spacerW(endSpacers))
        return row
    }

    /** A symbols row that ends with a backspace key (and an optional leading spacer to keep key width). */
    private fun lastCharRowWithBackspace(chars: String, leadWeight: Float, backWeight: Float): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(spacerW(leadWeight))
        for (ch in chars) row.addView(charKey(ch))
        row.addView(specialKey("⌫", backWeight) { onBackspace() })
        return row
    }

    private fun lettersRow3(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(specialKey(if (shift) "⇧" else "⇧", 1.5f, highlight = shift) { shift = !shift; buildKeyboard() })
        for (ch in lettersRows[2]) row.addView(charKey(ch))
        row.addView(specialKey("⌫", 1.5f) { onBackspace() })
        return row
    }

    private fun bottomRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(specialKey(if (symbols) "ABC" else "?123", 1.6f, small = true) { symbols = !symbols; shift = false; buildKeyboard() })
        row.addView(charKey(','))
        row.addView(specialKey("space", 4.6f, small = true) { onChar(' ') })
        row.addView(charKey('.'))
        row.addView(specialKey("✓", 1.6f, accent = true) { attempt() })
        return row
    }

    private fun charKey(ch: Char): View {
        val display = if (shift && !symbols) ch.uppercaseChar() else ch
        return keyView(display.toString(), 1f, c.raised, c.fg, 18f) { onChar(display) }
    }

    private fun specialKey(
        label: String, weight: Float, small: Boolean = false, accent: Boolean = false,
        highlight: Boolean = false, onClick: () -> Unit,
    ): View {
        val bg = if (accent) c.accent else if (highlight) c.raised else c.surface
        val fg = if (accent) c.onAccent else c.muted
        return keyView(label, weight, bg, fg, if (small) 13f else 17f, onClick)
    }

    private fun keyView(text: String, weight: Float, bgColor: Int, textColor: Int, textSp: Float, onClick: () -> Unit): View {
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(textColor); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            background = GradientDrawable().apply { cornerRadius = px(8f).toFloat(); setColor(bgColor) }
            isClickable = true
            setOnClickListener { Haptics.key(context); onClick() }
        }
        tv.layoutParams = LayoutParams(0, px(46f), weight).apply { setMargins(px(3f), px(3f), px(3f), px(3f)) }
        return tv
    }

    private fun spacerW(weight: Float) = View(context).apply { layoutParams = LayoutParams(0, px(46f), weight) }

    // ---- input ----
    private fun inCooldown() = checking || SystemClock.elapsedRealtime() < waitUntil

    private fun onChar(ch: Char) {
        if (inCooldown()) return
        if (entered.length >= 64) return
        entered.append(ch); message.text = ""; refreshMask()
        if (shift) { shift = false; buildKeyboard() } // one-shot shift
    }

    private fun onBackspace() {
        if (inCooldown()) return
        if (entered.isNotEmpty()) { entered.deleteCharAt(entered.length - 1); message.text = ""; refreshMask() }
    }

    private fun attempt() {
        if (inCooldown()) return
        val pw = entered.toString()
        if (pw.isEmpty()) return
        checking = true
        verify(pw) { ok ->
            checking = false
            if (ok) { resetFails(); onUnlock(); return@verify }
            entered.setLength(0); refreshMask()
            Haptics.error(context)
            val wait = requiredWaitMs(addFail())
            if (wait > 0) startCooldown(wait) else message.text = "Wrong password"
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
