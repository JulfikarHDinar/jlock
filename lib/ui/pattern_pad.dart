import 'package:flutter/material.dart';

import '../native.dart';

/// A 3×3 pattern pad, used both to set a pattern and to unlock. The drawn path is reported as an ordered
/// list of node indices 0..8 (row-major). Dragging between two aligned nodes auto-includes the node
/// between them, matching the familiar Android pattern behaviour, so setting and unlocking stay consistent.
class PatternPad extends StatefulWidget {
  const PatternPad({
    super.key,
    required this.onComplete,
    required this.accent,
    required this.node,
    required this.error,
    this.enabled = true,
    this.visible = true,
    this.size = 280,
  });

  /// false = nothing is drawn while the pattern is entered (it still works normally).
  final bool visible;

  /// Called on finger-up with the drawn path (empty if nothing was drawn).
  final ValueChanged<List<int>> onComplete;
  final Color accent;
  final Color node; // unselected node + idle lines
  final Color error; // tint when the last attempt was wrong
  final bool enabled;
  final double size;

  @override
  State<PatternPad> createState() => _PatternPadState();
}

class _PatternPadState extends State<PatternPad> {
  final List<int> _path = [];
  Offset? _cursor;
  bool _showError = false;

  Offset _center(int i, double s) {
    final cell = s / 3;
    return Offset(cell * (i % 3) + cell / 2, cell * (i ~/ 3) + cell / 2);
  }

  void _add(Offset p, double s) {
    final cell = s / 3;
    for (var i = 0; i < 9; i++) {
      if (_path.contains(i)) continue;
      if ((p - _center(i, s)).distance < cell * 0.3) {
        if (_path.isNotEmpty) _maybeAddMidpoint(_path.last, i);
        _path.add(i);
        Native.haptic('tick');
        break;
      }
    }
  }

  /// If the segment a→b jumps straight over the node between them, include that node first.
  void _maybeAddMidpoint(int a, int b) {
    final ar = a ~/ 3, ac = a % 3, br = b ~/ 3, bc = b % 3;
    if ((ar + br) % 2 == 0 && (ac + bc) % 2 == 0) {
      final m = ((ar + br) ~/ 2) * 3 + (ac + bc) ~/ 2;
      if (m != a && m != b && !_path.contains(m)) _path.add(m);
    }
  }

  void _start(Offset p, double s) {
    _path.clear();
    _showError = false;
    _add(p, s);
    setState(() => _cursor = p);
  }

  void _end() {
    widget.onComplete(List.unmodifiable(_path));
    setState(() => _cursor = null);
  }

  /// Parent calls this (via a GlobalKey) to flash the last path red after a wrong attempt.
  void markError() => setState(() => _showError = true);

  @override
  Widget build(BuildContext context) {
    final s = widget.size;
    return SizedBox(
      width: s,
      height: s,
      child: GestureDetector(
        onPanStart: widget.enabled ? (d) => _start(d.localPosition, s) : null,
        onPanUpdate: widget.enabled
            ? (d) {
                _add(d.localPosition, s);
                setState(() => _cursor = d.localPosition);
              }
            : null,
        onPanEnd: widget.enabled ? (_) => _end() : null,
        child: CustomPaint(
          size: Size(s, s),
          painter: _PatternPainter(
            path: widget.visible ? _path : const [],
            cursor: widget.visible ? _cursor : null,
            color: _showError ? widget.error : widget.accent,
            node: widget.node,
            center: (i) => _center(i, s),
          ),
        ),
      ),
    );
  }
}

class _PatternPainter extends CustomPainter {
  _PatternPainter({
    required this.path,
    required this.cursor,
    required this.color,
    required this.node,
    required this.center,
  });

  final List<int> path;
  final Offset? cursor;
  final Color color;
  final Color node;
  final Offset Function(int) center;

  @override
  void paint(Canvas canvas, Size size) {
    final dot = size.width / 3 * 0.12;
    final line = Paint()
      ..color = color
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;

    // connect the path, then trail to the finger
    for (var i = 0; i + 1 < path.length; i++) {
      canvas.drawLine(center(path[i]), center(path[i + 1]), line);
    }
    if (path.isNotEmpty && cursor != null) {
      canvas.drawLine(center(path.last), cursor!, line);
    }

    for (var i = 0; i < 9; i++) {
      final on = path.contains(i);
      canvas.drawCircle(center(i), dot, Paint()..color = on ? color : node);
      if (on) {
        canvas.drawCircle(
          center(i),
          dot * 2.1,
          Paint()
            ..color = color
            ..style = PaintingStyle.stroke
            ..strokeWidth = 2,
        );
      }
    }
  }

  @override
  bool shouldRepaint(_PatternPainter old) =>
      old.path.length != path.length || old.cursor != cursor || old.color != color;
}
