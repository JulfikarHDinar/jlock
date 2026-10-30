import 'package:flutter/material.dart';

import '../native.dart';
import '../settings.dart';
import 'unlock_screen.dart';

/// Protects jLock itself. While a PIN is set, opening jLock — or returning to it from the background —
/// requires the PIN before the PIN, the locked-app list or any setting can be seen or changed. This is
/// an in-app gate (not the native overlay, which skips jLock's own package), so it works during setup
/// before Accessibility/overlay permissions are granted.
///
/// If no PIN is set yet, the gate is transparent so first-run setup is reachable.
class AuthGate extends StatefulWidget {
  const AuthGate({super.key, required this.child});
  final Widget child;

  @override
  State<AuthGate> createState() => _AuthGateState();
}

enum _Phase { checking, locked, open }

class _AuthGateState extends State<AuthGate> with WidgetsBindingObserver {
  _Phase _phase = _Phase.checking;
  bool _configured = false;
  ({int waitMs, int pinLength, String defaultMethod, List<String> methods, bool patternVisible}) _gate =
      (waitMs: 0, pinLength: 0, defaultMethod: 'pin', methods: const [], patternVisible: true);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _relock();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
        // Backgrounded: blank now (so the Recents snapshot shows no content) and re-lock if a PIN is set.
        if (_configured && _phase == _Phase.open && mounted) setState(() => _phase = _Phase.checking);
        _relock();
      case AppLifecycleState.resumed:
        // Keep the configured flag fresh so the next background blanks correctly even right after setup.
        _syncConfigured();
      case AppLifecycleState.inactive:
      case AppLifecycleState.detached:
        break;
    }
  }

  /// Lock if a PIN exists, otherwise fall through to setup. Safe to call on launch and on background.
  Future<void> _relock() async {
    final configured = await Native.isConfigured();
    if (!mounted) return;
    _configured = configured;
    if (!configured) {
      if (_phase != _Phase.open) setState(() => _phase = _Phase.open);
      return;
    }
    final gate = await Native.authState();
    if (!mounted) return;
    setState(() {
      _gate = gate;
      _phase = _Phase.locked;
    });
  }

  Future<void> _syncConfigured() async {
    final configured = await Native.isConfigured();
    if (mounted) _configured = configured;
  }

  @override
  Widget build(BuildContext context) {
    // Keep the app (the whole navigator) mounted underneath and draw the lock on top, so unlocking
    // returns to exactly where the user was without rebuilding the app, and the lock covers every route.
    return PopScope(
      canPop: _phase == _Phase.open, // while locked, Back can't slip past the lock
      child: Stack(
        children: [
          // While locked, the app underneath is hidden from accessibility services too (they could read it out).
          ExcludeSemantics(excluding: _phase != _Phase.open, child: widget.child),
          if (_phase == _Phase.locked)
            Positioned.fill(
              child: UnlockScreen(
                pinLength: _gate.pinLength,
                initialWaitMs: _gate.waitMs,
                defaultMethod: _gate.defaultMethod,
                methods: _gate.methods,
                patternVisible: _gate.patternVisible,
                onUnlocked: () => setState(() => _phase = _Phase.open),
              ),
            )
          else if (_phase == _Phase.checking)
            // A plain themed hold while the decision resolves — no app content shown.
            Positioned.fill(child: ColoredBox(color: Settings.current.value.bg)),
        ],
      ),
    );
  }
}
