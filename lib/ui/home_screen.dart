import 'package:flutter/material.dart';

import '../native.dart';
import 'apps_screen.dart';
import 'battery.dart';
import 'log_screen.dart';
import 'notifications_screen.dart';
import 'brand.dart';
import '../settings.dart';
import '../theme.dart';
import 'settings_screen.dart';
import 'permissions_screen.dart';
import 'lock_method_screen.dart';
import 'methods.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final AppLifecycleListener _lifecycle;
  bool _configured = false;
  bool _permissionsOk = false;
  int _lockedCount = 0;
  String _default = 'pin';
  int _methodCount = 0;
  bool _batteryOk = false;
  int _failCount = 0;
  int _notifCount = 0;
  int _hiddenCount = 0;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onResume: _refresh);
    _refresh();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final engine = await Native.getLockEngine();
    final results = await Future.wait([
      engine == 'usage' ? Native.hasUsageAccess() : Native.isAccessibilityEnabled(),
      engine == 'usage' ? Native.canDrawOverlays() : Future<bool>.value(true),
      Native.getLockedApps(),
      Native.lockMethods(),
      Native.isIgnoringBatteryOptimizations(),
      Native.getLog(),
      Native.getNotifHiddenApps(),
    ]);
    if (!mounted) return;
    final methods = results[3] as ({List<String> enabled, String defaultMethod, bool fingerprintAvailable, bool hasSecret});
    setState(() {
      _permissionsOk = (results[0] as bool) && (results[1] as bool);
      _lockedCount = (results[2] as List).length;
      _default = methods.defaultMethod;
      _methodCount = methods.enabled.length;
      _configured = methods.enabled.isNotEmpty;
      _batteryOk = results[4] as bool;
      final log = results[5] as List<LogEntry>;
      _failCount = log.where((e) => e.type == 'fail').length;
      _notifCount = log.length - _failCount;
      final locked = (results[2] as List).cast<String>().toSet();
      _hiddenCount = (results[6] as List<String>).where(locked.contains).length;
    });
  }

  Future<void> _open(Widget screen) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final permissionsOk = _permissionsOk;
    final active = _configured && permissionsOk && _lockedCount > 0;
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: ListView(
              padding: EdgeInsets.only(bottom: 24 + MediaQuery.viewPaddingOf(context).bottom),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 16, 12, 12),
                  child: Row(
                    children: [
                      const BrandMark(size: 34),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text('jLock',
                            style: Theme.of(context).textTheme.headlineSmall!
                                .copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.5)),
                      ),
                      IconButton(
                        icon: const Icon(Icons.settings_outlined),
                        tooltip: 'Settings',
                        onPressed: () => _open(const SettingsScreen()),
                      ),
                    ],
                  ),
                ),
                _StatusCard(active: active, lockedCount: _lockedCount, onTap: () => _open(const AppsScreen())),
                const SectionHeader('Setup'),
                CardGroup(children: [
                  _Tile(
                    icon: Icons.lock_outline,
                    title: 'Lock methods',
                    subtitle: _configured
                        ? '${methodName(_default)}${_methodCount > 1 ? ' +${_methodCount - 1} more' : ''}'
                        : 'Not set. Choose how jLock unlocks.',
                    done: _configured,
                    onTap: () => _open(const LockMethodScreen()),
                  ),
                  _Tile(
                    icon: Icons.security,
                    title: 'Permissions',
                    subtitle: permissionsOk ? 'Ready' : 'Needed so the lock can appear',
                    done: permissionsOk,
                    onTap: () => _open(const PermissionsScreen()),
                  ),
                  _Tile(
                    icon: Icons.apps,
                    title: 'Locked apps',
                    subtitle: _lockedCount == 0 ? 'None yet. Choose apps to lock.' : '$_lockedCount selected',
                    done: _lockedCount > 0,
                    onTap: () => _open(const AppsScreen()),
                  ),
                  _Tile(
                    icon: Icons.battery_charging_full,
                    title: 'Battery',
                    subtitle: _batteryOk ? 'No restrictions' : 'Remove restrictions so the lock keeps running',
                    done: _batteryOk,
                    onTap: () => showBatteryHelp(context),
                  ),
                ]),
                const SectionHeader('Privacy'),
                CardGroup(children: [
                  _Tile(
                    icon: Icons.notifications_off_outlined,
                    title: 'Hidden notifications',
                    subtitle: _hiddenCount == 0
                        ? 'Choose which locked apps hide their notifications'
                        : '$_hiddenCount app${_hiddenCount == 1 ? '' : 's'} hidden',
                    done: false,
                    onTap: () => _open(const NotificationsScreen()),
                  ),
                  _Tile(
                    icon: Icons.history,
                    title: 'Activity log',
                    subtitle: '$_failCount failed unlock${_failCount == 1 ? '' : 's'} · '
                        '$_notifCount hidden notification${_notifCount == 1 ? '' : 's'}',
                    done: false,
                    onTap: () => _open(const LogScreen()),
                  ),
                ]),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// The state of protection at a glance: on the accent colour when jLock is guarding apps, a quiet card while setup
/// is unfinished.
class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.active, required this.lockedCount, required this.onTap});
  final bool active;
  final int lockedCount;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = Settings.current.value;
    final tt = Theme.of(context).textTheme;
    final fg = active ? p.onAccent : p.text;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Material(
        color: active ? p.accent : cardColor(p),
        borderRadius: BorderRadius.circular(24),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 22),
            decoration: active
                ? BoxDecoration(
                    // A soft sheen across the accent, for depth.
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [Colors.white.withValues(alpha: 0.14), Colors.white.withValues(alpha: 0.0)],
                    ),
                  )
                : null,
            child: Row(
              children: [
                Container(
                  width: 52,
                  height: 52,
                  decoration: BoxDecoration(
                    color: active ? p.onAccent.withValues(alpha: 0.14) : (p.light ? p.surface : p.raised),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(active ? Icons.verified_user_rounded : Icons.lock_open_rounded,
                      color: active ? p.onAccent : p.muted, size: 28),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(active ? 'Protected' : 'Setup needed',
                          style: tt.titleLarge!.copyWith(color: fg, fontWeight: FontWeight.w700)),
                      const SizedBox(height: 2),
                      Text(
                        active
                            ? 'Guarding $lockedCount app${lockedCount == 1 ? '' : 's'}'
                            : 'Finish the steps below to start',
                        style: tt.bodyMedium!.copyWith(color: active ? fg.withValues(alpha: 0.8) : p.muted),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.icon, required this.title, required this.subtitle, required this.done, required this.onTap});
  final IconData icon;
  final String title;
  final String subtitle;
  final bool done;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListTile(
      contentPadding: const EdgeInsets.fromLTRB(16, 6, 12, 6),
      leading: IconChip(icon, active: done),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: done
          ? Icon(Icons.check_circle_rounded, color: cs.primary, size: 22)
          : Icon(Icons.chevron_right_rounded, color: cs.onSurfaceVariant),
      onTap: onTap,
    );
  }
}
