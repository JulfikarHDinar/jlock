package com.jlock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * The lock engine. Event-driven (no polling): Android delivers a window-change event, we read the
 * foreground package and flip the pre-attached overlay visible. Battery cost at idle is ~zero.
 *
 * The window list is watched only while it's needed. While a service watches it, Android holds back every "app opened"
 * event until that app's window is listed: ~50 ms on a warm start, and its 500 ms time-out on a cold start (a splash
 * screen is never listed). Unwatched, the event arrives within a few ms of the app's first draw — even its splash
 * screen's — so the lock is up before any of the app shows. The list is needed to tell an app's screen from System UI,
 * the keyboard or another app's floating window before dropping a lock, for split screen, and to see a chat head open;
 * so it is watched while a lock is up, while split screen or a locked app's floating window is on screen, and briefly
 * to check a lock shown from an unwatched event.
 */
class JLockAccessibilityService : AccessibilityService() {
    private val tag = "jLockEngine"
    private var overlay: LockOverlayManager? = null
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val homePackage by lazy { LockStore.homePackage(this) }
    private val main = Handler(Looper.getMainLooper())
    // Lookups that call into another app's process run here, never on the event thread: that app may be busy
    // starting, and waiting on it held up every event behind it (the lock came 100-500 ms late).
    private var lookup: Handler? = null
    private var seq = 0 // bumped per event (main thread only); a lookup result is dropped if a newer event came in

    private var watching = false     // the window list is on (FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
    private var keepWatching = false // at the last look: split screen, or a locked app's floating window on screen
    // Who owns each floating window seen (window id -> package, "" if unknown), found once in the background.
    private val floatingOwner = ConcurrentHashMap<Int, String>()
    private val floaters = ConcurrentHashMap.newKeySet<String>() // apps seen floating their own windows (chat heads)
    private val screens = ConcurrentHashMap<String, Int>() // "pkg/class" -> NOT_SCREEN, SCREEN or EMBEDDABLE
    private val bubbleCapable = ConcurrentHashMap<String, Boolean>() // app -> has a screen that may be embedded
    private val lastScreen = ConcurrentHashMap<String, String>() // app -> class of its latest window event (its screen)
    private val popupWindows = ConcurrentHashMap.newKeySet<Int>() // windows of Settings pop-ups seen (see isPopup)

