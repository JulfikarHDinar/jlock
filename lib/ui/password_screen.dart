import 'package:flutter/material.dart';

import '../native.dart';
import 'brand.dart';

/// Set (and confirm) jLock's own alphanumeric password. Like [PinScreen] but no digits-only filter.
class PasswordScreen extends StatefulWidget {
  const PasswordScreen({super.key});

  @override
  State<PasswordScreen> createState() => _PasswordScreenState();
}

class _PasswordScreenState extends State<PasswordScreen> {
  final _pw = TextEditingController();
  final _confirm = TextEditingController();
  bool _saving = false;
  bool _obscure = true;
  String? _error;

  @override
  void dispose() {
    _pw.dispose();
    _confirm.dispose();
    super.dispose();
  }

  bool get _valid => _pw.text.length >= 4 && _pw.text == _confirm.text;

  Future<void> _save() async {
    if (!_valid || _saving) {
      setState(() =>
          _error = _pw.text.length < 4 ? 'Use at least 4 characters.' : 'The two passwords do not match.');
      return;
    }
    setState(() => _saving = true);
    try {
      await Native.setPassword(_pw.text);
      if (mounted) Navigator.pop(context, true);
    } catch (_) {
      if (mounted) setState(() => _error = "Couldn't save the password. Please try again.");
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Set password')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
            children: [
              const Center(child: BrandBadge(icon: Icons.password_outlined)),
              const SizedBox(height: 20),
              Text('Choose a password', style: tt.titleLarge, textAlign: TextAlign.center),
              const SizedBox(height: 6),
              Text(
                  'At least 4 characters. jLock stores only a one-way, hardware-encrypted hash — never the password itself.',
                  style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant), textAlign: TextAlign.center),
              const SizedBox(height: 28),
              _field(_pw, 'New password', showToggle: true),
              const SizedBox(height: 12),
              _field(_confirm, 'Confirm password'),
              SizedBox(
                height: 30,
                child: _error == null
                    ? null
                    : Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(_error!,
                            style: tt.bodySmall!.copyWith(color: cs.error), textAlign: TextAlign.center),
                      ),
              ),
              const SizedBox(height: 16),
              FilledButton(onPressed: _saving ? null : _save, child: const Text('Save password')),
            ],
          ),
        ),
      ),
    );
  }

  Widget _field(TextEditingController c, String hint, {bool showToggle = false}) {
    final cs = Theme.of(context).colorScheme;
    return TextField(
      controller: c,
      obscureText: _obscure,
      autocorrect: false,
      enableSuggestions: false,
      maxLength: 64,
      onChanged: (_) => setState(() => _error = null),
      decoration: InputDecoration(
        hintText: hint,
        counterText: '',
        hintStyle: TextStyle(color: cs.onSurfaceVariant),
        filled: true,
        fillColor: cs.surfaceContainerLow,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
        focusedBorder:
            OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: cs.primary, width: 1.5)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
        suffixIcon: showToggle
            ? IconButton(
                icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                tooltip: _obscure ? 'Show' : 'Hide',
                onPressed: () => setState(() => _obscure = !_obscure),
              )
            : null,
      ),
    );
  }
}
