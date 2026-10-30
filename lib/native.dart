import 'dart:convert';

import 'package:flutter/services.dart';

/// One app in the picker.
class AppInfo {
  const AppInfo(this.package, this.label);
  final String package;
  final String label;
}

/// One activity-log line: a failed unlock or a hidden notification.
class LogEntry {
  const LogEntry(this.type, this.package, this.label, this.time);
  final String type; // 'fail' or 'notif'
  final String package;
  final String label;
  final DateTime time;
}

/// Calls into MainActivity.kt (the lock engine config + permission helpers).
class Native {
  Native._();
  static const _ch = MethodChannel('jlock/native');

  static Future<bool> isConfigured() async => await _ch.invokeMethod<bool>('isConfigured') ?? false;

  /// The setup wizard was completed; until then it is the first screen.
  static Future<bool> isOnboarded() async => await _ch.invokeMethod<bool>('isOnboarded') ?? false;
  static Future<void> setOnboarded() => _ch.invokeMethod('setOnboarded');

  /// Adds/updates the PIN method (and makes it the default if none is set yet).
  static Future<void> setPin(String pin) => _ch.invokeMethod('setPin', {'pin': pin});

  /// Adds/updates the pattern method. [pattern] is the ordered node path, e.g. "0-1-2-5-8".
  static Future<void> setPattern(String pattern) => _ch.invokeMethod('setPattern', {'pattern': pattern});

  /// Adds/updates the password method (an alphanumeric secret).
  static Future<void> setPassword(String password) => _ch.invokeMethod('setPassword', {'password': password});

  /// Turns a method on/off. Used to enable fingerprint/device, or remove a configured method.
  static Future<void> setMethodEnabled(String method, bool on) =>
      _ch.invokeMethod('setMethodEnabled', {'method': method, 'on': on});

  /// Sets which configured method is shown first on the lock screen.
  static Future<void> setDefaultMethod(String method) =>
      _ch.invokeMethod('setDefaultMethod', {'method': method});

  /// The configured methods, the default one, whether a fingerprint is available, and whether a
  /// PIN/pattern exists (the required fallback before fingerprint can be turned on).
  static Future<({List<String> enabled, String defaultMethod, bool fingerprintAvailable, bool hasSecret})>
      lockMethods() async {
    final r = await _ch.invokeMethod<Map>('lockMethods') ?? const {};
    return (
      enabled: ((r['enabled'] as List?) ?? const []).cast<String>(),
      defaultMethod: (r['default'] as String?) ?? 'pin',
      fingerprintAvailable: r['fingerprintAvailable'] == true,
      hasSecret: r['hasSecret'] == true,
    );
  }

  /// Shows the system fingerprint prompt (BIOMETRIC_STRONG only). Returns true if authenticated, null when the user
  /// taps "Use another method" (switch to their PIN/pattern/password), false on any other cancel.
  static Future<bool?> authenticate() => _ch.invokeMethod<bool>('authenticate');

  /// Checks a secret ([secret]) for the given [method] ('pin' or 'pattern'); crypto runs natively.
  /// Returns whether it matched and, on failure, how long to wait before the next try.
  static Future<({bool ok, int waitMs})> verifyCredential(String method, String secret) async {
    final r = await _ch.invokeMethod<Map>('verifyCredential', {'method': method, 'secret': secret}) ?? const {};
    return (ok: r['ok'] == true, waitMs: ((r['waitMs'] as num?) ?? 0).toInt());
  }

  /// Lock-screen state: the current backoff wait (ms), the stored PIN length (for keypad auto-submit),
  /// the default method to show first, and all configured methods (for the "More options" switcher).
  static Future<({int waitMs, int pinLength, String defaultMethod, List<String> methods, bool patternVisible})>
      authState() async {
    final r = await _ch.invokeMethod<Map>('authState') ?? const {};
    return (
      waitMs: ((r['waitMs'] as num?) ?? 0).toInt(),
      pinLength: ((r['pinLength'] as num?) ?? 0).toInt(),
      defaultMethod: (r['default'] as String?) ?? 'pin',
      methods: ((r['methods'] as List?) ?? const []).cast<String>(),
      patternVisible: r['patternVisible'] != false,
    );
  }

  // --- lock-screen options ---

  /// relock: 'screen_off' or 'app_exit'; patternVisible: draw the pattern path; haptics: vibrate on touch.
  static Future<({String relock, bool patternVisible, bool haptics})> getLockPrefs() async {
    final r = await _ch.invokeMethod<Map>('getLockPrefs') ?? const {};
    return (
      relock: (r['relock'] as String?) ?? 'screen_off',
      patternVisible: r['patternVisible'] != false,
      haptics: r['haptics'] != false,
    );
  }

  static Future<void> setLockPref(String key, Object value) =>
      _ch.invokeMethod('setLockPref', {'key': key, 'value': value});

  /// Touch feedback: 'key' (a key press), 'tick' (a pattern dot), 'error' (wrong entry). Honors "Vibrate on touch".
  static void haptic(String kind) => _ch.invokeMethod('haptic', {'kind': kind});

  // --- activity log ---

