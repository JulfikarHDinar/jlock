package com.jlock.app

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val channel = "jlock/native"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        LockStore.migrateIfNeeded(this) // one-time upgrade from the old single-method layout
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            when (call.method) {
                "isConfigured" -> result.success(LockStore.isConfigured(this))
                "isOnboarded" -> result.success(LockStore.onboarded(this))
                "setOnboarded" -> { LockStore.setOnboarded(this); result.success(true) }
                // Hashed off the main thread (the key derivation takes a moment), so the screen never freezes on Save.
                "setPin" -> {
                    val pin = call.argument<String>("pin")
                    if (pin.isNullOrEmpty()) result.error("bad_args", "pin required", null)
                    else saveSecret(pin, result) { LockStore.setPinCredential(this, it, pin.length) }
                }
                "setPattern" -> {
                    // The pattern arrives as its node path, e.g. "0-1-2-5-8"; hashed like any other secret.
                    val pattern = call.argument<String>("pattern")
                    if (pattern.isNullOrEmpty()) result.error("bad_args", "pattern required", null)
                    else saveSecret(pattern, result) { LockStore.setPatternCredential(this, it) }
                }
                "setPassword" -> {
                    // An alphanumeric password; hashed like any other secret (never stored in the clear).
                    val password = call.argument<String>("password")
                    if (password.isNullOrEmpty()) result.error("bad_args", "password required", null)
                    else saveSecret(password, result) { LockStore.setPasswordCredential(this, it) }
                }
                "setMethodEnabled" -> {
                    // Turn a method on/off (biometric flags, or remove a PIN/pattern with on=false).
                    val method = call.argument<String>("method")
                    val on = call.argument<Boolean>("on") ?: false
                    if (method == null) result.error("bad_args", "method required", null)
                    else { LockStore.setMethodEnabled(this, method, on); result.success(true) }
                }
                "setDefaultMethod" -> {
                    val method = call.argument<String>("method")
                    if (method == null) result.error("bad_args", "method required", null)
                    else { LockStore.setDefaultMethod(this, method); result.success(true) }
                }
                "lockMethods" -> result.success(
                    mapOf(
                        "enabled" to LockStore.enabledMethods(this),
                        "default" to LockStore.defaultMethod(this),
                        "fingerprintAvailable" to BiometricAuth.canFingerprint(this),
                        "hasSecret" to LockStore.hasSecretMethod(this),
                    )
                )
                "authenticate" -> {
                    // System fingerprint prompt over the Flutter UI (this Activity): the biometric gate and
                    // the setup confirm. null = "Use another method": the user's own PIN/pattern, not the device's.
                    BiometricAuth.authenticate(this, "Unlock jLock", "Use your fingerprint", "Use another method",
                        onFailed = { LockStore.logEvent(this, LockStore.LOG_FAIL, packageName) }) { ok ->
                        if (ok == true) LockStore.recordSuccess(this)
                        result.success(ok)
                    }
                }
                "verifyCredential" -> {
                    // Verify a secret-based method (PIN, pattern or password). Crypto stays native.
                    val method = call.argument<String>("method") ?: LockStore.PIN
                    val secret = call.argument<String>("secret") ?: ""
                    // A running lockout wait is enforced here too, not only by the screen's countdown.
                    val waiting = LockStore.lockoutRemainingMs(this)
                    if (waiting > 0) { result.success(mapOf("ok" to false, "waitMs" to waiting)); return@setMethodCallHandler }
                    CryptoManager.verifyAsync(secret, LockStore.verifierFor(this, method)) { ok ->
                        if (!ok) LockStore.logEvent(this, LockStore.LOG_FAIL, packageName)
                        if (ok) LockStore.recordSuccess(this) else LockStore.recordFailure(this)
                        result.success(mapOf("ok" to ok, "waitMs" to LockStore.lockoutRemainingMs(this)))
                    }
                }
                "authState" -> result.success(
                    mapOf(
                        "waitMs" to LockStore.lockoutRemainingMs(this),
                        "pinLength" to LockStore.pinLength(this),
                        "default" to LockStore.defaultMethod(this),
                        "methods" to LockStore.enabledMethods(this),
                        "patternVisible" to LockStore.patternVisible(this),
                    )
                )
                // --- lock-screen options ---
                "getLockPrefs" -> result.success(
                    mapOf(
                        "relock" to LockStore.relockMode(this),
                        "patternVisible" to LockStore.patternVisible(this),
                        "haptics" to LockStore.hapticsOn(this),
                    )
                )
                "setLockPref" -> {
                    when (call.argument<String>("key")) {
                        "relock" -> LockStore.setRelockMode(this, call.argument<String>("value") ?: LockStore.RELOCK_SCREEN_OFF)
                        "patternVisible" -> LockStore.setPatternVisible(this, call.argument<Boolean>("value") ?: true)
                        "haptics" -> LockStore.setHapticsOn(this, call.argument<Boolean>("value") ?: true)
                    }
                    result.success(true)
                }
                "haptic" -> {
                    when (call.argument<String>("kind")) {
                        "tick" -> Haptics.tick(this)
                        "error" -> Haptics.error(this)
                        else -> Haptics.key(this)
                    }
                    result.success(null)
                }
                // --- activity log ---
                "getLog" -> result.success(LockStore.eventLog(this))
                "clearLog" -> { LockStore.clearLog(this); result.success(true) }
                // --- the usage engine's status notification (only the user can switch it off) ---
                "isServiceNotificationHidden" -> {
                    val ch = getSystemService(NotificationManager::class.java).getNotificationChannel(UsageAccessLockService.CHANNEL)
                    result.success(ch?.importance == NotificationManager.IMPORTANCE_NONE)
                }
                "openServiceNotificationSettings" -> {
                    startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, UsageAccessLockService.CHANNEL)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    result.success(null)
                }
                // --- battery ---
                "isIgnoringBatteryOptimizations" ->
                    result.success(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                "requestIgnoreBatteryOptimizations" -> {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                        // Some OEMs hide the direct dialog; fall back to the full list.
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    result.success(null)
                }
                "getLockedApps" -> result.success(LockStore.lockedPackages(this).toList())
                "setLockedApps" -> {
                    val pkgs = call.argument<List<String>>("pkgs") ?: emptyList()
                    LockStore.setLockedPackages(this, pkgs.toSet()); result.success(true)
                }
                // Asking the package manager for every app's name, and drawing icons, takes a while: done in the
                // background so the picker scrolls smoothly.
                "listApps" -> inBackground(result) { listLaunchableApps() }
                "appIcon" -> { val pkg = call.argument<String>("pkg") ?: ""; inBackground(result) { appIconPng(pkg) } }
                "isAccessibilityEnabled" -> result.success(isAccessibilityEnabled())
                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(null)
                }
                // --- detection engine (accessibility vs usage access) ---
                "getLockEngine" -> result.success(LockStore.lockEngine(this))
                "setLockEngine" -> {
                    val engine = call.argument<String>("engine") ?: LockStore.ENGINE_USAGE
                    LockStore.setLockEngine(this, engine)
                    if (engine == LockStore.ENGINE_USAGE) UsageAccessLockService.startIfSelected(this)
                    else UsageAccessLockService.stop(this)
                    result.success(true)
                }
                "startUsageService" -> { UsageAccessLockService.startIfSelected(this); result.success(true) }
                "hasUsageAccess" -> result.success(hasUsageAccess())
                "openUsageAccessSettings" -> {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(null)
                }
                "canDrawOverlays" -> result.success(Settings.canDrawOverlays(this))
                "openOverlaySettings" -> {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    result.success(null)
                }
                // --- updates: jLock has no internet access, so the browser shows the latest release ---
                "appVersion" -> result.success(packageManager.getPackageInfo(packageName, 0).versionName)
                "openUrl" -> result.success(
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(call.argument<String>("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: ActivityNotFoundException) { false }
                )
                "openAppInfo" -> {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ); result.success(null)
                }
                "isNotificationAccessEnabled" -> result.success(isNotificationAccessEnabled())
                "openNotificationAccessSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(null)
                }
                "getNotifHiddenApps" -> result.success(LockStore.notifHiddenPackages(this).toList())
                "setNotifHiddenApps" -> {
                    val pkgs = call.argument<List<String>>("pkgs") ?: emptyList()
                    LockStore.setNotifHiddenPackages(this, pkgs.toSet()); result.success(true)
                }
                "hasPostNotifications" -> result.success(hasPostNotifications())
                "requestPostNotifications" -> { requestPostNotifications(); result.success(true) }
                "getThemeId" -> result.success(LockStore.themeId(this))
                "setTheme" -> {
                    val id = call.argument<String>("id") ?: "graphite"
                    val colors = HashMap<String, Int>()
                    for (k in listOf("bg", "surface", "raised", "line", "text", "muted", "accent", "onAccent")) {
                        call.argument<Number>(k)?.let { colors[k] = it.toInt() }
                    }
                    LockStore.setTheme(this, id, colors)
                    // Next launches open on a splash matching the palette's lightness (Android 13+).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        splashScreen.setSplashScreenTheme(if (call.argument<Boolean>("light") == true) R.style.SplashLight else 0)
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    /** Launchable apps (excluding jLock itself), as [{package,label}], sorted by label. */
    private fun listLaunchableApps(): List<Map<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else
                @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        return acts.asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { pkg -> mapOf("package" to pkg, "label" to labelOf(pm, pkg)) }
            .sortedBy { it["label"]!!.lowercase() }
            .toList()
    }

    /** [pkg]'s launcher icon drawn at 40dp and PNG-encoded for the picker; null if it can't be loaded. */
    private fun appIconPng(pkg: String): ByteArray? = try {
        val s = (40 * resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        packageManager.getApplicationIcon(pkg).apply { setBounds(0, 0, s, s) }.draw(Canvas(bmp))
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    } catch (_: Throwable) { null }

    private fun labelOf(pm: PackageManager, pkg: String): String =
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.contains("$packageName/") && it.contains("JLockAccessibilityService") }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { it.contains("$packageName/") && it.contains("JLockNotificationListener") }
    }

    private fun hasPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    private fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotifications())
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    /** Whether Usage Access is granted (the usage-access engine reads the foreground app through it). */
    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun saveSecret(secret: String, result: MethodChannel.Result, store: (String) -> Unit) =
        CryptoManager.hashAsync(secret) { v ->
            if (v == null) result.error("hash_failed", "couldn't secure the secret", null) else { store(v); result.success(true) }
        }

    private val background = Executors.newSingleThreadExecutor()
    private fun inBackground(result: MethodChannel.Result, work: () -> Any?) = background.execute {
        val r = try { Result.success(work()) } catch (t: Throwable) { Result.failure(t) }
        runOnUiThread { r.fold({ result.success(it) }, { result.error("failed", it.message, null) }) }
    }

    override fun onResume() {
        super.onResume()
        // Keep the usage-access service alive whenever jLock is opened and that engine is selected.
        UsageAccessLockService.startIfSelected(this)
    }

    // jLock's screens are opened now and then; left in the background, their Flutter engine would stay in memory for as
    // long as the lock keeps this process alive. Close them a while after the user leaves (they lock again on return
    // anyway); the wait keeps a trip to Settings to grant a permission from landing back on the home screen.
    private val handler = Handler(Looper.getMainLooper())
    private val closeWhenAway = Runnable { if (!isFinishing) finish() }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(closeWhenAway)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) handler.postDelayed(closeWhenAway, CLOSE_AFTER_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(closeWhenAway)
        background.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val CLOSE_AFTER_MS = 2 * 60_000L
    }
}
