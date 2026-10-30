import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../native.dart';
import 'brand.dart';

/// The "Hidden notifications" menu: pick which of your locked apps have their notifications hidden.
/// Each hidden app's alerts are replaced with one generic marker (content hidden, sound/vibration kept).
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  late final AppLifecycleListener _lifecycle;
  List<AppInfo>? _locked; // the locked apps, with labels
  final Set<String> _hidden = {}; // which of them hide notifications
  bool _notifAccess = false;
  bool _loaded = false;
  final _iconCache = <String, Uint8List?>{};
  final _iconLoads = <String, Future<Uint8List?>>{};

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onResume: _load);
    _load();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final apps = await Native.listApps();
    final lockedPkgs = (await Native.getLockedApps()).toSet();
    final hidden = await Native.getNotifHiddenApps();
    final access = await Native.isNotificationAccessEnabled();
    if (!mounted) return;
    setState(() {
      _locked = apps.where((a) => lockedPkgs.contains(a.package)).toList();
      _hidden
        ..clear()
        ..addAll(hidden.where(lockedPkgs.contains)); // ignore any stale entries for unlocked apps
      _notifAccess = access;
      _loaded = true;
    });
  }

  Future<void> _toggle(String pkg, bool on) async {
    if (on && !_notifAccess) {
      await _grantAccess();
      return; // returns here; onResume reloads once access is granted
    }
    if (on && !await Native.hasPostNotifications()) {
      await Native.requestPostNotifications();
    }
    setState(() => on ? _hidden.add(pkg) : _hidden.remove(pkg));
    await Native.setNotifHiddenApps(_hidden.toList());
  }

  Future<void> _grantAccess() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Notification access'),
        content: const Text(
          'To hide notifications from your locked apps, jLock needs Notification access.\n\n'
          'It reads a notification only in memory to decide whether to hide it. Only the app name and '
          'time are kept, in jLock\'s activity log on this phone. Nothing is sent anywhere.\n\n'
          'On the next screen, turn on jLock.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Not now')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Open settings')),
        ],
      ),
    );
    if (ok == true) await Native.openNotificationAccessSettings();
  }

  Widget _icon(String pkg) => FutureBuilder<Uint8List?>(
        future: _iconLoads.putIfAbsent(pkg, () => Native.appIcon(pkg).then((b) => _iconCache[pkg] = b)),
        initialData: _iconCache[pkg],
        builder: (_, s) => s.data == null
            ? const SizedBox.square(dimension: 40)
            : Image.memory(s.data!, width: 40, height: 40, gaplessPlayback: true),
      );

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final locked = _locked;
    return Scaffold(
      appBar: AppBar(title: const Text('Hidden notifications')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: !_loaded
              ? const Center(child: CircularProgressIndicator())
              : locked == null || locked.isEmpty
                  ? Padding(
                      padding: const EdgeInsets.all(32),
                      child: Center(
                        child: Text(
                          'Lock some apps first, then choose which ones hide notifications.',
                          textAlign: TextAlign.center,
                          style: tt.bodyMedium!.copyWith(color: cs.onSurfaceVariant),
                        ),
                      ),
                    )
                  : ListView(
                      padding: EdgeInsets.only(bottom: 24 + MediaQuery.viewPaddingOf(context).bottom),
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(20, 16, 20, 4),
                          child: Text(
                            'Hidden apps show one generic alert instead of their content. Sound and '
                            'vibration are kept; tapping it does nothing. Chat bubbles (chat heads) are left '
                            'as they are: hiding one would remove it.',
                            style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant),
                          ),
                        ),
                        if (!_notifAccess)
                          Padding(
                            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                            child: Material(
                              color: cs.surfaceContainerLow,
                              borderRadius: BorderRadius.circular(14),
                              child: ListTile(
                                leading: const IconChip(Icons.info_outline),
                                title: const Text('Notification access needed'),
                                subtitle: const Text('Turn it on to start hiding notifications.'),
                                trailing: FilledButton(onPressed: _grantAccess, child: const Text('Grant')),
                              ),
                            ),
                          ),
                        for (final a in locked)
                          SwitchListTile(
                            key: ValueKey(a.package),
                            secondary: _icon(a.package),
                            title: Text(a.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                            value: _hidden.contains(a.package),
                            onChanged: (v) => _toggle(a.package, v),
                          ),
                      ],
                    ),
        ),
      ),
    );
  }
}
