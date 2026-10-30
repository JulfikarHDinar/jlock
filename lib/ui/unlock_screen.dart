import 'dart:async';

import 'package:flutter/material.dart';

import '../native.dart';
import '../settings.dart';
import '../theme.dart';
import 'brand.dart';
import 'methods.dart';
import 'pattern_pad.dart';

/// jLock's own unlock screen (the in-app gate). Shows the default method's input, with a "More options"
/// switcher at the bottom for the other configured methods. Crypto/biometrics run natively; this only
/// collects the secret or raises the system prompt. Mirrors the native overlay that locks other apps.
class UnlockScreen extends StatefulWidget {
  const UnlockScreen({
    super.key,
    required this.pinLength,
    required this.initialWaitMs,
    required this.defaultMethod,
    required this.methods,
    required this.onUnlocked,
    this.patternVisible = true,
  });

  /// Draw the pattern path while unlocking (off = dots and lines stay hidden).
  final bool patternVisible;

  /// Length of the stored PIN, so the keypad auto-submits (0 = submit at 4+).
  final int pinLength;

  /// Any backoff wait already in force (so a relaunch mid-lockout keeps waiting).
  final int initialWaitMs;

  /// The method shown first.
  final String defaultMethod;

  /// All configured methods (for the "More options" switcher).
  final List<String> methods;

  final VoidCallback onUnlocked;

