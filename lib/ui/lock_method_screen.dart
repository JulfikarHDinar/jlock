import 'package:flutter/material.dart';

import '../native.dart';
import 'brand.dart';
import 'methods.dart';
import 'password_screen.dart';
import 'pattern_screen.dart';
import 'pin_screen.dart';

/// Manage lock methods: turn each on or off, and star one as the default shown first on the lock screen.
/// The lock screen offers the other configured methods under "More options".
class LockMethodScreen extends StatefulWidget {
  const LockMethodScreen({super.key});

  @override
  State<LockMethodScreen> createState() => _LockMethodScreenState();
}

class _LockMethodScreenState extends State<LockMethodScreen> {
  List<String> _enabled = const [];
  String _default = 'pin';
  bool _fingerprintAvailable = false;
  bool _hasSecret = false;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final m = await Native.lockMethods();
    if (!mounted) return;
    setState(() {
      _enabled = m.enabled;
      _default = m.defaultMethod;
      _fingerprintAvailable = m.fingerprintAvailable;
      _hasSecret = m.hasSecret;
      _loading = false;
    });
  }

  Future<void> _toggle(String method, bool on) async {
    if (on) {
      final ok = await _enable(method);
      if (!ok) {
        await _load(); // reflect that nothing changed
        return;
      }
    } else {
      await Native.setMethodEnabled(method, false);
    }
    await _load();
  }

  /// Returns true if the method got enabled.
  Future<bool> _enable(String method) async {
    switch (method) {
      case 'pin':
        return await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const PinScreen())) == true;
      case 'pattern':
        return await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const PatternScreen())) == true;
      case 'password':
        return await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const PasswordScreen())) == true;
      default: // fingerprint — confirm via the system prompt, then enable
        final ok = await Native.authenticate() == true;
        if (ok) await Native.setMethodEnabled(method, true);
        if (!ok && mounted) {
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text("Didn't confirm — the method wasn't turned on.")));
        }
        return ok;
    }
  }

  Future<void> _edit(String method) async {
    // Re-run setup to change a PIN/pattern/password already set.
    if (method == 'pin') {
      await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const PinScreen()));
    } else if (method == 'pattern') {
      await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const PatternScreen()));
    } else if (method == 'password') {
      await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const PasswordScreen()));
    }
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final methods = [
      'pin',
      'pattern',
      'password',
      if (_fingerprintAvailable) 'fingerprint',
    ];

    Widget row(String method) {
      final on = _enabled.contains(method);
      final isDefault = on && _default == method;
      final secretBased = method == 'pin' || method == 'pattern' || method == 'password';
      // Fingerprint needs a PIN/pattern/password fallback — can't be turned on until one exists.
      final needsFallback = method == 'fingerprint' && !on && !_hasSecret;
      return ListTile(
        contentPadding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
        enabled: !needsFallback,
        leading: IconChip(methodIcon(method), active: on),
        title: Text(methodName(method)),
        subtitle: Text(needsFallback
            ? 'Set a PIN, pattern or password first.'
            : isDefault
                ? 'Default · ${methodDesc(method)}'
                : methodDesc(method)),
        onTap: on && secretBased ? () => _edit(method) : null,
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (on)
              IconButton(
                icon: Icon(isDefault ? Icons.star_rounded : Icons.star_border_rounded,
                    color: isDefault ? cs.primary : cs.onSurfaceVariant),
                tooltip: isDefault ? 'Default method' : 'Set as default',
                onPressed: isDefault
                    ? null
                    : () async {
                        await Native.setDefaultMethod(method);
                        await _load();
                      },
              ),
            Switch(value: on, onChanged: needsFallback ? null : (v) => _toggle(method, v)),
          ],
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Lock methods')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 560),
                child: ListView(
                  padding: EdgeInsets.only(top: 8, bottom: 24 + MediaQuery.viewPaddingOf(context).bottom),
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
                      child: Text(
                        'Turn on the ways you want to unlock jLock. Star the one shown first; the lock screen '
                        'offers the others under "More options".',
                        style: Theme.of(context).textTheme.bodySmall!.copyWith(color: cs.onSurfaceVariant),
                      ),
                    ),
                    CardGroup(children: [for (final m in methods) row(m)]),
                  ],
                ),
              ),
            ),
    );
  }
}
