package com.jlock.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for the lock engine, backed by SharedPreferences.
 * Package names / which lock types are on are NOT secret (plain prefs). The credential verifier is
 * produced by CryptoManager (already one-way + hardware-encrypted) before it is stored here.
 * Session state (apps unlocked right now) is in-memory only and cleared on screen-off.
 */
object LockStore {
    private const val PREFS = "jlock"
    private const val KEY_LOCKED = "locked_pkgs"
    private const val KEY_PIN = "pin_verifier"
    private const val KEY_FAILS = "fail_count"

    private val sessionUnlocked = ConcurrentHashMap.newKeySet<String>()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hear about setting changes (the lock rebuilds itself right away instead of when it next has to appear). */
    fun watch(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).registerOnSharedPreferenceChangeListener(l)
    fun unwatch(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)

    // Parsed once per change: the engines ask on every check.
    @Volatile private var lockedCache: Pair<String, Set<String>>? = null

    fun lockedPackages(c: Context): Set<String> {
        val raw = prefs(c).getString(KEY_LOCKED, null) ?: return emptySet()
        lockedCache?.let { if (it.first == raw) return it.second }
        return raw.split(',').filter { it.isNotBlank() }.toSet().also { lockedCache = raw to it }
    }

    /** Saving the locked list also drops "hide notifications" for apps that are no longer locked. */
    fun setLockedPackages(c: Context, pkgs: Set<String>) {
        prefs(c).edit().putString(KEY_LOCKED, pkgs.joinToString(",")).apply()
        setNotifHiddenPackages(c, notifHiddenPackages(c).intersect(pkgs))
    }

    // --- credentials: several methods can be configured at once; one is the default shown first. ---
    // PIN, pattern and password each have their own CryptoManager-hashed verifier. Fingerprint is
    // OS-verified (BIOMETRIC_STRONG only, no device credential), so it is just an on/off flag with no
    // stored secret, and it always has a jLock secret method as a fallback (enforced below). The lock
    // screen shows the default method and offers the other configured ones under "More options".
    const val PIN = "pin"
    const val PATTERN = "pattern"
    const val PASSWORD = "password"
    const val FINGERPRINT = "fingerprint"
    private const val K_PATTERN = "pattern_verifier"
    private const val K_PASSWORD = "password_verifier"
    private const val K_FP = "fp_enabled"
    private const val K_DEFAULT = "default_method"

    /** The stored verifier for a secret-based method, or null (biometric method, or not set). */
    fun verifierFor(c: Context, method: String): String? = when (method) {
        PIN -> prefs(c).getString(KEY_PIN, null)
        PATTERN -> prefs(c).getString(K_PATTERN, null)
        PASSWORD -> prefs(c).getString(K_PASSWORD, null)
        else -> null
    }

    fun pinLength(c: Context): Int = prefs(c).getInt("pin_len", 0)

    fun setPinCredential(c: Context, verifier: String, len: Int) {
        prefs(c).edit().putString(KEY_PIN, verifier).putInt("pin_len", len).apply()
        ensureDefault(c, PIN)
    }
    fun setPatternCredential(c: Context, verifier: String) {
        prefs(c).edit().putString(K_PATTERN, verifier).apply()
        ensureDefault(c, PATTERN)
    }
    fun setPasswordCredential(c: Context, verifier: String) {
        prefs(c).edit().putString(K_PASSWORD, verifier).apply()
        ensureDefault(c, PASSWORD)
    }
    /** Turn a method on/off. Fingerprint = a flag; PIN/pattern/password "off" clears their verifier. */
    fun setMethodEnabled(c: Context, method: String, on: Boolean) {
        when (method) {
            FINGERPRINT -> prefs(c).edit().putBoolean(K_FP, on).apply()
            PIN -> if (!on) prefs(c).edit().remove(KEY_PIN).remove("pin_len").apply()
            PATTERN -> if (!on) prefs(c).edit().remove(K_PATTERN).apply()
            PASSWORD -> if (!on) prefs(c).edit().remove(K_PASSWORD).apply()
        }
        // Fingerprint must always have a secret-method fallback — if the last one is removed, drop it too.
        if (!on && method != FINGERPRINT && !hasSecretMethod(c)) {
            prefs(c).edit().putBoolean(K_FP, false).apply()
        }
        if (on) ensureDefault(c, method) else repickDefaultIfNeeded(c)
    }

