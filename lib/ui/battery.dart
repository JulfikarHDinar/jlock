import 'package:flutter/material.dart';

import '../native.dart';

/// Explains why jLock needs battery restrictions lifted and opens the right system screens. Shared by
/// the setup wizard and the Home checklist.
Future<void> showBatteryHelp(BuildContext context) => showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove battery restrictions'),
        content: const Text(
          'Phones put background apps to sleep to save battery. If that happens to jLock, your apps can '
          'open without the lock.\n\n'
          '1. Tap "Allow" below and confirm.\n'
          '2. On Samsung, also open App info › Battery and choose "Unrestricted".',
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              Native.openAppInfo();
            },
            child: const Text('App info'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              Native.requestIgnoreBatteryOptimizations();
            },
            child: const Text('Allow'),
          ),
        ],
      ),
    );