  @override
  State<UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<UnlockScreen> {
  late String _method = widget.defaultMethod;
  String _pin = '';
  final _pwController = TextEditingController();
  String? _error;
  bool _busy = false;
  bool _bioInFlight = false;
  int _waitMs = 0;
  Timer? _ticker;
  late final AppLifecycleListener _lifecycle;

  bool get _biometric => _method == 'fingerprint';
  bool get _isPassword => _method == 'password';
  bool get _locked => _waitMs > 0;

  @override
  void initState() {
    super.initState();
    if (widget.initialWaitMs > 0) _startCooldown(widget.initialWaitMs);
    // Fingerprint first: open the system prompt right away (in-display sensors light up), no tap needed.
    if (_biometric) WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted) _bioAuth(); });
    // Back in front with the gate still up (it stays up while jLock is away): open the prompt again.
    _lifecycle = AppLifecycleListener(onResume: () { if (mounted && _biometric) _bioAuth(); });
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    _ticker?.cancel();
    _pwController.dispose();
    super.dispose();
  }

  void _switchMethod(String m) {
    if (m == _method) return;
    setState(() {
      _method = m;
      _pin = '';
      _pwController.clear();
      _error = null;
    });
    if (m == 'fingerprint') _bioAuth();
  }

  void _startCooldown(int ms) {
    _ticker?.cancel();
    setState(() => _waitMs = ms);
    _ticker = Timer.periodic(const Duration(seconds: 1), (t) {
      final left = _waitMs - 1000;
      if (left <= 0) {
        t.cancel();
        if (mounted) setState(() => _waitMs = 0);
      } else if (mounted) {
        setState(() => _waitMs = left);
      }
    });
  }

  // ---- PIN ----
  void _onDigit(String d) {
    if (_locked || _busy) return;
    final target = widget.pinLength;
    if (target > 0 && _pin.length >= target) return;
    Native.haptic('key');
    setState(() {
      _pin += d;
      _error = null;
    });
    if (target > 0 ? _pin.length >= target : _pin.length >= 16) _submit();
  }

  void _onBack() {
    if (_locked || _busy || _pin.isEmpty) return;
    Native.haptic('key');
    setState(() {
      _pin = _pin.substring(0, _pin.length - 1);
      _error = null;
    });
  }

  // ---- pattern ----
  void _onPattern(List<int> path) {
    if (_locked || _busy || path.length < 2) return;
    _pin = path.join('-');
    _submit();
  }

  // ---- password (focusable gate uses a real text field + system IME) ----
  Future<void> _submitPassword() async {
    if (_busy || _locked) return;
    final text = _pwController.text;
    if (text.isEmpty) return;
    _pin = text;
    await _submit();
    if (mounted && _method == 'password') _pwController.clear();
  }

  Future<void> _submit() async {
    if (_busy || _pin.isEmpty) return;
    setState(() => _busy = true);
    try {
      final r = await Native.verifyCredential(_method, _pin);
      if (!mounted) return;
      if (r.ok) {
        widget.onUnlocked();
        return;
      }
      Native.haptic('error');
      setState(() {
        _pin = '';
        _error = r.waitMs > 0 ? null : 'Wrong ${methodName(_method).toLowerCase()}. Try again.';
      });
      if (r.waitMs > 0) _startCooldown(r.waitMs);
    } catch (_) {
      if (mounted) setState(() => _error = 'Something went wrong. Try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  // ---- biometric ----
  Future<void> _bioAuth() async {
    if (_bioInFlight) return;
    Native.haptic('key');
    setState(() {
      _bioInFlight = true;
      _error = null;
    });
    try {
      final ok = await Native.authenticate();
      if (!mounted) return;
      if (ok == true) {
        widget.onUnlocked();
        return;
      }
      final other = widget.methods.where((m) => m != 'fingerprint');
      if (ok == null && other.isNotEmpty) {
        _switchMethod(other.first); // "Use another method"
        return;
      }
      setState(() => _error = 'Not confirmed. Tap to try again.');
    } finally {
      if (mounted) setState(() => _bioInFlight = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = Settings.current.value;
    return PopScope(
      canPop: false, // the gate can't be dismissed without unlocking
      child: Scaffold(
        backgroundColor: p.bg,
        body: SafeArea(
          child: Column(
            children: [
              Expanded(
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 360),
                    child: _frame(
                      p,
                      _biometric
                          ? _biometricPad(p)
                          : _isPassword
                              ? _passwordPad(p)
                              : _secretPad(p),
                      // The fingerprint target sits low, near the thumb, like the system's.
                      padAtBottom: _biometric,
                    ),
                  ),
                ),
              ),
              _moreOptions(p),
            ],
          ),
        ),
      ),
    );
  }

  /// The same composition as the lock over other apps (native LockHeader + face): identity at the top, then the
  /// prompt and input lower down. The header never moves when the method changes. Scrolls only if the screen is too
  /// short (e.g. with the keyboard up for a password).
  Widget _frame(Palette p, Widget pad, {bool padAtBottom = false}) => LayoutBuilder(
        builder: (context, box) => SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: box.maxHeight),
            child: IntrinsicHeight(
              child: Column(
                children: [
                  // Same room above the logo as the lock over other apps (LockHeader): ~7% of the screen height.
                  SizedBox(height: (MediaQuery.sizeOf(context).height * 0.07).clamp(40.0, 88.0)),
                  _header(p),
                  const Spacer(flex: 5),
                  pad,
                  if (padAtBottom) const SizedBox(height: 20) else const Spacer(flex: 3),
                ],
              ),
            ),
          ),
        ),
      );

  Widget _header(Palette p) {
    final tt = Theme.of(context).textTheme;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const BrandMark(size: 60),
        const SizedBox(height: 14),
        Text('jLock', style: tt.titleLarge!.copyWith(fontWeight: FontWeight.w700)),
        const SizedBox(height: 6),
        Text('Unlock to continue', style: tt.bodySmall!.copyWith(color: p.muted)),
      ],
    );
  }

  /// What to do, or the lockout countdown while one is running.
  Widget _prompt(Palette p, String text) => Text(
        _locked ? 'Too many tries. Try again in ${(_waitMs / 1000).ceil()}s' : text,
        textAlign: TextAlign.center,
        style: Theme.of(context).textTheme.bodyMedium!.copyWith(color: _locked ? errorColor(p) : p.muted),
      );

  Widget _errorLine(Palette p) => SizedBox(
        height: 18,
        child: _error == null
            ? null
            : Text(_error!, style: Theme.of(context).textTheme.bodySmall!.copyWith(color: errorColor(p))),
      );

  Widget _secretPad(Palette p) {
    final isPattern = _method == 'pattern';
    final dotCount = widget.pinLength > 0 ? widget.pinLength : (_pin.length < 4 ? 4 : _pin.length);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _prompt(p, isPattern ? 'Draw your pattern' : 'Enter your PIN'),
        const SizedBox(height: 16),
        if (isPattern) ...[
          PatternPad(onComplete: _onPattern, visible: widget.patternVisible, enabled: !_locked && !_busy, accent: p.accent, node: p.line, error: errorColor(p)),
          const SizedBox(height: 10),
          _errorLine(p),
        ] else ...[
          _Dots(count: dotCount, filled: _pin.length, color: p.accent, empty: p.muted),
          const SizedBox(height: 12),
          _errorLine(p),
          const SizedBox(height: 14),
          _Keypad(palette: p, enabled: !_locked && !_busy, onDigit: _onDigit, onBack: _onBack),
        ],
      ],
    );
  }

  Widget _biometricPad(Palette p) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        InkResponse(
          onTap: _bioInFlight ? null : _bioAuth,
          radius: 64,
          child: Container(
            width: 96,
            height: 96,
            decoration: BoxDecoration(color: p.surface, shape: BoxShape.circle),
            child: Icon(Icons.fingerprint, color: p.accent, size: 46),
          ),
        ),
        const SizedBox(height: 16),
        _prompt(p, 'Tap the fingerprint to unlock'),
        const SizedBox(height: 6),
        _errorLine(p),
      ],
    );
  }

  Widget _passwordPad(Palette p) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _prompt(p, 'Enter your password'),
          const SizedBox(height: 16),
          TextField(
            controller: _pwController,
            obscureText: true,
            autofocus: true,
            enabled: !_locked && !_busy,
            autocorrect: false,
            enableSuggestions: false,
            textInputAction: TextInputAction.done,
            textAlign: TextAlign.center,
            style: TextStyle(color: p.text, letterSpacing: 2),
            onChanged: (_) {
              if (_error != null) setState(() => _error = null);
            },
            onSubmitted: (_) => _submitPassword(),
            decoration: InputDecoration(
              hintText: 'Password',
              hintStyle: TextStyle(color: p.muted, letterSpacing: 0),
              filled: true,
              fillColor: p.surface,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
              focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: p.accent, width: 1.5)),
              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            ),
          ),
          const SizedBox(height: 12),
          _errorLine(p),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: (_locked || _busy) ? null : _submitPassword,
              child: const Text('Unlock'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _moreOptions(Palette p) {
    final others = widget.methods.where((m) => m != _method).toList();
    if (others.isEmpty) return const SizedBox(height: 8);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('More options', style: Theme.of(context).textTheme.labelMedium!.copyWith(color: p.muted)),
          const SizedBox(height: 8),
          Wrap(
            alignment: WrapAlignment.center,
            spacing: 10,
            runSpacing: 8,
            children: [for (final m in others) _chip(p, m)],
          ),
        ],
      ),
    );
  }

  Widget _chip(Palette p, String method) => Material(
        color: p.surface,
        shape: StadiumBorder(side: BorderSide(color: p.line)),
        child: InkWell(
          customBorder: const StadiumBorder(),
          onTap: () => _switchMethod(method),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(methodName(method), style: Theme.of(context).textTheme.labelMedium!.copyWith(color: p.text)),
              ],
            ),
          ),
        ),
      );
}