    fun enabledMethods(c: Context): List<String> {
        val p = prefs(c)
        val m = mutableListOf<String>()
        if (!p.getString(KEY_PIN, null).isNullOrEmpty()) m.add(PIN)
        if (!p.getString(K_PATTERN, null).isNullOrEmpty()) m.add(PATTERN)
        if (!p.getString(K_PASSWORD, null).isNullOrEmpty()) m.add(PASSWORD)
        if (p.getBoolean(K_FP, false)) m.add(FINGERPRINT)
        return m
    }

    /** A non-biometric method (PIN, pattern or password) is set — the required fallback for fingerprint. */
    fun hasSecretMethod(c: Context): Boolean {
        val p = prefs(c)
        return !p.getString(KEY_PIN, null).isNullOrEmpty() ||
            !p.getString(K_PATTERN, null).isNullOrEmpty() ||
            !p.getString(K_PASSWORD, null).isNullOrEmpty()
    }

    fun defaultMethod(c: Context): String {
        val d = prefs(c).getString(K_DEFAULT, null)
        val en = enabledMethods(c)
        return if (d != null && en.contains(d)) d else (en.firstOrNull() ?: PIN)
    }
    fun setDefaultMethod(c: Context, method: String) {
        if (enabledMethods(c).contains(method)) prefs(c).edit().putString(K_DEFAULT, method).apply()
    }
    private fun ensureDefault(c: Context, method: String) {
        val d = prefs(c).getString(K_DEFAULT, null)
        if (d == null || !enabledMethods(c).contains(d)) prefs(c).edit().putString(K_DEFAULT, method).apply()
    }
    private fun repickDefaultIfNeeded(c: Context) {
        val d = prefs(c).getString(K_DEFAULT, null)
        if (d == null || !enabledMethods(c).contains(d)) {
            val first = enabledMethods(c).firstOrNull()
            prefs(c).edit().apply { if (first != null) putString(K_DEFAULT, first) else remove(K_DEFAULT) }.apply()
        }
    }

    fun isBiometricMethod(method: String): Boolean = method == FINGERPRINT

    /** The setup wizard was completed (lock, permissions, apps and battery all done) — until then it shows first. */
    fun onboarded(c: Context): Boolean = prefs(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) = prefs(c).edit().putBoolean("onboarded", true).apply()
    fun isConfigured(c: Context): Boolean = enabledMethods(c).isNotEmpty()

    /** One-time migration from the old single-method (cred_type + one shared verifier) layout. */
    fun migrateIfNeeded(c: Context) {
        val p = prefs(c)
        if (p.contains(K_DEFAULT) || !p.contains("cred_type")) return
        val old = p.getString("cred_type", PIN) ?: PIN
        val e = p.edit()
        when (old) {
            PATTERN -> { p.getString(KEY_PIN, null)?.let { e.putString(K_PATTERN, it) }; e.remove(KEY_PIN).remove("pin_len"); e.putString(K_DEFAULT, PATTERN) }
            PASSWORD -> { p.getString(KEY_PIN, null)?.let { e.putString(K_PASSWORD, it) }; e.remove(KEY_PIN).remove("pin_len"); e.putString(K_DEFAULT, PASSWORD) }
            PIN -> e.putString(K_DEFAULT, PIN) // verifier already in pin_verifier
            // "device"/"fingerprint" old defaults stored no usable secret; drop them so the user re-sets.
            else -> e.remove(KEY_PIN).remove("pin_len")
        }
        e.remove("cred_type").remove("device_enabled").apply()
    }

    fun fails(c: Context): Int = prefs(c).getInt(KEY_FAILS, 0)

    /** Escalating lockout after repeated wrong tries. Never a hard block — the wait only grows.
     *  Shared by the overlay lock and the in-app settings gate so one throttle covers both. */
    fun requiredWaitMs(fails: Int): Long = if (fails < 5) 0L else minOf(30_000L * (fails - 4), MAX_WAIT_MS)
    private const val MAX_WAIT_MS = 300_000L

    /**
     * One wrong try, from any lock face or the in-app gate. Starts the lockout wait (if due) as a single deadline on
     * the monotonic clock that every face and the gate respect — switching methods or reopening the app can't skip
     * it. Returns the new failure count.
     */
    fun recordFailure(c: Context): Int {
        val n = fails(c) + 1
        val wait = requiredWaitMs(n)
        prefs(c).edit().putInt(KEY_FAILS, n).putLong("lockout_until", SystemClock.elapsedRealtime() + wait).apply()
        return n
    }

    fun recordSuccess(c: Context) = prefs(c).edit().putInt(KEY_FAILS, 0).remove("lockout_until").apply()

