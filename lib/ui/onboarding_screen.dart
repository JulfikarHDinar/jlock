import 'package:flutter/material.dart';

import '../native.dart';
import '../settings.dart';
import '../theme.dart';
import 'apps_screen.dart';
import 'battery.dart';
import 'brand.dart';
import 'home_screen.dart';
import 'lock_method_screen.dart';
import 'permissions_screen.dart';

/// First-run setup. Walks what jLock needs — a lock, the detection permission, a list of apps, and no
/// battery restrictions — then opens the app. Reuses the same screens the Home checklist links to, so there's one
/// place that does each job. Shown until a lock method exists; after that the Home checklist takes over.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  late final AppLifecycleListener _lifecycle;
  bool _lockSet = false;
  bool _permsOk = false;
  int _lockedCount = 0;
  bool _batteryOk = false;

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
      Native.lockMethods(),
      engine == 'usage' ? Native.hasUsageAccess() : Native.isAccessibilityEnabled(),
      engine == 'usage' ? Native.canDrawOverlays() : Future<bool>.value(true),
      Native.getLockedApps(),
      Native.isIgnoringBatteryOptimizations(),
    ]);
    if (!mounted) return;
    final methods = results[0] as ({List<String> enabled, String defaultMethod, bool fingerprintAvailable, bool hasSecret});
    setState(() {
      _lockSet = methods.enabled.isNotEmpty;
      _permsOk = (results[1] as bool) && (results[2] as bool);
      _lockedCount = (results[3] as List).length;
      _batteryOk = results[4] as bool;
    });
  }

  Future<void> _open(Widget screen) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
    _refresh();
  }

  Future<void> _enter() async {
    await Native.setOnboarded();
    if (!mounted) return;
    Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => const HomeScreen()));
  }

  /// What's still missing, in step order — jLock can't be entered until every step is done.
  List<String> get _missing => [
        if (!_lockSet) 'create your lock',
        if (!_permsOk) 'allow jLock to work',
        if (_lockedCount == 0) 'choose apps to lock',
        if (!_batteryOk) 'remove battery restrictions',
      ];

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final missing = _missing;
    final allDone = missing.isEmpty;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
              children: [
                const SizedBox(height: 12),
                const Center(child: BrandMark(size: 64)),
                const SizedBox(height: 18),
                Text('Welcome to jLock', textAlign: TextAlign.center, style: tt.headlineSmall),
                const SizedBox(height: 6),
                Text(
                  'A few quick steps and your apps are protected.',
                  textAlign: TextAlign.center,
                  style: tt.bodyMedium!.copyWith(color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 28),
                _Step(
                  n: 1,
                  title: 'Create your lock',
                  subtitle: _lockSet ? 'Lock method set.' : 'A PIN, pattern, password, or fingerprint.',
                  done: _lockSet,
                  onTap: () => _open(const LockMethodScreen()),
                ),
                _Step(
                  n: 2,
                  title: 'Allow jLock to work',
                  subtitle: _permsOk ? 'Permission granted.' : 'Choose how it detects apps, then grant the permission.',
                  done: _permsOk,
                  enabled: _lockSet,
                  onTap: () => _open(const PermissionsScreen()),
                ),
                _Step(
                  n: 3,
                  title: 'Choose apps to lock',
                  subtitle: _lockedCount == 0 ? 'Pick the apps you want protected.' : '$_lockedCount selected.',
                  done: _lockedCount > 0,
                  enabled: _lockSet,
                  onTap: () => _open(const AppsScreen()),
                ),
                _Step(
                  n: 4,
                  title: 'Remove battery restrictions',
                  subtitle: _batteryOk ? 'No restrictions.' : 'So your phone never puts the lock to sleep.',
                  done: _batteryOk,
                  enabled: _lockSet,
                  onTap: () => showBatteryHelp(context),
                ),
                const SizedBox(height: 28),
                FilledButton(
                  onPressed: allDone ? _enter : null,
                  style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                  child: const Text('Enter jLock'),
                ),
                if (!allDone)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      'Finish every step to continue. Still to do: ${missing.join(', ')}.',
                      textAlign: TextAlign.center,
                      style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant),
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

class _Step extends StatelessWidget {
  const _Step({
    required this.n,
    required this.title,
    required this.subtitle,
    required this.done,
    required this.onTap,
    this.enabled = true,
  });

  final int n;
  final String title;
  final String subtitle;
  final bool done;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final p = Settings.current.value;
    final on = enabled || done;
    return Opacity(
      opacity: on ? 1 : 0.5,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Material(
          color: cardColor(p),
          borderRadius: BorderRadius.circular(20),
          child: InkWell(
            borderRadius: BorderRadius.circular(20),
            onTap: on ? onTap : null,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: done ? cs.primaryContainer : (p.light ? p.surface : p.raised),
                      shape: BoxShape.circle,
                    ),
                    child: done
                        ? Icon(Icons.check, size: 20, color: cs.primary)
                        : Text('$n', style: tt.titleMedium!.copyWith(color: cs.onSurfaceVariant)),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(title, style: tt.titleMedium),
                        const SizedBox(height: 2),
                        Text(subtitle, style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant)),
                      ],
                    ),
                  ),
                  Icon(done ? Icons.check_circle_rounded : Icons.chevron_right_rounded,
                      color: done ? cs.primary : cs.onSurfaceVariant),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
