import 'package:flutter/material.dart';


/// jLock's brand colours: the indigo-to-violet tile of the launcher icon (res/drawable/ic_launcher_*.xml).
const brandStart = Color(0xFF4338CA);
const brandEnd = Color(0xFF7C3AED);

/// The jLock logo — a white padlock on the indigo-to-violet tile, drawn exactly like the launcher icon.
class BrandMark extends StatelessWidget {
  const BrandMark({super.key, this.size = 56});

  final double size;

  @override
  Widget build(BuildContext context) =>
      CustomPaint(size: Size.square(size), painter: const _MarkPainter());
}

class _MarkPainter extends CustomPainter {
  const _MarkPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width;
    final tile = RRect.fromRectAndRadius(Offset.zero & size, Radius.circular(s * 0.3));
    canvas.drawRRect(
      tile,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [brandStart, brandEnd],
        ).createShader(Offset.zero & size),
    );
    // The launcher shows the middle 72 of its 108-unit canvas; draw in those units.
    canvas.scale(s / 72);
    canvas.translate(-18, -18);
    final white = Paint()..color = Colors.white;
    canvas.drawPath(
      Path()
        ..moveTo(43, 51)
        ..lineTo(43, 41)
        ..arcToPoint(const Offset(65, 41), radius: const Radius.circular(11))
        ..lineTo(65, 51),
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 7
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawRRect(RRect.fromLTRBR(36, 50, 72, 80, const Radius.circular(8)), white);
    final hole = Paint()..color = const Color(0xFF5B3BD9);
    canvas.drawCircle(const Offset(54, 62.5), 4.2, hole);
    canvas.drawRRect(RRect.fromLTRBR(51.8, 62.5, 56.2, 71.5, const Radius.circular(2.2)), hole);
  }

  @override
  bool shouldRepaint(_MarkPainter oldDelegate) => false;
}

/// A rounded tile holding a screen's own icon (PIN, pattern, password setup). [active] tints it with the accent.
class BrandBadge extends StatelessWidget {
  const BrandBadge({super.key, this.size = 56, this.icon = Icons.lock_rounded, this.active = true});

  final double size;
  final IconData icon;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: active ? cs.primaryContainer : cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(size * 0.3),
      ),
      child: Icon(icon, color: active ? cs.primary : cs.onSurfaceVariant, size: size * 0.5),
    );
  }
}

/// The rounded icon tile that leads list rows across jLock; [active] tints it with the accent.
class IconChip extends StatelessWidget {
  const IconChip(this.icon, {super.key, this.active = false});

  final IconData icon;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final light = cs.brightness == Brightness.light;
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        // A step off the card it sits on (cards are white on light themes, one step up on dark ones).
        color: active ? cs.primaryContainer : (light ? cs.surfaceContainerLow : cs.surfaceContainerHigh),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Icon(icon, size: 20, color: active ? cs.primary : cs.onSurfaceVariant),
    );
  }
}

/// Quiet label over a group of rows (Home, Settings), lined up with the rows' text.
class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key});

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(32, 22, 20, 8),
        child: Text(title,
            style: Theme.of(context).textTheme.labelLarge!.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontWeight: FontWeight.w600,
                )),
      );
}

/// Rows grouped on one rounded card, with hairlines between them — the way jLock lists settings.
class CardGroup extends StatelessWidget {
  const CardGroup({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    // From the theme (not Settings directly), so even a const group recolours when the theme changes.
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Material(
        color: cs.brightness == Brightness.light ? cs.surfaceBright : cs.surfaceContainerLow, // = theme.dart cardColor
        borderRadius: BorderRadius.circular(20),
        clipBehavior: Clip.antiAlias,
        child: Column(
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0) Divider(height: 1, thickness: 1, indent: 72, color: cs.outlineVariant.withValues(alpha: 0.6)),
              children[i],
            ],
          ],
        ),
      ),
    );
  }
}
