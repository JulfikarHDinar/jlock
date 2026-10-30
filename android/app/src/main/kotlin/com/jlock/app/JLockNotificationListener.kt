package com.jlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Per-app notification privacy. For each app the user picks in the "Hidden notifications" menu, that
 * app's notifications are replaced with a single generic entry ("New notification") on jLock's own
 * channel — content hidden, but the alert (sound/vibration/heads-up) preserved.
 *
 * - One entry per app: repeated alerts from the same app collapse onto it (tag = package name), so a
 *   chatty app shows one hidden marker, while different apps each get their own.
 * - Tapping the marker does nothing (no content intent, no auto-cancel); the user opens the app
 *   themselves, where the lock then guards it. The marker is swipeable.
 * - The marker alerts (and the activity log counts it) only for a real new alert. Once jLock has removed an
 *   app's notification, the app's later quiet updates to it (typing, read receipts, chat heads) arrive as new
 *   notifications that Android would alert for, so the app's own "alert once" flag is honoured here instead.
 * - Left alone: ongoing/foreground-service notifications (a cancel can't remove them, so masking would
 *   repeat on every update) and chat bubbles (cancelling one removes the chat head itself). A group summary
 *   is only removed when nothing left alone is in its group: removing a summary removes its whole group.
 *
 * Privacy: a notification's content is read only in memory to decide whether to hide it. Only the app
 * and the time go into jLock's on-device activity log; nothing is sent anywhere.
 *
 * Known Android limit: the source app's launcher badge (the dot/count on its icon) can't be kept while
 * its notification is hidden — a badge belongs to the app's own active notification, which has to be
 * removed to hide it, and no app can badge another app's icon. The hidden marker (and its badge on
 * jLock) is the signal instead.
 */
class JLockNotificationListener : NotificationListenerService() {
    private val tag = "jLockNotif"
    private var connected = false
    private val masked = HashMap<String, Long>() // notification key -> when jLock last replaced it (main thread only)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        ensureChannel()
        Log.i(tag, "connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val n = sbn ?: return
        try {
            if (!connected) return
            if (n.packageName == packageName) return                 // never mask our own marker
            if (!LockStore.isNotifHidden(this, n.packageName)) return // per-app opt-in, locked apps only
            val notif = n.notification ?: return
            if (notif.flags and LEAVE_ALONE != 0) return
            if (notif.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
                // A summary's own text can repeat the messages; hide it, unless that would take a chat bubble (or
                // another notification left alone) down with it.
                val keep = try {
                    activeNotifications.any { it.groupKey == n.groupKey && it.key != n.key && it.notification.flags and LEAVE_ALONE != 0 }
                } catch (_: Throwable) { true }
                if (!keep) cancelNotification(n.key)
                return
            }
            // Alert only when Android did (it already applied silent channels, Do Not Disturb, the ringer) and the app
            // didn't mean this as a quiet update of a notification jLock already replaced.
            val r = Ranking()
            val now = System.currentTimeMillis()
            masked.values.removeAll { now - it > MEMORY_MS }
            val quietUpdate = notif.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && masked.containsKey(n.key)
            val alerted = !quietUpdate && rankingMap?.getRanking(n.key, r) == true && r.lastAudiblyAlertedMillis >= n.postTime
            masked[n.key] = now

            val marker = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lock_tag)
                .setContentTitle(appLabel(n.packageName))
                .setContentText("New notification")
                .setLargeIcon(appIcon(n.packageName))
                .setAutoCancel(false)        // tapping must not dismiss it...
                .setWhen(n.postTime)          // (and no content intent is set, so a tap does nothing)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                // Its own group per app: otherwise Android bundles it with jLock's other notifications, and tapping
                // the bundle runs the bundle's action (it opened jLock). Separate apps stay separate entries.
                .setGroup("hidden:${n.packageName}")
                // A quiet marker: as a group child that defers alerts to a summary that never exists, it updates
                // silently (the same mechanism as Notification.Builder.setSilent, which needs Android 12).
                .apply { if (!alerted) setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY) }
                .build()
            // tag = package name: every alert from this app collapses onto one marker; other apps keep their own.
            (getSystemService(NotificationManager::class.java)).notify(n.packageName, MASK_ID, marker)
            cancelNotification(n.key)                                 // remove the real one (reveals nothing)
            if (alerted) LockStore.logEvent(this, LockStore.LOG_NOTIF, n.packageName)
        } catch (t: Throwable) {
            Log.e(tag, "mask failed", t) // fail open: on any error, leave the original notification as-is
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Locked app alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Hidden notifications from your locked apps."
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun appLabel(pkg: String): String =
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }

    /** The source app's launcher icon, so the marker clearly shows which app it is. */
    private fun appIcon(pkg: String): Icon? =
        try { Icon.createWithBitmap(toBitmap(packageManager.getApplicationIcon(pkg))) } catch (_: Throwable) { null }

    private fun toBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    companion object {
        private const val CHANNEL_ID = "jlock_masked"
        private const val MASK_ID = 7 // same id for all; the per-package tag keeps them separate
        private const val LEAVE_ALONE = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or
            Notification.FLAG_NO_CLEAR or Notification.FLAG_BUBBLE
        private const val MEMORY_MS = 30 * 60_000L // how long a replaced notification's quiet updates stay quiet
    }
}
