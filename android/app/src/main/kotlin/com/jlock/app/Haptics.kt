package com.jlock.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Short, crisp feedback for the lock screens, using the phone's own tuned effects (the same ones the system
 * keyboard and lock screen use). Controlled only by jLock's "Vibrate on touch".
 *
 * Played as hardware-key feedback, not "touch" feedback: Android drops touch vibrations whenever the phone's
 * touch-vibration switch is off (common on Samsung), which made these silent; key-press feedback still plays.
 */
object Haptics {
    fun key(c: Context) = play(c, VibrationEffect.EFFECT_CLICK)   // a key or button press
    fun tick(c: Context) = play(c, VibrationEffect.EFFECT_TICK)   // a pattern dot joined
    fun error(c: Context) = play(c, VibrationEffect.EFFECT_DOUBLE_CLICK) // wrong PIN/pattern/password

    private fun play(c: Context, effect: Int) {
        if (!LockStore.hapticsOn(c)) return
        try {
            val v = c.getSystemService(Vibrator::class.java) ?: return
            val e = VibrationEffect.createPredefined(effect)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
            } else {
                // Android 11-12: media usage isn't tied to the touch-vibration switch.
                v.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            }
        } catch (_: Throwable) {}
    }
}
