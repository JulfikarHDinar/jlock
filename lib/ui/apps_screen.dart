import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../native.dart';

class AppsScreen extends StatefulWidget {
  const AppsScreen({super.key});

  @override
  State<AppsScreen> createState() => _AppsScreenState();
}

class _AppsScreenState extends State<AppsScreen> {
  List<AppInfo>? _apps;
  final Set<String> _selected = {};
  String _query = '';
  // Icons load lazily, one per visible row; the sync cache stops a placeholder flash when a row scrolls back.
  final _iconCache = <String, Uint8List?>{};
  final _iconLoads = <String, Future<Uint8List?>>{};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final apps = await Native.listApps();
    final locked = await Native.getLockedApps();
    if (!mounted) return;
    setState(() {
      _selected
        ..clear()
        ..addAll(locked);
      // Locked apps first so it's clear at a glance what's protected; the order is fixed on open.
      _apps = [
        ...apps.where((a) => _selected.contains(a.package)),
        ...apps.where((a) => !_selected.contains(a.package)),
      ];
    });
  }

  void _toggle(String pkg, bool on) {
    setState(() => on ? _selected.add(pkg) : _selected.remove(pkg));
    Native.setLockedApps(_selected.toList()); // saved right away — nothing to lose by backing out
  }

  Widget _icon(String pkg) => FutureBuilder<Uint8List?>(
        future: _iconLoads.putIfAbsent(pkg, () => Native.appIcon(pkg).then((b) => _iconCache[pkg] = b)),
        initialData: _iconCache[pkg],
        builder: (_, s) => s.data == null
            ? const SizedBox.square(dimension: 40)
            : Image.memory(s.data!, width: 40, height: 40, gaplessPlayback: true),
      );

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final apps = _apps;
    final filtered = apps == null
        ? const <AppInfo>[]
        : apps.where((a) => a.label.toLowerCase().contains(_query) || a.package.contains(_query)).toList();
    return Scaffold(
      appBar: AppBar(title: Text(_selected.isEmpty ? 'Locked apps' : 'Locked apps · ${_selected.length}')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: TextField(
                  onChanged: (v) => setState(() => _query = v.toLowerCase()),
                  decoration: InputDecoration(
                    hintText: 'Search apps',
                    prefixIcon: const Icon(Icons.search),
                    filled: true,
                    fillColor: cs.surfaceContainerLow,
                    border: const OutlineInputBorder(
                        borderRadius: BorderRadius.all(Radius.circular(24)), borderSide: BorderSide.none),
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
              ),
              Expanded(
                child: apps == null
                    ? const Center(child: CircularProgressIndicator())
                    : filtered.isEmpty
                        ? Center(
                            child: Text('No apps match your search.',
                                style: Theme.of(context).textTheme.bodySmall!.copyWith(color: cs.onSurfaceVariant)),
                          )
                        : ListView.builder(
                            padding: EdgeInsets.only(bottom: 16 + MediaQuery.viewPaddingOf(context).bottom),
                            itemCount: filtered.length,
                            itemBuilder: (_, i) {
                              final a = filtered[i];
                              return SwitchListTile(
                                key: ValueKey(a.package), // a fresh row per app, so icons never flash across rows
                                value: _selected.contains(a.package),
                                secondary: _icon(a.package),
                                title: Text(a.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                                onChanged: (v) => _toggle(a.package, v),
                              );
                            },
                          ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
