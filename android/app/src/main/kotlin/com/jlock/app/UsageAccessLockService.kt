package com.jlock.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.view.WindowManager
import android.util.Log

/**
 * The USAGE-ACCESS lock engine: a foreground service that watches which app is in front via
 * UsageStatsManager (no accessibility, so banking apps that refuse accessibility still run) and covers a
 * locked app with the lock overlay window ([LockOverlayManager] as an app overlay, ~30 ms to appear). Settings
 * and system dialogs hide other apps' overlays, so there it raises [LockActivity] instead (~250 ms).
 *
 * Detection is polled every [POLL_MS] while the screen is on (usage events report an app opening right away, so
 * the poll interval is the delay), and every [FAST_POLL_MS] while an app switch is under way; polling stops with
 * the screen off. A poll that brings no new events does no other work. The service must stay alive — set jLock to
 * unrestricted battery on OEMs that sleep background apps (One UI). "Display over other apps" is required: it
 * draws the overlay and allows the Activity start from the background.
 */
class UsageAccessLockService : Service() {
    private val tag = "jLockUsage"
    private val handler = Handler(Looper.getMainLooper())
    private val usm by lazy { getSystemService(UsageStatsManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var running = false
    private var ready = false // set up: needs the phone unlocked once since boot (settings live in its encrypted storage)
    private var overlay: LockOverlayManager? = null
    // Activities on screen right now ("pkg/class" -> pkg, most recently resumed last), kept up to date from usage
    // events. An activity counts until it STOPS, not when it pauses: a dialog from another app (a permission
    // prompt, a share sheet) pauses the app under it but leaves it visible, so its lock must stay up. Split screen
    // keeps two apps resumed at once, so a locked app beside an unlocked one is seen too.
    private val resumed = LinkedHashMap<String, String>()
    private val paused = HashSet<String>() // keys in [resumed] that are paused but still visible
    private var lastEventTs = 0L
    private val homePkg by lazy { LockStore.homePackage(this) }
    private var dirty = true   // something besides usage events changed (screen on, keyguard gone): check fully
    private var fastUntil = 0L // poll quickly until then (uptime): an app is leaving the screen, another may open
    private var homeOnTop = false
    private var launchedAt = 0L  // when the lock Activity was last asked for (uptime; see LockActivity.pendingFor)
    private var recheck = false  // decide() is waiting on something: run it again at the next poll

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { LockStore.lockAll(); overlay?.hide(); stopPolling() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { dirty = true; startPolling() }
            }
        }
    }

