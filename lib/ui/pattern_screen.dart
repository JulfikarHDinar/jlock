import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../native.dart';
import '../settings.dart';
import '../theme.dart';
import 'brand.dart';
import 'pattern_pad.dart';

/// Step 1 of setting a pattern: draw it. A valid draw moves to the confirm screen.
class PatternScreen extends StatefulWidget {
  const PatternScreen({super.key});

  @override
  State<PatternScreen> createState() => _PatternScreenState();
}

class _PatternScreenState extends State<PatternScreen> {
  String? _error;

  Future<void> _onComplete(List<int> path) async {
    if (path.length < 4) {
      setState(() => _error = 'Connect at least 4 dots.');
      return;
    }
    final saved = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => ConfirmPatternScreen(first: path)),
    );
    if (!mounted) return;
    if (saved == true) {
      Navigator.pop(context, true); // pattern set — back out to Home
    } else {
      setState(() => _error = null); // came back to redraw
    }
  }

  @override
  Widget build(BuildContext context) => _PatternScaffold(
        appBarTitle: 'Set pattern',
        heading: 'Draw an unlock pattern',
        note: 'Connect at least 4 dots. jLock stores only a one-way, hardware-encrypted hash.',
        padKey: const ValueKey('set'),
        onComplete: _onComplete,
        error: _error,
      );
}

/// Step 2: draw the pattern again to confirm, then save it as the active method.
class ConfirmPatternScreen extends StatefulWidget {
  const ConfirmPatternScreen({super.key, required this.first});
  final List<int> first;

  @override
  State<ConfirmPatternScreen> createState() => _ConfirmPatternScreenState();
}

class _ConfirmPatternScreenState extends State<ConfirmPatternScreen> {
  String? _error;
  bool _saving = false;

  Future<void> _onComplete(List<int> path) async {
    if (_saving) return;
    if (!listEquals(widget.first, path)) {
      setState(() => _error = "Patterns didn't match. Try again.");
      return;
    }
    setState(() => _saving = true);
    try {
      await Native.setPattern(path.join('-'));
      if (mounted) Navigator.pop(context, true);
    } catch (_) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = "Couldn't save the pattern. Please try again.";
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => _PatternScaffold(
        appBarTitle: 'Confirm pattern',
        heading: 'Draw your pattern again',
        note: 'It must match the pattern you just drew.',
        padKey: ValueKey('confirm-${_error ?? ''}'), // fresh pad after a mismatch
        onComplete: _onComplete,
        error: _error,
        enabled: !_saving,
      );
}

/// Shared layout for both pattern setup steps.
class _PatternScaffold extends StatelessWidget {
  const _PatternScaffold({
    required this.appBarTitle,
    required this.heading,
    required this.note,
    required this.padKey,
    required this.onComplete,
    required this.error,
    this.enabled = true,
  });

  final String appBarTitle;
  final String heading;
  final String note;
  final Key padKey;
  final ValueChanged<List<int>> onComplete;
  final String? error;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final p = Settings.current.value;
    final tt = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: Text(appBarTitle)),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const BrandBadge(icon: Icons.pattern),
              const SizedBox(height: 20),
              Text(heading, style: tt.titleLarge),
              const SizedBox(height: 6),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Text(note, textAlign: TextAlign.center, style: tt.bodySmall!.copyWith(color: p.muted)),
              ),
              const SizedBox(height: 28),
              PatternPad(
                key: padKey,
                onComplete: onComplete,
                enabled: enabled,
                accent: p.accent,
                node: p.line,
                error: errorColor(p),
              ),
              const SizedBox(height: 16),
              SizedBox(
                height: 20,
                child: error == null
                    ? null
                    : Text(error!, style: tt.bodySmall!.copyWith(color: errorColor(p))),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
