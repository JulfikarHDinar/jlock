import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../native.dart';

/// The activity log: failed unlock attempts and hidden notifications, grouped by app with a count and
/// the time of each one. Kept on the phone only (the last 500 events).
class LogScreen extends StatefulWidget {
  const LogScreen({super.key});

  @override
  State<LogScreen> createState() => _LogScreenState();
}

class _LogScreenState extends State<LogScreen> {
  late final AppLifecycleListener _lifecycle;
  List<LogEntry>? _log;
  final _iconLoads = <String, Future<Uint8List?>>{};

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onResume: _load); // new events arrive while jLock is in the background
    _load();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final log = await Native.getLog();
    if (mounted) setState(() => _log = log);
  }

  Future<void> _clear() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Clear the log?'),
        content: const Text('All failed-unlock and hidden-notification entries will be removed.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Clear')),
        ],
      ),
    );
    if (ok != true) return;
    await Native.clearLog();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final log = _log;
    final fails = log?.where((e) => e.type == 'fail').toList() ?? const <LogEntry>[];
    final notifs = log?.where((e) => e.type == 'notif').toList() ?? const <LogEntry>[];
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Activity log'),
          actions: [
            if (log != null && log.isNotEmpty)
              IconButton(icon: const Icon(Icons.delete_outline), tooltip: 'Clear log', onPressed: _clear),
          ],
          bottom: TabBar(tabs: [
            Tab(text: 'Failed unlocks · ${fails.length}'),
            Tab(text: 'Hidden alerts · ${notifs.length}'),
          ]),
        ),
        body: log == null
            ? const Center(child: CircularProgressIndicator())
            : TabBarView(children: [
                _list(fails, 'No failed unlocks.', 'failed attempt'),
                _list(notifs, 'No hidden notifications.', 'notification'),
              ]),
      ),
    );
  }

  Widget _list(List<LogEntry> entries, String empty, String noun) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    if (entries.isEmpty) {
      return Center(child: Text(empty, style: tt.bodyMedium!.copyWith(color: cs.onSurfaceVariant)));
    }
    // Group by app, newest first within each app; apps ordered by their latest event.
    final byApp = <String, List<LogEntry>>{};
    for (final e in entries.reversed) {
      byApp.putIfAbsent(e.package, () => []).add(e);
    }
    final apps = byApp.values.toList();
    return ListView.builder(
      padding: EdgeInsets.only(bottom: 16 + MediaQuery.viewPaddingOf(context).bottom),
      itemCount: apps.length,
      itemBuilder: (_, i) {
        final list = apps[i];
        final n = list.length;
        return ExpansionTile(
          key: PageStorageKey('${list.first.type}-${list.first.package}'),
          leading: _icon(list.first.package),
          title: Text(list.first.label, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text('$n $noun${n == 1 ? '' : 's'} · last ${_when(list.first.time)}'),
          shape: const Border(),
          childrenPadding: const EdgeInsets.only(left: 72, right: 20, bottom: 8),
          children: [
            for (final e in list.take(100))
              Align(
                alignment: Alignment.centerLeft,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(_when(e.time), style: tt.bodyMedium!.copyWith(color: cs.onSurfaceVariant)),
                ),
              ),
            if (n > 100)
              Align(
                alignment: Alignment.centerLeft,
                child: Text('…and ${n - 100} older', style: tt.bodySmall!.copyWith(color: cs.onSurfaceVariant)),
              ),
          ],
        );
      },
    );
  }

  Widget _icon(String pkg) => FutureBuilder<Uint8List?>(
        future: _iconLoads.putIfAbsent(pkg, () => Native.appIcon(pkg)),
        builder: (_, s) => s.data == null
            ? const SizedBox.square(dimension: 40)
            : Image.memory(s.data!, width: 40, height: 40, gaplessPlayback: true),
      );
}

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// "Today 14:05", "Yesterday 09:12", "3 Oct 18:40" (year added when it isn't this year).
String _when(DateTime t) {
  final now = DateTime.now();
  String two(int v) => v.toString().padLeft(2, '0');
  final hm = '${two(t.hour)}:${two(t.minute)}';
  final day = DateTime(t.year, t.month, t.day);
  final today = DateTime(now.year, now.month, now.day);
  if (day == today) return 'Today $hm';
  if (day == today.subtract(const Duration(days: 1))) return 'Yesterday $hm';
  return '${t.day} ${_months[t.month - 1]}${t.year == now.year ? '' : ' ${t.year}'} $hm';
}
