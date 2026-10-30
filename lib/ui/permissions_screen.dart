import 'package:flutter/material.dart';

import '../native.dart';

/// Lets the user pick how jLock detects the foreground app, and grant that engine's permissions:
///  - Accessibility: fastest, and sees apps' own floating chat heads; banking apps that refuse accessibility won't open.
///  - Usage Access: a hair slower and needs a background service, but banking apps run normally.
class PermissionsScreen extends StatefulWidget {
  const PermissionsScreen({super.key});

  @override
  State<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends State<PermissionsScreen> {
  late final AppLifecycleListener _lifecycle;
  String _engine = 'usage';
  bool _accessibility = false;
  bool _usage = false;
  bool _overlay = false;
  bool _serviceNotifHidden = false;

  bool get _usageEngine => _engine == 'usage';

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
    final results = await Future.wait([
      Native.getLockEngine(),
      Native.isAccessibilityEnabled(),
      Native.hasUsageAccess(),
      Native.canDrawOverlays(),
      Native.isServiceNotificationHidden(),
    ]);
    if (!mounted) return;
    setState(() {
      _engine = results[0] as String;
      _accessibility = results[1] as bool;
      _usage = results[2] as bool;
      _overlay = results[3] as bool;
      _serviceNotifHidden = results[4] as bool;
    });
  }

  Future<void> _setEngine(String e) async {
    setState(() => _engine = e);
    await Native.setLockEngine(e);
    if (e == 'usage') await Native.startUsageService();
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Permissions')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Text('How jLock detects apps', style: tt.titleSmall!.copyWith(color: cs.primary)),
              const SizedBox(height: 10),
              _EngineCard(
                title: 'Usage Access',
                subtitle: 'Recommended. Banking apps keep working. Runs a small background service.',
                selected: _usageEngine,
                onTap: () => _setEngine('usage'),
              ),
              const SizedBox(height: 10),
              _EngineCard(
                title: 'Accessibility',
                subtitle: 'Fastest, and also locks chat heads that apps draw themselves (like Messenger\'s). '
                    'Some banking apps won\'t open while this is on.',
                selected: !_usageEngine,
                onTap: () => _setEngine('accessibility'),
              ),
              const SizedBox(height: 24),
              Text('What this mode needs', style: tt.titleSmall!.copyWith(color: cs.primary)),
              const SizedBox(height: 10),
              if (!_usageEngine) ..._accessibilityPerms(tt, cs) else ..._usagePerms(tt, cs),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _accessibilityPerms(TextTheme tt, ColorScheme cs) => [
        _perm(
          icon: Icons.accessibility_new,
          title: 'Accessibility',
          granted: _accessibility,
          why: 'Lets jLock see which app is in front and draw your lock over it. It does not read, store, '
              'or send screen content.',
          onTap: Native.openAccessibilitySettings,
        ),
        const SizedBox(height: 16),
        _helpCard(
          tt, cs,
          title: 'Switch greyed out?',
          body: 'On a phone, Android blocks sideloaded apps from turning on Accessibility until you allow '
              'it: open jLock\'s App info, tap the ⋮ menu, and choose "Allow restricted settings".',
          button: 'Open App info',
          onTap: Native.openAppInfo,
        ),
      ];

  List<Widget> _usagePerms(TextTheme tt, ColorScheme cs) => [
        _perm(
          icon: Icons.bar_chart,
          title: 'Usage Access',
          granted: _usage,
          why: 'Lets jLock see which app is in front, without accessibility. Find jLock in the list on the '
              'next screen and turn it on.',
          onTap: Native.openUsageAccessSettings,
        ),
        const SizedBox(height: 16),
        _perm(
          icon: Icons.layers_outlined,
          title: 'Display over other apps',
          granted: _overlay,
          why: 'Lets jLock show the lock screen over a locked app.',
          onTap: Native.openOverlaySettings,
        ),
        if (!_serviceNotifHidden) ...[
          const SizedBox(height: 16),
          _helpCard(
            tt, cs,
            title: 'Hide the status notification',
            body: 'Android shows "jLock is protecting your apps" while this mode runs and doesn\'t let apps '
                'hide it themselves. Switch it off on the next screen. jLock keeps working.',
            button: 'Turn off notification',
            onTap: Native.openServiceNotificationSettings,
          ),
        ],
        if (_accessibility) ...[
          const SizedBox(height: 16),
          _helpCard(
            tt, cs,
            title: 'Turn Accessibility off',
            body: 'jLock\'s Accessibility is still on. For banking apps to open, turn it off — Usage Access '
                'does the detection now.',
            button: 'Open Accessibility',
            onTap: Native.openAccessibilitySettings,
          ),
        ],
      ];

  /// A tip with an action (restricted settings, turning accessibility off, hiding the status notification).
  Widget _helpCard(TextTheme tt, ColorScheme cs,
      {required String title, required String body, required String button, required VoidCallback onTap}) {
    return _card(
      cs,
      leading: Icon(Icons.info_outline, color: cs.onSurfaceVariant),
      title: Text(title, style: tt.titleMedium),
      body: body,
      action: OutlinedButton(onPressed: onTap, child: Text(button)),
    );
  }

  Widget _perm({
    required IconData icon,
    required String title,
    required bool granted,
    required String why,
    required VoidCallback onTap,
  }) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    return _card(
      cs,
      leading: Icon(icon, color: granted ? cs.primary : cs.onSurfaceVariant),
      title: Row(
        children: [
          Expanded(child: Text(title, style: tt.titleMedium)),
          Icon(granted ? Icons.check_circle : Icons.radio_button_unchecked,
              color: granted ? cs.primary : cs.onSurfaceVariant),
        ],
      ),
      body: why,
      action: granted ? null : FilledButton(onPressed: onTap, child: const Text('Grant')),
    );
  }

  /// Every card on this screen shares one layout: icon in a fixed column, and the title, text and button all
  /// starting at the same edge (the same as the detection-mode cards above).
  Widget _card(ColorScheme cs, {required Widget leading, required Widget title, required String body, Widget? action}) {
    final tt = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: cs.surfaceContainerLow, borderRadius: BorderRadius.circular(12)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          leading,
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                title,
                const SizedBox(height: 4),
                Text(body, style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant)),
                if (action != null) ...[const SizedBox(height: 12), action],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A selectable card for choosing the detection engine.
class _EngineCard extends StatelessWidget {
  const _EngineCard({required this.title, required this.subtitle, required this.selected, required this.onTap});
  final String title;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: selected ? cs.primaryContainer : cs.surfaceContainerLow,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                  color: selected ? cs.primary : cs.onSurfaceVariant),
              const SizedBox(width: 12),
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
            ],
          ),
        ),
      ),
    );
  }
}