class _Dots extends StatelessWidget {
  const _Dots({required this.count, required this.filled, required this.color, required this.empty});
  final int count;
  final int filled;
  final Color color;
  final Color empty;

  @override
  Widget build(BuildContext context) => Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (var i = 0; i < count; i++)
            Container(
              width: 12,
              height: 12,
              margin: const EdgeInsets.symmetric(horizontal: 8),
              // Hollow until typed, filled after — the same slots as the lock over other apps.
              decoration: BoxDecoration(
                color: i < filled ? color : Colors.transparent,
                shape: BoxShape.circle,
                border: i < filled ? null : Border.all(color: empty, width: 1.5),
              ),
            ),
        ],
      );
}

class _Keypad extends StatelessWidget {
  const _Keypad({required this.palette, required this.enabled, required this.onDigit, required this.onBack});
  final Palette palette;
  final bool enabled;
  final ValueChanged<String> onDigit;
  final VoidCallback onBack;

  static const _subs = {
    '2': 'ABC', '3': 'DEF', '4': 'GHI', '5': 'JKL',
    '6': 'MNO', '7': 'PQRS', '8': 'TUV', '9': 'WXYZ',
  };

  @override
  Widget build(BuildContext context) {
    Widget key(String label) {
      // Same grid as the lock over other apps: 90x62 keys, 5 px apart (300 wide). The blank cell takes a key's full
      // footprint so the 0 lines up under the 8.
      if (label.isEmpty) return const SizedBox(width: 100, height: 72);
      final isBack = label == '<';
      return Padding(
        padding: const EdgeInsets.all(5),
        child: Material(
          color: isBack ? Colors.transparent : palette.surface, // backspace is a bare icon, like the system's
          borderRadius: BorderRadius.circular(18),
          child: InkWell(
            borderRadius: BorderRadius.circular(18),
            onTap: !enabled ? null : (isBack ? onBack : () => onDigit(label)),
            child: SizedBox(
              width: 90,
              height: 62,
              child: isBack
                  ? Icon(Icons.backspace_outlined, color: palette.muted, size: 22)
                  : Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(label,
                            style: TextStyle(
                                fontSize: 26, fontWeight: FontWeight.w500, color: palette.text, height: 1.0)),
                        if (_subs[label] != null)
                          Padding(
                            padding: const EdgeInsets.only(top: 2),
                            child: Text(_subs[label]!,
                                style: TextStyle(
                                    fontSize: 9, letterSpacing: 1.4, color: palette.muted, height: 1.0)),
                          ),
                      ],
                    ),
            ),
          ),
        ),
      );
    }

    Widget row(List<String> labels) => Row(
          mainAxisSize: MainAxisSize.min,
          children: [for (final l in labels) key(l)],
        );

    return Opacity(
      opacity: enabled ? 1 : 0.5,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          row(const ['1', '2', '3']),
          row(const ['4', '5', '6']),
          row(const ['7', '8', '9']),
          row(const ['', '0', '<']),
        ],
      ),
    );
  }
}