    /** A window to check against the list once it's in: was it the app's screen, or its floating window? [locked]: a
     *  lock is up for it; [fresh]: that lock went up for this window (none was up for the app before). */
    private class Check(val windowId: Int, val pkg: String, val locked: Boolean, val cls: CharSequence? = null, val fresh: Boolean = false)
    private var check: Check? = null
    private var checkSince = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                LockStore.lockAll()
                overlay?.hide()
            } else if (intent.action == Intent.ACTION_USER_PRESENT) {
                onAccessibilityEvent(null) // the keyguard just went away: lock the app in front if it needs it
                // A locked app's chat head may be on screen (unseen while unwatched): look, so its opening is caught.
                if (!watching && floaters.any { it in LockStore.lockedPackages(this@JLockAccessibilityService) }) lookAround()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LockStore.migrateIfNeeded(this) // in case the engine starts before the UI after an update
        floaters.addAll(LockStore.floatingApps(this) - LockStore.SETTINGS) // its floating windows are pop-ups, not chat heads
        overlay = LockOverlayManager(this).also {
            it.onBack = { performGlobalAction(GLOBAL_ACTION_HOME) } // reliable escape from the lock
            // Stepping aside for the fingerprint prompt must not show a chat bubble or chat head of the app.
            it.canStepAside = {
                val ws = if (watching) try { windows } catch (_: Throwable) { emptyList() } else emptyList()
                val listed = LockStore.lockedPackages(this)
                partScreenAppWindows(ws).isEmpty() &&
                    ws.none { w -> w.type == AccessibilityWindowInfo.TYPE_SYSTEM && isBig(w) && floatingOwner[w.id] in listed }
            }
            it.attach() // pre-inflate so the first lock has no view-building cost
        }
        lookup = Handler(HandlerThread("jLockLookup").apply { start() }.looper)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) })
        setWatching(false) // start unwatched: the fast way
        Log.i(tag, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // Only act when this is the chosen engine; in Usage-Access mode the foreground service locks instead
            // (and the user keeps accessibility off so banking apps run).
            if (LockStore.lockEngine(this) != LockStore.ENGINE_ACCESSIBILITY) { overlay?.hide(); return }
            // Settings asking for the phone's own PIN/fingerprint (Secure Folder, "confirm it's you"; covering it cancels its
            // fingerprint check), or a Settings pop-up (a Bluetooth or Wi-Fi request, a quick-settings panel): never locked,
            // and whatever lock is up stays up. Seen even under the keyguard: a pop-up shown there is still up after it.
            if (event != null && event.contentChangeTypes == 0) event.packageName?.toString()?.let { p ->
                if (LockStore.isCredentialScreen(p, event.className?.toString())) return
                // A pop-up can bring a Settings screen up under it (from the same task), which can't be used meanwhile: no
                // lock for one until it's in front again. The list is watched while the pop-up is up, to see that.
                if (isPopup(p, event.className, event.windowId)) {
                    if (overlay?.currentPackage == LockStore.SETTINGS) overlay?.hide()
                    if (!watching) lookAround()
                    return
                }
            }
            // The device keyguard always wins: never draw over it (alarms and calls over the lock screen stay usable).
            if (keyguard.isKeyguardLocked) { overlay?.hide(); return }
            val t0 = SystemClock.uptimeMillis()
            val age = event?.let { t0 - it.eventTime } ?: -1
            val ws = if (watching) try { windows } catch (_: Throwable) { emptyList() } else emptyList()
            if (ws.isNotEmpty()) {
                runCheck(ws)
                if (lockFloating(ws)) return
                // A Settings lock with one of its pop-ups in front: the screen under it can't be used meanwhile (see onApp).
                if (overlay?.currentPackage == LockStore.SETTINGS && underPopup(ws, -1)) overlay?.hide()
            }
            val pkg = eventPackage(event, ws) ?: return
            val mySeq = ++seq
            if (pkg != LOOKUP) { onApp(pkg, ws, t0, age, event); return }
            // No package on the event (a windows-changed burst, or the keyguard just went away): ask the top app
            // window (or, unwatched, the active one) in the background — that call waits on the app, which may still
            // be starting — and act only if no newer event came in meanwhile.
            lookup?.post {
                val top = if (ws.isEmpty()) null else ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val node = try { if (ws.isEmpty()) rootInActiveWindow else top?.root } catch (_: Throwable) { null }
                val p = node?.packageName?.toString()
                val wid = node?.windowId ?: -1
                val part = top?.let { isPart(it) } // null: not known (unwatched)
                if (p != null && part == true) screenKind(p, lastScreen[p]) // looked up here, off the main thread
                if (p != null) main.post { if (seq == mySeq) safely { onApp(p, ws, t0, age, null, part, wid) } }
            }
        } catch (t: Throwable) {
            Log.e(tag, "event error", t)
            overlay?.hide()
        } finally {
            updateWatching()
        }
    }

    /**
     * [pkg] is the app now in front ([inPart]: in a window covering only part of the screen — a chat bubble, split
     * screen). Locks it at once if needed; nothing slow runs before the lock is up. [event] is set when the package
     * came straight from the event (not looked up).
     */
    private fun onApp(
        pkg: String, ws: List<AccessibilityWindowInfo>, t0: Long, age: Long, event: AccessibilityEvent?,
        inPart: Boolean? = null, windowId: Int = -1,
    ) {
        if (pkg == packageName) return
        val ov = overlay ?: return
        // Looked up while something big from System UI covers the top app window (a chat bubble opening, the shade): the
        // app windows under it can be missing from the list (a bubble's own window shows up only once it has opened), so
        // the top one listed says nothing about what's in front — leave things as they are.
        if (event == null && underSystemUi(ws)) return
        if (pkg == homePackage) LockStore.onHome(this) // "lock again when I leave the app"
        // A system dialog (share sheet, permission prompt) or a Settings pop-up opened over the app in front: that app is
        // still visible behind it, so whatever lock is up stays up. The keyboard likewise.
        if (pkg in LockStore.SYSTEM_DIALOG_APPS || windowId in popupWindows || pkg == keyboardPackage()) return
        // The app's screen in front, for the windows-changed events that don't name one.
        event?.className?.let { lastScreen[pkg] = it.toString() }
        // Its window: part of the screen, all of it, or not known (unwatched, or not listed yet: a splash screen, a
        // window still opening).
        val part: Boolean? = inPart ?: event?.let { e -> ws.firstOrNull { it.id == e.windowId }?.let { isPart(it) } }
        // Unwatched, this window can't be told apart yet: it is checked against the list once that's in. An app known to
        // float its own windows (chat heads) gets that check before the lock when the window isn't one of its screens
        // (no flash of the lock as a chat head pops up); any other locked app is locked at once and checked right after
        // (split screen? its chat head? a Settings pop-up drawn as a plain window?); an unlocked one's non-screen windows
        // are checked to learn about chat heads.
        val unwatched = ws.isEmpty() && event != null && pkg in LockStore.lockedPackages(this)
        // A Settings screen under a pop-up of its own (it came up with it, from the same task): not locked while the
        // pop-up is up — it can't be used meanwhile — but as soon as it's in front again.
        val underPopup = pkg == LockStore.SETTINGS && underPopup(ws, event?.windowId ?: windowId)
        if (!underPopup && LockStore.isLocked(this, pkg)) {
            if (unwatched && pkg in floaters && !isActivity(pkg, event!!.className)) { await(event.windowId, pkg, locked = false, cls = event.className); return }
            // In part of the screen, and able to be shown in a chat bubble (the bubble floats above the app screens, so
            // the lock must not step aside for the fingerprint prompt). Only screens marked embeddable can be in one;
            // judging by size alone (or by the app having such a screen somewhere) mistook apps for bubbles while their
            // opening animation still had them small, and took fingerprint away from their lock.
            val floating = part == true && screenKind(pkg, event?.className ?: lastScreen[pkg]) == EMBEDDABLE
            val fresh = ov.currentPackage != pkg
            showLock(ov, pkg, floating)
            Log.d(tag, "lock $pkg ${SystemClock.uptimeMillis() - t0}ms after event (event ${age}ms old)")
            // The fingerprint prompt opens once the app is known to fill the screen (see confirmLater): stepping the lock
            // aside for it would show a chat bubble, and its screen opening would end split screen (the user taps the
            // fingerprint there, as on the Usage Access side).
            if (part == false) confirm(pkg)
            else if (part == null) (event?.windowId ?: windowId).let { if (it >= 0) await(it, pkg, locked = true, cls = event?.className, fresh = fresh) }
            return
        }
        if (unwatched && !isActivity(pkg, event!!.className)) await(event.windowId, pkg, locked = false)
        // Unwatched means no lock is up (the list is watched while one is), or the list is not in yet: then only the
        // home screen may drop a lock — anything else could be the keyboard or a floating window over it.
        if (ws.isEmpty()) { if (pkg == homePackage && ov.currentPackage != null) ov.hide(); return }
        // Usual case: an unlocked app filling the screen, nothing else showing — drop any lock now.
        val parts = partScreenAppWindows(ws)
        if (pkg == homePackage || parts.isEmpty()) {
            if (ov.currentPackage != null) ov.hide()
            return
        }
        // Something only part-screen is involved: split screen, a pop-up view, or a dialog from another app (a
        // permission prompt, a share sheet) over a locked app. Check every visible app window for a locked app;
        // finding a window's package calls into its process, so it runs in the background and any lock already up
        // stays up meanwhile.
        val apps = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val anyFull = apps.any { it !in parts }
        val mySeq = seq
        lookup?.post {
            var locked: Pair<String, Boolean>? = null // the locked app found, and whether it's in a chat bubble
            for (w in apps) {
                if (w.id in popupWindows) continue
                val p = try { w.root?.packageName?.toString() } catch (_: Throwable) { null }
                if (p != null && p != packageName && LockStore.isLocked(this, p)) {
                    locked = p to (w in parts && screenKind(p, lastScreen[p]) == EMBEDDABLE)
                    break
                }
            }
            main.post {
                if (seq != mySeq) return@post
                safely {
                    val o = overlay ?: return@safely
                    when {
                        locked != null -> showLock(o, locked.first, floating = locked.second)
                        // No full-screen app listed: a dialog may be hiding the locked app from the list — keep the lock.
                        anyFull && o.currentPackage != null -> o.hide()
                    }
                }
                updateWatching()
            }
        }
    }

    private fun showLock(ov: LockOverlayManager, pkg: String, floating: Boolean = false) {
        val was = ov.currentPackage
        ov.show(pkg, floating) {
            LockStore.markUnlocked(pkg)
            ov.hide()
            updateWatching()
        }
        if (was != pkg) Log.d(tag, "lock $pkg up")
    }

    // --- the window list, on demand ---

    /** Watch the list for a moment and look at the floating windows on screen. */
    private fun lookAround() {
        if (check == null) { check = Check(-1, "", locked = true); checkSince = SystemClock.uptimeMillis() } // no window to settle
        setWatching(true)
        main.removeCallbacks(pollList)
        main.post(pollList)
    }

    /** Check [windowId] of [pkg] once the window list is in ([locked]: a lock was already shown for it). */
    private fun await(windowId: Int, pkg: String, locked: Boolean, cls: CharSequence? = null, fresh: Boolean = false) {
        check = Check(windowId, pkg, locked, cls, fresh)
        checkSince = SystemClock.uptimeMillis()
        setWatching(true)
        main.removeCallbacks(pollList)
        main.post(pollList)
    }

    // The list arrives a frame or two after watching starts; until then, look each frame (give up after 300 ms).
    private val pollList = object : Runnable {
        override fun run() {
            if (check == null) return
            val ws = try { windows } catch (_: Throwable) { emptyList() }
            if (ws.isNotEmpty()) safely { runCheck(ws); lockFloating(ws) }
            if (check != null && SystemClock.uptimeMillis() - checkSince < 300) { main.postDelayed(this, 16); return }
            else safely { check?.let { c -> check = null; if (c.pkg.isNotEmpty() && !c.locked && LockStore.isLocked(this@JLockAccessibilityService, c.pkg)) overlay?.let { showLock(it, c.pkg) } } }
            updateWatching()
        }
    }

    /** Settle a pending [check]: the app's screen (or a splash screen, which is never listed) gets the lock; its
     *  floating window gets it only when opened up (see [lockFloating]), and a lock shown for a closed one goes. */
    private fun runCheck(ws: List<AccessibilityWindowInfo>) {
        val c = check ?: return
        val w = ws.firstOrNull { it.id == c.windowId }
        // Not listed: a splash screen (never listed), or a list from before the window came (just after watching starts,
        // the last one seen can come back) — give a new window a moment to show up before calling it a screen.
        if (w == null && c.pkg.isNotEmpty() && SystemClock.uptimeMillis() - checkSince < 120) return
        check = null
        if (c.pkg.isEmpty()) return // just looking (lookAround)
        val ov = overlay ?: return
        when {
            // A window of Settings that is neither one of its screens nor full screen: a pop-up drawn as a plain window.
            // Never locked (a lock that went up for it goes), and not a chat head.
            w != null && c.pkg == LockStore.SETTINGS && !isActivity(c.pkg, c.cls) &&
                (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || isPart(w)) -> {
                popupWindows.add(w.id)
                if (c.fresh && ov.currentPackage == c.pkg) ov.hide()
            }
            // A Settings screen that came up under a pop-up of its own: no lock while the pop-up is up (see onApp).
            c.pkg == LockStore.SETTINGS && underPopup(ws, c.windowId) -> if (c.locked && ov.currentPackage == c.pkg) ov.hide()
            w != null && w.type != AccessibilityWindowInfo.TYPE_APPLICATION -> {
                if (floaters.add(c.pkg)) LockStore.addFloatingApp(this, c.pkg)
                if (c.locked && !isBig(w) && ov.currentPackage == c.pkg) ov.hide()
            }
            !LockStore.isLocked(this, c.pkg) -> {}
            // A screen that may be embedded, in part of the screen: a chat bubble. (Any other in part of the screen:
            // split screen — no fingerprint prompt by itself.)
            w != null && isPart(w) -> if (screenKind(c.pkg, c.cls) == EMBEDDABLE) showLock(ov, c.pkg, floating = true)
            !c.locked -> { showLock(ov, c.pkg); confirmLater(c, w) }
            else -> confirmLater(c, w)
        }
    }

    /**
     * The lock's app is known not to be in part of the screen ([w] its listed window, or null: not listed — a splash
     * screen, or a screen under the lock): open the fingerprint prompt, unless it could still be in a chat bubble — a
     * screen marked embeddable, or a splash screen of an app that has one.
     */
    private fun confirmLater(c: Check, w: AccessibilityWindowInfo?) {
        when (if (w != null) SCREEN else screenKind(c.pkg, c.cls)) {
            SCREEN -> confirm(c.pkg)
            NOT_SCREEN -> bubbleCapable[c.pkg]?.let { if (!it) confirm(c.pkg) } ?: lookup?.post {
                if (!bubbleCapable(c.pkg)) main.post { confirm(c.pkg) }
            }
        }
    }

    /** [pkg]'s screen fills the screen: its fingerprint prompt may open. Not in split screen (the prompt's screen opening
     *  would end it), and not while no app window is listed: as apps move into split screen, the list has none for a
     *  moment and theirs looked full. The next window change confirms it then. */
    private fun confirm(pkg: String) {
        val ws = if (watching) try { windows } catch (_: Throwable) { emptyList() } else emptyList()
        if (ws.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } &&
            ws.none { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }) overlay?.confirmFullScreen(pkg)
    }

    /** Watch the list only while needed (see the class note). It stops a moment after the need ends, counted from then
     *  (the events that follow, such as the home screen's animation, don't push it back): an app opened right after
     *  leaving a lock then gets the fast, unwatched path. */
    private fun updateWatching() {
        val need = overlay?.active == true || keepWatching || check != null
        if (need) {
            main.removeCallbacks(stopWatching)
            stopPending = false
            if (!watching) setWatching(true)
        } else if (watching && !stopPending) {
            stopPending = true
            main.postDelayed(stopWatching, 200)
        }
    }

    private var stopPending = false
    private val stopWatching = Runnable {
        stopPending = false
        if (overlay?.active != true && !keepWatching && check == null) setWatching(false)
    }

    private fun setWatching(on: Boolean) {
        try {
            serviceInfo = serviceInfo.apply {
                flags = if (on) flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                else flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
            }
            watching = on
            if (!on) keepWatching = false
        } catch (_: Throwable) {}
    }

    /**
     * An app's own floating window opened up over the screen — a chat head tapped open (Messenger draws those itself,
     * no app screen involved). A locked app's one gets the lock; a small one (the closed chat head) never does, but
     * keeps the list watched so its opening is seen. Owners are found once per window, in the background (it asks
     * the app); true when the lock is up for one.
     */
    private fun lockFloating(ws: List<AccessibilityWindowInfo>): Boolean {
        val floating = ws.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
        val unknown = floating.filter { !floatingOwner.containsKey(it.id) }
        if (unknown.isNotEmpty()) {
            if (floatingOwner.size > 64) floatingOwner.keys.retainAll(ws.map { it.id }.toSet())
            for (w in unknown) floatingOwner[w.id] = "" // looked up once
            lookup?.post {
                for (w in unknown) floatingOwner[w.id] = try { w.root?.packageName?.toString() } catch (_: Throwable) { null } ?: ""
                main.post { safely { lockFloating(try { windows } catch (_: Throwable) { emptyList() }) }; updateWatching() } // as things are now
            }
        }
        val listed = LockStore.lockedPackages(this)
        var lockedFloating = false
        for (w in floating) {
            val owner = floatingOwner[w.id]?.takeIf { it.isNotEmpty() && it != packageName && it != SYSTEM_UI } ?: continue
            if (owner !in listed || owner == LockStore.SETTINGS) continue
            if (floaters.add(owner)) LockStore.addFloatingApp(this, owner)
            lockedFloating = true
            if (isBig(w) && LockStore.isLocked(this, owner)) {
                overlay?.let { showLock(it, owner, floating = true) }
                keepWatching = true
                return true
            }
        }
        // Also while a Settings pop-up is up: a Settings screen under it is locked when the pop-up goes.
        keepWatching = lockedFloating || partScreenAppWindows(ws).isNotEmpty() || ws.any { it.id in popupWindows }
        return false
    }

    /** Whether [pkg] has any screen that may be embedded (shown in a chat bubble). Asks the package manager once;
     *  call off the main thread the first time. */
    private fun bubbleCapable(pkg: String): Boolean = bubbleCapable.getOrPut(pkg) {
        try {
            packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities
                ?.any { it.flags and FLAG_ALLOW_EMBEDDED != 0 } ?: false
        } catch (_: Throwable) { true }
    }

    /** Whether [cls] is one of [pkg]'s screens (an Activity) rather than a window it floats over others. Cached. */
    private fun isActivity(pkg: String, cls: CharSequence?) = screenKind(pkg, cls) != NOT_SCREEN

    /** Is [cls] one of [pkg]'s screens — one that may be embedded (shown in a chat bubble) or not — or another kind of
     *  window (a splash screen, a floating window)? Cached. */
    private fun screenKind(pkg: String, cls: CharSequence?): Int {
        val c = cls?.toString() ?: return NOT_SCREEN
        return screens.getOrPut("$pkg/$c") {
            try {
                val f = packageManager.getActivityInfo(ComponentName(pkg, c), 0).flags
                if (f and FLAG_ALLOW_EMBEDDED != 0) EMBEDDABLE else SCREEN
            } catch (_: Throwable) { NOT_SCREEN }
        }
    }

    /** The current keyboard app: its windows sit over an app without replacing it. */
    private fun keyboardPackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    /** Any error drops the lock (fail open), so the phone always stays usable. */
    private inline fun safely(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(tag, "event error", t); overlay?.hide() }
    }

    /**
     * The package of the app that came forward, [LOOKUP] when the event doesn't say (the caller finds it), or null
     * when the event comes from System UI (shade, prompts, keyguard), the keyboard or an overlay — those sit above
     * an app without replacing it, so they must never drop or move the lock.
     */
    private fun eventPackage(event: AccessibilityEvent?, ws: List<AccessibilityWindowInfo>): String? {
        // A pane change inside an app is not an app switch, and one can arrive late from an app that just left
        // the screen (Clock does on HOME) — trusting it re-showed the lock over the home screen.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.contentChangeTypes != 0) return null
        val type = ws.firstOrNull { it.id == event?.windowId }?.type
        if (type != null && type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        event?.packageName?.toString()?.let { return if (it == SYSTEM_UI) null else it }
        return LOOKUP
    }

    /** App windows that cover only part of the screen (split screen, pop-up view; not a Settings pop-up). No IPC. */
    private fun partScreenAppWindows(ws: List<AccessibilityWindowInfo>) =
        ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id !in popupWindows && isPart(it) }

    /** A Settings pop-up or its dialog ([LockStore.isSettingsPopup]); its window is remembered for events naming none. */
    private fun isPopup(pkg: String, cls: CharSequence?, windowId: Int): Boolean {
        if (!LockStore.isSettingsPopup(this, pkg, cls?.toString())) return false
        if (popupWindows.size > 64) popupWindows.clear() // window ids aren't reused; old ones are just dropped
        if (windowId >= 0) popupWindows.add(windowId)
        return true
    }

    /** A big window that isn't an app's (System UI's chat bubble frame, the shade, a chat head) is over every app window. */
    private fun underSystemUi(ws: List<AccessibilityWindowInfo>): Boolean {
        for (w in ws) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) return false
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && isBig(w)) return true
        }
        return false
    }

    /** Window [windowId] is under a Settings pop-up: one is the top app window (the list runs from the top down; a
     *  pop-up's full-screen window also hides what's under it from the list). */
    private fun underPopup(ws: List<AccessibilityWindowInfo>, windowId: Int): Boolean {
        val top = ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        return top.id in popupWindows && top.id != windowId
    }

    private val rect = Rect()
    private fun isPart(w: AccessibilityWindowInfo): Boolean = areaOf(w) < screenArea() * 9 / 10
    private fun isBig(w: AccessibilityWindowInfo): Boolean = areaOf(w) * 10 >= screenArea() * 4 // 40% or more
    private fun areaOf(w: AccessibilityWindowInfo): Long { w.getBoundsInScreen(rect); return rect.width().toLong() * rect.height() }

    // The screen's area in pixels, kept between events (asking the window manager each time costs a call).
    private var area = 0L
    private fun screenArea(): Long {
        if (area == 0L) area = wm.currentWindowMetrics.bounds.let { it.width().toLong() * it.height() }
        return area
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        area = 0L // rotated or resized: measure again
    }

    override fun onInterrupt() {}

    private companion object {
        const val SYSTEM_UI = "com.android.systemui" // its first event can land before its window is listed
        const val LOOKUP = "" // eventPackage(): the event names no app; find the top app window's package
        const val NOT_SCREEN = 0
        const val SCREEN = 1
        const val EMBEDDABLE = 2
        const val FLAG_ALLOW_EMBEDDED = 0x80000000.toInt() // ActivityInfo's android:allowEmbedded (hidden constant)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        main.removeCallbacks(pollList)
        main.removeCallbacks(stopWatching)
        lookup?.looper?.quitSafely()
        lookup = null
        overlay?.release()
        overlay = null
        return super.onUnbind(intent)
    }
}