    // Started at boot before the phone's first unlock (BootReceiver): sets up the moment it is unlocked.
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { setUp() }
    }

    override fun onCreate() {
        super.onCreate()
        try { startForeground(FG_ID, buildNotification()) } catch (_: Throwable) {}
        // The lock is drawn and the polls run on this thread: keep it ahead of a busy app that is starting up.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Throwable) {}
        if (!unlocked()) registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        setUp() // also covers an unlock that came just before the receiver was in place
    }

    private fun unlocked() = getSystemService(UserManager::class.java)?.isUserUnlocked != false

    private fun setUp() {
        if (ready || !unlocked()) return
        ready = true
        try { unregisterReceiver(unlockReceiver) } catch (_: Throwable) {}
        // If the user switched to the accessibility engine, stand down.
        if (LockStore.lockEngine(this) != LockStore.ENGINE_USAGE) { stopSelf(); return }
        // Built now so the first lock costs no view-building.
        overlay = LockOverlayManager(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.attach() }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        startPolling()
        Log.i(tag, "started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!unlocked()) return START_STICKY // sets up once the phone is unlocked
        // If the user switched to the accessibility engine, stand down.
        if (LockStore.lockEngine(this) != LockStore.ENGINE_USAGE) { stopSelf(); return START_NOT_STICKY }
        setUp()
        startPolling()
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (_: Throwable) {}
            if (running) handler.postDelayed(this, if (SystemClock.uptimeMillis() < fastUntil) FAST_POLL_MS else POLL_MS)
        }
    }

    private fun startPolling() {
        if (running) return
        running = true
        handler.post(tick)
    }

    private fun stopPolling() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun check() {
        if (LockStore.lockEngine(this) != LockStore.ENGINE_USAGE) { stopSelf(); return }
        val changed = refreshOnScreen() ?: return
        if (!changed && !dirty) return // nothing new on screen since the last poll
        dirty = true // stays set if the pass below fails, so the next poll retries
        recheck = false
        decide()
        dirty = recheck
    }

    private fun decide() {
        // A Settings pop-up (a Bluetooth or Wi-Fi request, a quick-settings panel) is never locked: like a system dialog,
        // it only sits over the app in front, which stays visible under it — and it hides app overlays meanwhile.
        val popupInFront = resumed.keys.lastOrNull()?.let { isPopup(it) } == true
        val screens = resumed.filterKeys { !isPopup(it) }
        val onScreen = screens.values.reversed().distinct() // most recently opened first
        val ov = overlay
        val top = onScreen.firstOrNull()
        if (top == null) { if (ov?.currentPackage != null) ov.hide(); return }
        // The home screen is in front again — also when a chat bubble over it was closed, which reports no "home
        // opened": the app was left ("lock again when I leave").
        if (top == homePkg && !homeOnTop) LockStore.onHome(this)
        homeOnTop = top == homePkg
        // jLock's own screen in front (the lock Activity, the fingerprint prompt, the app): leave things as they are.
        if (top == packageName) return
        // Which locked app can be seen? A resumed one; a paused one only while a system dialog sits over it, or while
        // its lock is already up and it is on its way out (it stays covered until it has fully stopped).
        val active = screens.filterKeys { it !in paused }.values.toSet()
        // Two apps up at once (a chat bubble, split screen, a pop-up view): a bubble's window can sit above the lock's.
        val multi = active.count { it != packageName } > 1
        val dialogInFront = top in LockStore.SYSTEM_DIALOG_APPS || popupInFront
        val shown = ov?.currentPackage
        val pkg = onScreen.firstOrNull { p ->
            p != packageName && LockStore.isLocked(this, p) &&
                (p in active || dialogInFront || (p == shown && top !in FULL_HIDERS))
        }
        if (pkg == null) { if (shown != null) ov.hide(); return } // the locked app left the screen
        // Android hides other apps' overlays while Settings or a system dialog (share sheet, permission prompt) is on
        // screen; then the lock Activity is used instead: slower to appear (~250 ms), but nothing can hide it.
        val overlayHidden = pkg in FULL_HIDERS || top in FULL_HIDERS || dialogInFront
        if (LockActivity.showingFor == pkg || (!overlayHidden && shown == pkg && (!multi || ov?.coversFloating == true))) return // already covering it
        // The lock Activity is on its way (it takes a few hundred ms): asking again meanwhile restarted it over and over,
        // each time opening another fingerprint prompt. Looked at again until it is up, or seems lost (then asked again).
        if (LockActivity.pendingFor == pkg && SystemClock.uptimeMillis() - launchedAt < 1_000) { recheck = true; return }
        // The device keyguard always wins (checked only now, so a normal poll stays one cheap query).
        if (keyguard.isKeyguardLocked) { ov?.hide(); return }
        // Fast path: cover it with the overlay window (raised to the top first when another app shares the screen).
        if (!overlayHidden && ov != null && Settings.canDrawOverlays(this)) {
            ov.show(pkg, floatingWin = multi) { LockStore.markUnlocked(pkg); ov.hide() }
            Log.d(tag, "lock $pkg up")
            return
        }
        ov?.hide()
        try {
            LockActivity.pendingFor = pkg
            launchedAt = SystemClock.uptimeMillis()
            startActivity(Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("pkg", pkg)
                // Split screen (two apps resumed): the full-screen lock ends the split, so reopen the app after unlocking.
                putExtra("reopen", multi)
            })
        } catch (t: Throwable) {
            Log.e(tag, "launch lock failed (needs Display over other apps?)", t)
        }
    }

    private fun isPopup(key: String) = LockStore.isSettingsPopup(this, key.substringBefore('/'), key.substringAfter('/'))

    /**
     * Applies the usage events since the last poll to [resumed]: true if any changed it, false if none, null if they
     * couldn't be read. Replaying the boundary millisecond is harmless: each activity simply ends in its latest state.
     */
    private fun refreshOnScreen(): Boolean? {
        val now = System.currentTimeMillis()
        val begin = if (lastEventTs == 0L) now - FIRST_QUERY_MS else lastEventTs
        val events = try { usm.queryEvents(begin, now) } catch (_: Throwable) { null } ?: return null
        val e = UsageEvents.Event()
        var changed = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp > lastEventTs) lastEventTs = e.timeStamp
            // Settings asking for the phone's own PIN/fingerprint (Secure Folder, "confirm it's you"): not an app to
            // lock, and covering it would cancel its fingerprint check — leave it out of the picture entirely.
            if (LockStore.isCredentialScreen(e.packageName, e.className)) continue
            val key = "${e.packageName}/${e.className}"
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumed.remove(key); resumed[key] = e.packageName; paused.remove(key)
                    // Home is always full screen: nothing else can be showing. Also clears any stale entry.
                    if (e.packageName == homePkg) { resumed.keys.retainAll { it == key }; paused.clear(); LockStore.onHome(this) }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (resumed.containsKey(key)) paused.add(key)
                    // Something is leaving the screen: the next app's "opened" event is due any moment (later
                    // when it has to start from scratch), so look more often for a while to catch it sooner.
                    fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
                }
                UsageEvents.Event.ACTIVITY_STOPPED, ACTIVITY_DESTROYED -> { resumed.remove(key); paused.remove(key) }
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> { resumed.clear(); paused.clear() }
                // The phone's lock screen went away: the app in front shows from now on, so look again. (The unlock
                // broadcast does too, but right after a restart it came seconds late, with a locked app on screen.)
                UsageEvents.Event.KEYGUARD_HIDDEN -> {}
                else -> continue
            }
            changed = true
        }
        if (lastEventTs == 0L) lastEventTs = now
        return changed
    }

    /**
     * Android requires a notification for a foreground service and won't let an app hide it itself (it
     * raises a switched-off channel back to visible). The user can switch this channel off, and Android
     * respects that: the Permissions screen links straight to it. The service keeps running either way.
     */
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL) // the old, visible "jLock is protecting your apps" channel
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Background service", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps jLock watching which app is open. Safe to turn off."
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock_tag)
            .setContentTitle("jLock is protecting your apps")
            .setContentText("Tap to open jLock")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopPolling()
        overlay?.release()
        overlay = null
        try { unregisterReceiver(unlockReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val FG_ID = 42
        const val CHANNEL = "jlock_service"
        private const val OLD_CHANNEL = "jlock_fg"
        // How often the foreground app is checked while the screen is on: the lock appears at most this late.
        // Measured (release, emulator): 100 ms -> lock on screen ~108 ms median, 0.65% of one core; 50 ms -> ~79 ms,
        // 1.2%; 33 ms -> no faster (Android's own event timing dominates) at 2.75%. So 50 ms.
        private const val POLL_MS = 50L
        // While an app switch is under way (something just left the screen): ~1 frame, for up to [FAST_WINDOW_MS],
        // so a cold-starting app — which reports itself open only once its process is up — is caught right away.
        private const val FAST_POLL_MS = 16L
        private const val FAST_WINDOW_MS = 1_500L
        // Full-screen system apps that hide other apps' overlays while open (they hold HIDE_NON_SYSTEM_OVERLAY_WINDOWS).
        // System dialogs do it too: see LockStore.SYSTEM_DIALOG_APPS.
        private val FULL_HIDERS = setOf(LockStore.SETTINGS)
        private const val FIRST_QUERY_MS = 60 * 60_000L // on start, rebuild what's on screen from the last hour
        private const val ACTIVITY_DESTROYED = 24      // UsageEvents.Event.ACTIVITY_DESTROYED (not in the public SDK)

        /** Start the service if Usage-Access is the chosen engine (no-op otherwise). */
        fun startIfSelected(c: Context) {
            LockStore.saveBootEngine(c) // keeps the copy read at the next boot current (it's new in this version)
            if (LockStore.lockEngine(c) == LockStore.ENGINE_USAGE) start(c)
        }

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, UsageAccessLockService::class.java)) } catch (_: Throwable) {}
        }

        fun stop(c: Context) {
            try { c.stopService(Intent(c, UsageAccessLockService::class.java)) } catch (_: Throwable) {}
        }
    }
}
