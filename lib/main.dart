import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'native.dart';
import 'settings.dart';
import 'theme.dart';
import 'ui/auth_gate.dart';
import 'ui/home_screen.dart';
import 'ui/onboarding_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  await Settings.load(); // the chosen palette must be known before the first frame
  runApp(const JLockApp());
}

class JLockApp extends StatelessWidget {
  const JLockApp({super.key});

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<Palette>(
        valueListenable: Settings.current,
        builder: (context, p, _) => MaterialApp(
          title: 'jLock',
          debugShowCheckedModeBanner: false,
          theme: buildTheme(p),
          // The gate wraps the whole navigator, so the lock covers every route (settings, app picker, …),
          // not just the home screen.
          builder: (context, child) => AnnotatedRegion(value: systemBars(p), child: AuthGate(child: child!)),
          home: const StartupRouter(),
        ),
      );
}

/// Until the setup wizard is completed (lock, permissions, apps, battery) → the wizard; then the home screen.
/// A plain themed hold covers the one-frame check so no screen flashes first.
class StartupRouter extends StatefulWidget {
  const StartupRouter({super.key});

  @override
  State<StartupRouter> createState() => _StartupRouterState();
}

class _StartupRouterState extends State<StartupRouter> {
  bool? _configured;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    final c = await Native.isOnboarded();
    if (mounted) setState(() => _configured = c);
  }

  @override
  Widget build(BuildContext context) {
    final c = _configured;
    if (c == null) return ColoredBox(color: Settings.current.value.bg);
    return c ? const HomeScreen() : const OnboardingScreen();
  }
}
