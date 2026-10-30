package com.jlock.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The locked app's identity at the top of every lock face: its icon, its name and "Locked by jLock". One shared
 * header with fixed sizes, so the logo sits in exactly the same place on every method and never jumps when the
 * user switches method or when the icon finishes loading (its space is always reserved).
 */
@SuppressLint("ViewConstructor")
class LockHeader(context: Context, c: LockColors) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val name = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val dp = context.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        // Room above the logo: ~7% of the screen height (about 60 dp on a phone), so it sits comfortably low.
        val gap = (context.resources.displayMetrics.heightPixels * 0.07f).toInt().coerceIn(px(40f), px(88f))
        setPadding(0, gap, 0, 0)

        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(icon, LayoutParams(px(ICON_DP), px(ICON_DP)))

        name.apply {
            setTextColor(c.fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, px(14f), 0, 0)
        }
        addView(name, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, px(6f), 0, 0)
            addView(ImageView(context).apply {
                setImageDrawable(context.getDrawable(R.drawable.ic_lock_tag)?.mutate()?.also { it.setTint(c.accent) })
            }, LayoutParams(px(14f), px(14f)).apply { marginEnd = px(6f) })
            addView(TextView(context).apply {
                text = "Locked by jLock"; setTextColor(c.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        })
    }

    fun bind(label: String, d: Drawable?) {
        name.text = label
        icon.setImageDrawable(d)
    }

    private companion object {
        const val ICON_DP = 60f
    }
}