    /** Time left on the lockout wait (0 = none). A deadline further away than the longest wait means the phone
     *  rebooted (the monotonic clock restarted), so it is ignored. */
    fun lockoutRemainingMs(c: Context): Long {
        val left = prefs(c).getLong("lockout_until", 0L) - SystemClock.elapsedRealtime()
        return if (left in 1..MAX_WAIT_MS) left else 0L
    }

    // --- what the engine asks ---
    fun isLocked(c: Context, pkg: String): Boolean =
        lockedPackages(c).contains(pkg) && !sessionUnlocked.contains(pkg) && isConfigured(c)

    fun markUnlocked(pkg: String) { sessionUnlocked.add(pkg) }

    // --- re-lock timing ---
    const val RELOCK_SCREEN_OFF = "screen_off" // unlocked apps stay open until the screen turns off
    const val RELOCK_APP_EXIT = "app_exit"     // also re-lock as soon as the app leaves the screen
    fun relockMode(c: Context): String = prefs(c).getString("relock", RELOCK_SCREEN_OFF) ?: RELOCK_SCREEN_OFF
    fun setRelockMode(c: Context, v: String) = prefs(c).edit().putString("relock", v).apply()

    /**
     * Called by the engines when the home screen (or Recents, part of it) comes forward. In "lock again when I
     * leave" mode, every unlocked app locks again. Only home counts as leaving: share sheets, photo pickers,
     * in-app browser tabs and permission prompts open other apps over yours without you leaving it.
     */
    fun onHome(c: Context) {
        if (relockMode(c) != RELOCK_APP_EXIT) return
        val keep = bubbleUnlock?.takeIf { SystemClock.elapsedRealtime() - it.second < 3_000 }?.first
        sessionUnlocked.clear()
        keep?.let { sessionUnlocked.add(it) }
    }

    // A chat bubble unlocked by fingerprint closes as the prompt opens, so the home screen comes up right after: that
    // home screen (seen within a few seconds; it can be reported twice) must not lock it again, or the bubble could
    // never be opened that way.
    @Volatile private var bubbleUnlock: Pair<String, Long>? = null
    fun keepThroughNextHome(pkg: String) { bubbleUnlock = pkg to SystemClock.elapsedRealtime() }

    /**
     * System dialogs that open over the app in front (share sheets, permission prompts, the package installer). The
     * app under one stays visible, so its lock must stay up; Android also hides other apps' overlay windows while one
     * is shown (the Usage-Access engine switches to its lock Activity then).
     */
    val SYSTEM_DIALOG_APPS = setOf(
        "android", "com.android.intentresolver",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
    )

    /**
     * Settings screens that only ask for the phone's own PIN, pattern or fingerprint on behalf of something else:
     * Samsung's Secure Folder, a work profile, an app confirming it's you. A locked Settings must not cover them —
     * the lock's own fingerprint check cancels theirs, so they could never be unlocked. The rest of Settings
     * (and App info) stays locked.
     */
    fun isCredentialScreen(pkg: String?, cls: String?): Boolean {
        if (pkg != SETTINGS || cls == null) return false
        val c = cls.lowercase()
        return "knox" in c || ("confirm" in c && CREDENTIAL_WORDS.any { it in c })
    }
    const val SETTINGS = "com.android.settings"

