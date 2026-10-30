import 'package:flutter/material.dart';

/// Display name for a lock-method id ('pin' | 'pattern' | 'password' | 'fingerprint').
String methodName(String method) => switch (method) {
      'pattern' => 'Pattern',
      'password' => 'Password',
      'fingerprint' => 'Fingerprint',
      _ => 'PIN',
    };

IconData methodIcon(String method) => switch (method) {
      'pattern' => Icons.pattern,
      'password' => Icons.password,
      'fingerprint' => Icons.fingerprint,
      _ => Icons.pin_outlined,
    };

String methodDesc(String method) => switch (method) {
      'pattern' => 'Join at least 4 dots.',
      'password' => 'Letters, numbers and symbols.',
      'fingerprint' => 'Unlock with your fingerprint.',
      _ => 'A 4+ digit code on a keypad.',
    };
