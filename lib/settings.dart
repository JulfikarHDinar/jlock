import 'package:flutter/foundation.dart';

import 'native.dart';
import 'theme.dart';

/// The chosen palette, applied to the app UI and mirrored to the native lock screen
/// so both always match. Native storage is the source of truth.
class Settings {
  Settings._();

  static final ValueNotifier<Palette> current = ValueNotifier(palettes.first);

  static Future<void> load() async {
    try {
      current.value = Palette.byId(await Native.getThemeId());
    } catch (_) {
      // first run or channel not ready: keep the default
    }
  }

  static Future<void> setPalette(Palette p) async {
    current.value = p;
    await Native.setTheme({
      'id': p.id,
      'light': p.light,
      'bg': p.bg.toARGB32(),
      'surface': p.surface.toARGB32(),
      'raised': p.raised.toARGB32(),
      'line': p.line.toARGB32(),
      'text': p.text.toARGB32(),
      'muted': p.muted.toARGB32(),
      'accent': p.accent.toARGB32(),
      'onAccent': p.onAccent.toARGB32(),
    });
  }
}