  /// Failed unlocks ('fail') and hidden notifications ('notif'), oldest first.
  static Future<List<LogEntry>> getLog() async {
    final raw = await _ch.invokeMethod<String>('getLog') ?? '[]';
    return [
      for (final e in jsonDecode(raw) as List)
        LogEntry(e['type'] as String, e['pkg'] as String, e['label'] as String,
            DateTime.fromMillisecondsSinceEpoch((e['ts'] as num).toInt())),
    ];
  }

  static Future<void> clearLog() => _ch.invokeMethod('clearLog');

  /// The usage engine's "running" notification: whether the user switched it off, and a link to do so.
  static Future<bool> isServiceNotificationHidden() async =>
      await _ch.invokeMethod<bool>('isServiceNotificationHidden') ?? false;
  static Future<void> openServiceNotificationSettings() => _ch.invokeMethod('openServiceNotificationSettings');

  // --- battery ---

  static Future<bool> isIgnoringBatteryOptimizations() async =>
      await _ch.invokeMethod<bool>('isIgnoringBatteryOptimizations') ?? false;
  static Future<void> requestIgnoreBatteryOptimizations() => _ch.invokeMethod('requestIgnoreBatteryOptimizations');

  static Future<List<String>> getLockedApps() async =>
      (await _ch.invokeMethod<List<Object?>>('getLockedApps'))?.cast<String>() ?? const [];

  static Future<void> setLockedApps(List<String> pkgs) => _ch.invokeMethod('setLockedApps', {'pkgs': pkgs});

  static Future<List<AppInfo>> listApps() async {
    final raw = await _ch.invokeMethod<List<Object?>>('listApps') ?? const [];
    return [
      for (final e in raw)
        AppInfo((e as Map)['package'] as String, e['label'] as String),
    ];
  }

  /// An app's launcher icon as small PNG bytes for the picker, or null if it can't be loaded.
  static Future<Uint8List?> appIcon(String pkg) => _ch.invokeMethod<Uint8List>('appIcon', {'pkg': pkg});


  static Future<bool> isAccessibilityEnabled() async =>
      await _ch.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
  static Future<void> openAccessibilitySettings() => _ch.invokeMethod('openAccessibilitySettings');

  static Future<void> openAppInfo() => _ch.invokeMethod('openAppInfo');

  /// The installed version name, e.g. "1.0.0".
  static Future<String> appVersion() async => await _ch.invokeMethod<String>('appVersion') ?? '';

  /// Opens [url] in the browser; false when no browser is installed.
  static Future<bool> openUrl(String url) async => await _ch.invokeMethod<bool>('openUrl', {'url': url}) ?? false;

  // --- detection engine: 'accessibility' (instant) or 'usage' (works with banking apps) ---

  /// The chosen engine: 'usage' (default) or 'accessibility'.
  static Future<String> getLockEngine() async => await _ch.invokeMethod<String>('getLockEngine') ?? 'usage';
  static Future<void> setLockEngine(String engine) => _ch.invokeMethod('setLockEngine', {'engine': engine});

  /// Starts the usage-access watcher (no-op unless that engine is selected). Called when jLock resumes.
  static Future<void> startUsageService() => _ch.invokeMethod('startUsageService');

  /// Usage Access permission (lets the usage engine read which app is in front).
  static Future<bool> hasUsageAccess() async => await _ch.invokeMethod<bool>('hasUsageAccess') ?? false;
  static Future<void> openUsageAccessSettings() => _ch.invokeMethod('openUsageAccessSettings');

  /// "Display over other apps" — the usage engine needs it to raise the lock from the background.
  static Future<bool> canDrawOverlays() async => await _ch.invokeMethod<bool>('canDrawOverlays') ?? false;
  static Future<void> openOverlaySettings() => _ch.invokeMethod('openOverlaySettings');

  // --- Phase 2 privacy ---

  /// Whether jLock's notification listener is enabled (needed to mask locked apps' notifications).
  static Future<bool> isNotificationAccessEnabled() async =>
      await _ch.invokeMethod<bool>('isNotificationAccessEnabled') ?? false;
  static Future<void> openNotificationAccessSettings() => _ch.invokeMethod('openNotificationAccessSettings');

  /// Apps whose notifications are hidden (per-app opt-in, chosen in the Hidden notifications menu).
  static Future<List<String>> getNotifHiddenApps() async =>
      (await _ch.invokeMethod<List<Object?>>('getNotifHiddenApps'))?.cast<String>() ?? const [];
  static Future<void> setNotifHiddenApps(List<String> pkgs) =>
      _ch.invokeMethod('setNotifHiddenApps', {'pkgs': pkgs});

  /// POST_NOTIFICATIONS runtime permission (API 33+), required to show the masked notifications.
  static Future<bool> hasPostNotifications() async =>
      await _ch.invokeMethod<bool>('hasPostNotifications') ?? true;
  static Future<void> requestPostNotifications() => _ch.invokeMethod('requestPostNotifications');

  static Future<String> getThemeId() async => await _ch.invokeMethod<String>('getThemeId') ?? 'graphite';
  static Future<void> setTheme(Map<String, Object> args) => _ch.invokeMethod('setTheme', args);
}
