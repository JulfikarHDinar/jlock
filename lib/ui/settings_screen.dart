import 'package:flutter/material.dart';

import '../native.dart';
import '../settings.dart';
import '../theme.dart';
import 'brand.dart';

const releasesUrl = 'https://github.com/JulfikarHDinar/jlock/releases/latest';

/// jLock settings: theme, lock screen behaviour, and about. (Hidden notifications live on the Home page.)
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final AppLifecycleListener _lifecycle;
  String _relock = 'screen_off';
  bool _patternVisible = true;
  bool _haptics = true;
  String _version = '';

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
    final prefs = await Native.getLockPrefs();
    final version = await Native.appVersion();
    if (!mounted) return;
    setState(() {
      _version = version;
      _relock = prefs.relock;
      _patternVisible = prefs.patternVisible;
      _haptics = prefs.haptics;
    });
  }

  void _setPref(String key, Object value) {
    Native.setLockPref(key, value);
    setState(() {
      switch (key) {
        case 'relock':
          _relock = value as String;
        case 'patternVisible':
          _patternVisible = value as bool;
        case 'haptics':
          _haptics = value as bool;
      }
    });
  }

  Future<void> _checkForUpdates() async {
    if (await Native.openUrl(releasesUrl) || !mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('No browser found. Visit $releasesUrl')));
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ValueListenableBuilder<Palette>(
      valueListenable: Settings.current,
      builder: (context, selected, _) => Scaffold(
        appBar: AppBar(title: const Text('Settings')),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: ListView(
              padding: EdgeInsets.only(bottom: 24 + MediaQuery.viewPaddingOf(context).bottom),
              children: [
                const SectionHeader('Theme'),
                for (final light in [false, true]) ...[
                  Padding(
                    padding: EdgeInsets.fromLTRB(32, light ? 16 : 4, 20, 6),
                    child: Text(
                      light ? 'Light' : 'Dark',
                      style: Theme.of(context).textTheme.labelMedium!
                          .copyWith(color: cs.onSurfaceVariant),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Row(
                      children: [
                        for (final p in palettes.where((p) => p.light == light))
                          Expanded(
                            child: _Swatch(
                              palette: p,
                              selected: p.id == selected.id,
                              onTap: () => Settings.setPalette(p),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
                const SectionHeader('Lock again'),
                RadioGroup<String>(
                  groupValue: _relock,
                  onChanged: (v) => _setPref('relock', v!),
                  child: const CardGroup(
                    children: [
                      RadioListTile(
                        value: 'screen_off',
                        contentPadding: EdgeInsets.fromLTRB(8, 4, 16, 4),
                        title: Text('When the screen turns off'),
                        subtitle: Text('Unlocked apps stay open until then.'),
                      ),
                      RadioListTile(
                        value: 'app_exit',
                        contentPadding: EdgeInsets.fromLTRB(8, 4, 16, 4),
                        title: Text('As soon as I leave the app'),
                        subtitle: Text('Going to the home screen or Recents locks it again. Screen off does too.'),
                      ),
                    ],
                  ),
                ),
                const SectionHeader('Lock screen'),
                CardGroup(children: [
                  SwitchListTile(
                    contentPadding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
                    secondary: IconChip(Icons.gesture, active: _patternVisible),
                    title: const Text('Show pattern path'),
                    subtitle: const Text('Turn off to hide the lines and dots while you draw.'),
                    value: _patternVisible,
                    onChanged: (v) => _setPref('patternVisible', v),
                  ),
                  SwitchListTile(
                    contentPadding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
                    secondary: IconChip(Icons.vibration, active: _haptics),
                    title: const Text('Vibrate on touch'),
                    subtitle: const Text('A light tap on each key and pattern dot.'),
                    value: _haptics,
                    onChanged: (v) => _setPref('haptics', v),
                  ),
                ]),
                const SectionHeader('About'),
                CardGroup(children: [
                  const ListTile(
                    contentPadding: EdgeInsets.fromLTRB(16, 6, 16, 6),
                    leading: BrandMark(size: 40),
                    title: Text('Private and offline'),
                    subtitle: Text('No account, no ads, no tracking, no internet.'),
                  ),
                  ListTile(
                    contentPadding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
                    leading: const IconChip(Icons.system_update_outlined),
                    title: const Text('Check for updates'),
                    subtitle: Text('You have version $_version. Opens the latest release in your browser.'),
                    trailing: const Icon(Icons.open_in_new, size: 20),
                    onTap: _checkForUpdates,
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

/// A theme preview in miniature: its ground with a lock dot and a couple of bars, like jNote's.
class _Swatch extends StatelessWidget {
  const _Swatch({required this.palette, required this.selected, required this.onTap});
  final Palette palette;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    Widget bar(Color c, double w) => Container(
          width: w, height: 5,
          decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(3)),
        );
    return Semantics(
      button: true,
      selected: selected,
      label: '${palette.name} theme',
      excludeSemantics: true,
      child: InkResponse(
        onTap: onTap,
        radius: 44,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Column(
            children: [
              Container(
                width: 84,
                height: 60,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: palette.bg,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: selected ? cs.primary : cs.outlineVariant, width: selected ? 2.5 : 1),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.lock, size: 16, color: palette.accent),
                    const SizedBox(height: 8),
                    bar(palette.muted, 40),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                palette.name,
                style: Theme.of(context).textTheme.labelMedium!
                    .copyWith(color: selected ? cs.onSurface : cs.onSurfaceVariant),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