    /**
     * A pop-up from Settings over whatever is open (a Bluetooth or Wi-Fi request, a quick-settings panel, a pairing
     * prompt) or a dialog window, rather than one of its full screens. Never locked: it's part of the phone's own
     * controls, and a lock over one trapped the user. (A dialog over a full screen needs no lock of its own: the
     * screen's decides.) A screen is told apart the way Android itself does, by whether its theme floats or is
     * see-through. Cached per screen; the first look at one asks the package manager (about a millisecond).
     */
    fun isSettingsPopup(c: Context, pkg: String?, cls: String?): Boolean {
        if (pkg != SETTINGS || cls == null) return false
        if (cls.endsWith("Dialog")) return true
        popups[cls]?.let { return it }
        val popup = try {
            val pm = c.packageManager
            val ai = pm.getActivityInfo(ComponentName(pkg, cls), 0)
            val theme = pm.getResourcesForApplication(ai.applicationInfo).newTheme().apply { applyStyle(ai.themeResource, true) }
            val a = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent)) // ids sorted, as required
            (a.getBoolean(0, false) || a.getBoolean(1, false)).also { a.recycle() }
        } catch (_: PackageManager.NameNotFoundException) {
            false // not one of its screens (a splash screen, another window)
        } catch (_: Throwable) {
            return false // try again next time
        }
        popups[cls] = popup
        return popup
    }
    private val popups = ConcurrentHashMap<String, Boolean>()
    private val CREDENTIAL_WORDS = listOf("lock", "credential", "password", "pattern", "pin", "keyguard", "biometric", "fingerprint")

    /** The default home app (launcher), which also provides Recents on most phones. */
    fun homePackage(c: Context): String? = try {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    } catch (_: Throwable) { null }

    // --- lock-screen feel ---
    fun patternVisible(c: Context): Boolean = prefs(c).getBoolean("pattern_visible", true)
    fun setPatternVisible(c: Context, v: Boolean) = prefs(c).edit().putBoolean("pattern_visible", v).apply()
    fun hapticsOn(c: Context): Boolean = prefs(c).getBoolean("haptics", true)
    fun setHapticsOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("haptics", v).apply()

    // --- activity log: failed unlocks and hidden notifications (app, time). Kept on the phone only. ---
    const val LOG_FAIL = "fail"
    const val LOG_NOTIF = "notif"
    private const val LOG_MAX = 500

    @Synchronized
    fun logEvent(c: Context, type: String, pkg: String) {
        try {
            val arr = JSONArray(prefs(c).getString("event_log", "[]"))
            arr.put(JSONObject().put("type", type).put("pkg", pkg).put("label", appLabel(c, pkg)).put("ts", System.currentTimeMillis()))
            while (arr.length() > LOG_MAX) arr.remove(0) // oldest first
            prefs(c).edit().putString("event_log", arr.toString()).apply()
        } catch (_: Throwable) {} // a log write must never break locking
    }

    fun eventLog(c: Context): String = prefs(c).getString("event_log", "[]") ?: "[]"
    @Synchronized fun clearLog(c: Context) = prefs(c).edit().remove("event_log").apply()

    private fun appLabel(c: Context, pkg: String): String =
        try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    // --- theme (chosen palette colors, mirrored from the Flutter UI so the lock matches the app) ---
    fun setTheme(c: Context, id: String, colors: Map<String, Int>) {
        val e = prefs(c).edit().putString("theme_id", id)
        for ((k, v) in colors) e.putInt("t_$k", v)
        e.apply()
    }
    fun themeId(c: Context): String = prefs(c).getString("theme_id", "graphite") ?: "graphite"
    fun themeColor(c: Context, key: String, def: Int): Int = prefs(c).getInt("t_$key", def)

    // --- detection engine (how jLock sees which app is in front) ---
    const val ENGINE_ACCESSIBILITY = "accessibility" // instant; breaks apps that refuse accessibility (some banks)
    const val ENGINE_USAGE = "usage"                 // uses Usage Access; a hair slower, but banking apps run
    // Usage Access is the default: banking apps keep working, and it locks about as fast.
    fun lockEngine(c: Context): String = prefs(c).getString("lock_engine", ENGINE_USAGE) ?: ENGINE_USAGE
    fun setLockEngine(c: Context, v: String) {
        prefs(c).edit().putString("lock_engine", v).apply()
        saveBootEngine(c)
    }

    // A copy of the engine choice in device-protected storage: the only storage readable after a restart before the
    // phone's first unlock, when the boot receiver starts the engine (see BootReceiver).
    private fun bootPrefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("jlock_boot", Context.MODE_PRIVATE)
    fun bootEngine(c: Context): String = bootPrefs(c).getString("lock_engine", ENGINE_USAGE) ?: ENGINE_USAGE
    fun saveBootEngine(c: Context) {
        val e = lockEngine(c)
        if (bootEngine(c) != e) bootPrefs(c).edit().putString("lock_engine", e).apply()
    }

    // --- Notification privacy (opt-in, per app; the listener reads this live) ---
    /** Apps whose notifications are hidden (content replaced by a single generic entry per app). */
    fun notifHiddenPackages(c: Context): Set<String> {
        val raw = prefs(c).getString("notif_hidden_pkgs", null) ?: return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }
    fun setNotifHiddenPackages(c: Context, pkgs: Set<String>) =
        prefs(c).edit().putString("notif_hidden_pkgs", pkgs.joinToString(",")).apply()
    fun isNotifHidden(c: Context, pkg: String): Boolean =
        notifHiddenPackages(c).contains(pkg) && lockedPackages(c).contains(pkg) // only while the app is locked

    /** Apps seen showing their own floating windows (chat heads), so the accessibility engine checks those first. */
    fun floatingApps(c: Context): Set<String> =
        prefs(c).getString("floating_apps", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    fun addFloatingApp(c: Context, pkg: String) =
        prefs(c).edit().putString("floating_apps", (floatingApps(c) + pkg).joinToString(",")).apply()

    /** Screen-off / device-lock: everything re-locks. */
    fun lockAll() { sessionUnlocked.clear() }
}
