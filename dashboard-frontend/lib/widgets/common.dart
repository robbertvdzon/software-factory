import 'package:flutter/material.dart';

import '../app_state.dart';
import '../main.dart';

/// Statusbanner boven elk scherm: altijd zichtbaar zodra de bridge offline is
/// (§9 "Factory-status prominent").
class OfflineBanner extends StatelessWidget {
  final AppState state;
  const OfflineBanner({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    if (state.connected) return const SizedBox.shrink();
    return Container(
      width: double.infinity,
      color: SfColors.red,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          const Icon(Icons.cloud_off, color: Colors.white, size: 18),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              'Factory niet verbonden — acties en data kunnen verouderd zijn.',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

class Panel extends StatelessWidget {
  final Widget child;
  const Panel({super.key, required this.child});

  @override
  Widget build(BuildContext context) =>
      Card(child: Padding(padding: const EdgeInsets.all(16), child: child));
}

class EmptyState extends StatelessWidget {
  final String message;
  const EmptyState(this.message, {super.key});

  @override
  Widget build(BuildContext context) => Panel(
    child: Text(message, style: const TextStyle(color: Colors.black54)),
  );
}

class ErrorBanner extends StatelessWidget {
  final String message;
  const ErrorBanner(this.message, {super.key});

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(14),
    margin: const EdgeInsets.only(bottom: 12),
    decoration: BoxDecoration(
      color: SfColors.redSoft,
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(message, style: const TextStyle(color: SfColors.red)),
  );
}

class StatusBadge extends StatelessWidget {
  final String label;
  final BadgeTone tone;
  const StatusBadge(this.label, this.tone, {super.key});

  factory StatusBadge.fromPhase(String? phase) {
    final value = (phase ?? '').toLowerCase();
    if (value.contains('with-questions')) return StatusBadge(phase!, BadgeTone.warn);
    if (value.contains('rejected') || value.contains('failed') || value.contains('not-approved')) {
      return StatusBadge(phase!, BadgeTone.bad);
    }
    if (value.contains('approved') || value.contains('done') || value.endsWith('ed')) {
      return StatusBadge(phase == null || phase.isEmpty ? '-' : phase, BadgeTone.good);
    }
    if (value.endsWith('ing')) return StatusBadge(phase!, BadgeTone.active);
    return StatusBadge(phase == null || phase.isEmpty ? 'open' : phase, BadgeTone.neutral);
  }

  @override
  Widget build(BuildContext context) {
    final colors = switch (tone) {
      BadgeTone.good => (SfColors.greenSoft, SfColors.green),
      BadgeTone.bad => (SfColors.redSoft, SfColors.red),
      BadgeTone.warn => (SfColors.amberSoft, SfColors.amber),
      BadgeTone.active => (SfColors.blueSoft, SfColors.blue),
      BadgeTone.neutral => (const Color(0xfff1f0ec), SfColors.muted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: colors.$1, borderRadius: BorderRadius.circular(999)),
      child: Text(
        label,
        style: TextStyle(color: colors.$2, fontSize: 12, fontWeight: FontWeight.w700),
      ),
    );
  }
}

enum BadgeTone { good, bad, warn, active, neutral }

class SectionTitle extends StatelessWidget {
  final String title;
  const SectionTitle(this.title, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8, top: 4),
    child: Text(title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
  );
}

/// Bevestigingsdialoog voor destructieve acties (purge/delete/re-implement/factory.stop, §8 fase D).
/// Geeft `true` terug zodra de gebruiker expliciet bevestigt, anders `false`/`null`.
Future<bool> confirmDestructive(
  BuildContext context, {
  required String title,
  required String message,
  String confirmLabel = 'Bevestigen',
}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(onPressed: () => Navigator.of(dialogContext).pop(false), child: const Text('Annuleren')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: SfColors.red),
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return result ?? false;
}

/// Toont een korte snackbar-melding (succes of fout) na een actie.
void showActionResult(BuildContext context, {required bool success, required String message}) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(message),
      backgroundColor: success ? null : SfColors.red,
    ),
  );
}
