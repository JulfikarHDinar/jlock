package com.jlock.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The lock screen shown over a locked app when the method is a pattern: the app's identity on top, then
 * a 3×3 pattern pad. Same build-once/reuse and escalating-backoff model as [LockScreenView]; the drawn
 * node path (e.g. "0-1-2-5-8") is the secret checked against the stored verifier.
 */
@SuppressLint("ViewConstructor")
class LockPatternView(
    context: Context,
    private val c: LockColors,
    private val verify: (String, (Boolean) -> Unit) -> Unit, // checks off the main thread, answers on it
    private val getFails: () -> Int,
    private val addFail: () -> Int,
    private val resetFails: () -> Unit,
) : LinearLayout(context), LockFace {

    override var onUnlock: () -> Unit = {}

    private val header = LockHeader(context, c)
    private val message: TextView
    private val grid: Grid
    private val handler = Handler(Looper.getMainLooper())
    private var waitUntil = 0L
    private var checking = false // a drawn pattern is being checked: input waits

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private fun sp(v: Float, t: TextView) = t.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(c.bg)
        setPadding(px(26f), 0, px(26f), px(12f))

        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(spacer(1f))

        addView(TextView(context).apply { text = "Draw your pattern"; setTextColor(c.muted); sp(14f, this); gravity = Gravity.CENTER })
        grid = Grid(context).apply {
            layoutParams = LayoutParams(px(280f), px(280f)).apply { topMargin = px(16f) }
        }
        addView(grid)
        message = TextView(context).apply {
            setTextColor(c.error); sp(12.5f, this); gravity = Gravity.CENTER
            minHeight = px(20f); setPadding(0, px(10f), 0, 0)
        }
        addView(message)

        addView(spacer(0.4f))
    }

    override fun bind(label: String, icon: Drawable?) = header.bind(label, icon)

    override fun reset() {
        grid.clear(); message.text = ""
        waitUntil = 0L; handler.removeCallbacks(ticker); checking = false
    }

    private fun spacer(weight: Float) = View(context).apply { layoutParams = LayoutParams(0, 0, weight) }

    private fun tinted(resId: Int, color: Int): Drawable? =
        context.getDrawable(resId)?.mutate()?.also { it.setTint(color) }

    private fun attempt(path: List<Int>) {
        checking = true
        verify(path.joinToString("-")) { ok ->
            checking = false
            if (ok) { resetFails(); onUnlock(); return@verify }
            grid.markError()
            Haptics.error(context)
            val fails = addFail()
            val wait = requiredWaitMs(fails)
            if (wait > 0) startCooldown(wait) else message.text = "Wrong pattern"
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

    /** The 3×3 pad: draws nodes + the drawn path, collects the node sequence, reports on finger-up. */
    private inner class Grid(context: Context) : View(context) {
        private val path = mutableListOf<Int>()
        private var cursor: PointF? = null
        private var showError = false
        private var visible = true // "Show pattern path" off: dots and lines stay hidden while drawing

        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = px(5f).toFloat()
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = px(2f).toFloat()
        }

        fun clear() { path.clear(); cursor = null; showError = false; invalidate() }
        fun markError() { showError = true; invalidate() }

        private fun cx(i: Int) = width / 3f * (i % 3) + width / 6f
        private fun cy(i: Int) = height / 3f * (i / 3) + height / 6f

        private fun addAt(x: Float, y: Float) {
            val cell = width / 3f
            for (i in 0..8) {
                if (path.contains(i)) continue
                val dx = x - cx(i); val dy = y - cy(i)
                if (dx * dx + dy * dy < (cell * 0.3f) * (cell * 0.3f)) {
                    if (path.isNotEmpty()) maybeMidpoint(path.last(), i)
                    path.add(i)
                    Haptics.tick(context)
                    break
                }
            }
        }

        private fun maybeMidpoint(a: Int, b: Int) {
            val ar = a / 3; val ac = a % 3; val br = b / 3; val bc = b % 3
            if ((ar + br) % 2 == 0 && (ac + bc) % 2 == 0) {
                val m = ((ar + br) / 2) * 3 + (ac + bc) / 2
                if (m != a && m != b && !path.contains(m)) path.add(m)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (checking || SystemClock.elapsedRealtime() < waitUntil) return true // swallow input during a check/cooldown
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { visible = LockStore.patternVisible(context); path.clear(); showError = false; addAt(e.x, e.y); cursor = PointF(e.x, e.y); invalidate() }
                MotionEvent.ACTION_MOVE -> { addAt(e.x, e.y); cursor = PointF(e.x, e.y); invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cursor = null; invalidate()
                    if (path.size >= 2) attempt(path.toList())
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val color = if (showError) c.error else c.accent
            val dot = width / 3f * 0.12f
            linePaint.color = color; ringPaint.color = color
            if (!visible) { for (i in 0..8) { nodePaint.color = c.line; canvas.drawCircle(cx(i), cy(i), dot, nodePaint) }; return }
            for (i in 0 until path.size - 1) {
                canvas.drawLine(cx(path[i]), cy(path[i]), cx(path[i + 1]), cy(path[i + 1]), linePaint)
            }
            cursor?.let { if (path.isNotEmpty()) canvas.drawLine(cx(path.last()), cy(path.last()), it.x, it.y, linePaint) }
            for (i in 0..8) {
                val on = path.contains(i)
                nodePaint.color = if (on) color else c.line
                canvas.drawCircle(cx(i), cy(i), dot, nodePaint)
                if (on) canvas.drawCircle(cx(i), cy(i), dot * 2.1f, ringPaint)
            }
        }
    }
}
