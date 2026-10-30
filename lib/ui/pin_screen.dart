import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../native.dart';
import 'brand.dart';

class PinScreen extends StatefulWidget {
  const PinScreen({super.key});

  @override
  State<PinScreen> createState() => _PinScreenState();
}

class _PinScreenState extends State<PinScreen> {
  final _pin = TextEditingController();
  final _confirm = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _pin.dispose();
    _confirm.dispose();
    super.dispose();
  }

  bool get _valid => _pin.text.length >= 4 && _pin.text == _confirm.text;

  Future<void> _save() async {
    if (!_valid || _saving) {
      setState(() => _error = _pin.text.length < 4 ? 'Use at least 4 digits.' : 'The two PINs do not match.');
      return;
    }
    setState(() => _saving = true);
    try {
      await Native.setPin(_pin.text);
      if (mounted) Navigator.pop(context, true);
    } catch (_) {
      if (mounted) setState(() => _error = "Couldn't save the PIN. Please try again.");
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Set PIN')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
            children: [
              const Center(child: BrandBadge(icon: Icons.pin_outlined)),
              const SizedBox(height: 20),
              Text('Choose a PIN', style: tt.titleLarge, textAlign: TextAlign.center),
              const SizedBox(height: 6),
              Text('At least 4 digits. jLock stores only a one-way, hardware-encrypted hash — never the PIN itself.',
                  style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant), textAlign: TextAlign.center),
              const SizedBox(height: 28),
              _field(_pin, 'New PIN'),
              const SizedBox(height: 12),
              _field(_confirm, 'Confirm PIN'),
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
              FilledButton(onPressed: _saving ? null : _save, child: const Text('Save PIN')),
            ],
          ),
        ),
      ),
    );
  }

  Widget _field(TextEditingController c, String hint) {
    final cs = Theme.of(context).colorScheme;
    return TextField(
      controller: c,
      keyboardType: TextInputType.number,
      obscureText: true,
      maxLength: 16,
      textAlign: TextAlign.center,
      style: const TextStyle(letterSpacing: 4, fontSize: 18),
      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
      onChanged: (_) => setState(() => _error = null),
      decoration: InputDecoration(
        hintText: hint,
        counterText: '',
        hintStyle: TextStyle(color: cs.onSurfaceVariant, letterSpacing: 0, fontSize: 16),
        filled: true,
        fillColor: cs.surfaceContainerLow,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
        focusedBorder:
            OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: cs.primary, width: 1.5)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      ),
    );
  }
}
