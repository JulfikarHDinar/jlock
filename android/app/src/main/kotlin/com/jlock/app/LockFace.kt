package com.jlock.app

import android.graphics.drawable.Drawable

/** What the overlay needs from a lock screen, so it can hold either the PIN pad or the pattern pad. */
interface LockFace {
    var onUnlock: () -> Unit
    fun bind(label: String, icon: Drawable?)
    fun reset()
    /** Show and enforce a lockout wait already running (set by an earlier wrong try, here or elsewhere). */
    fun lockout(ms: Long) {}
}
