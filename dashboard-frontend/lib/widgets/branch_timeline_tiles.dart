import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../features/projects/branch_timeline_models.dart';
import '../main.dart';
import 'common.dart';

/// Gedeelde bouwstenen voor "één branch/PR z'n build- en deploystatus", gebruikt door zowel de
/// ingebedde sectie op het Projects-scherm (`_BranchTimelineSection` in projects_screen.dart) als
/// de losse Buildstraat-pagina (`buildstraat_screen.dart`) — één plek voor styling/gedrag i.p.v.
/// twee kopieën die uit elkaar kunnen lopen.
class SyncStatusBadge extends StatelessWidget {
  final String status;
  const SyncStatusBadge({super.key, required this.status});

  @override
  Widget build(BuildContext context) => switch (status) {
    'IN_SYNC' => const StatusBadge('In sync met main', BadgeTone.good),
    'OUT_OF_SYNC' => const StatusBadge('Loopt achter op main', BadgeTone.warn),
    _ => const StatusBadge('Geen productieversie beschikbaar', BadgeTone.neutral),
  };
}

/// Eén branch/PR-kaart: header (naam/sha/datum/commit-message/PR-link) + een verticale
/// "Builds"-lijst (één regel per workflow, met naam) en, alleen voor main, een verticale
/// "Deploy"-lijst (één regel per live-component, met naam) — zie [JobStatusTile] en
/// [DeployStatusTile].
class BranchTimelineRowCard extends StatelessWidget {
  final BranchTimelineRow row;
  const BranchTimelineRowCard({super.key, required this.row});

  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(bottom: 10),
    padding: const EdgeInsets.all(10),
    decoration: BoxDecoration(
      color: const Color(0xfff8f7f5),
      borderRadius: BorderRadius.circular(8),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(
              child: Text(
                row.branchName,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (row.prNumber != null)
              InkWell(
                onTap: (row.prUrl?.isNotEmpty ?? false)
                    ? () => launchUrl(Uri.parse(row.prUrl!), mode: LaunchMode.externalApplication)
                    : null,
                child: Text(
                  'PR #${row.prNumber}',
                  style: const TextStyle(fontSize: 12, color: SfColors.blue),
                ),
              ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Wrap(
            spacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                decoration: BoxDecoration(
                  color: const Color(0xfff1f0ec),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  row.commitShortSha,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ),
              Text(
                formatRelativeTime(row.commitDate),
                style: const TextStyle(color: Colors.black54, fontSize: 12),
              ),
            ],
          ),
        ),
        Text(
          row.commitMessage,
          style: const TextStyle(fontSize: 12),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        if (row.jobs.isNotEmpty) ...[
          const SizedBox(height: 8),
          const SubsectionLabel('Builds'),
          for (final job in row.jobs) JobStatusTile(job: job),
        ],
        if (row.isMain && row.liveComponents.isNotEmpty) ...[
          const SizedBox(height: 8),
          const SubsectionLabel('Deploy'),
          for (final component in row.liveComponents) DeployStatusTile(component: component),
        ],
      ],
    ),
  );
}

class SubsectionLabel extends StatelessWidget {
  final String label;
  const SubsectionLabel(this.label, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 2),
    child: Text(
      label,
      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.black45, letterSpacing: 0.3),
    ),
  );
}

/// Eén build-regel: workflow-naam + tekstuele statusbadge (i.p.v. alleen een gekleurd bolletje —
/// dat was zonder hover niet te duiden). Het kleine bolletje ervoor blijft staan voor snel scannen
/// op kleur, maar de badge-tekst is nu de primaire manier om de status te lezen. Klik opent de
/// GitHub Actions-run (als die er is). `status == null` betekent dat er voor deze exacte commit geen
/// run van deze workflow bestaat — bewust anders dan "nog niet gestart" (zie `dotsFor` op de backend).
class JobStatusTile extends StatelessWidget {
  final BranchJobStatus job;
  const JobStatusTile({super.key, required this.job});

  @override
  Widget build(BuildContext context) {
    final htmlUrl = job.htmlUrl;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          _dot(),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              job.workflowName,
              style: const TextStyle(fontSize: 12.5),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 8),
          _badge(),
          SizedBox(
            width: 30,
            child: (htmlUrl != null && htmlUrl.isNotEmpty)
                ? IconButton(
                    icon: const Icon(Icons.open_in_new, size: 15),
                    tooltip: 'Open workflow-run',
                    padding: EdgeInsets.zero,
                    onPressed: () => launchUrl(Uri.parse(htmlUrl), mode: LaunchMode.externalApplication),
                  )
                : null,
          ),
        ],
      ),
    );
  }

  Widget _dot() {
    if (job.status == null) return const _DashedCircleDot(size: 11);
    if (job.status == 'queued' || job.status == 'in_progress') {
      return const _PulsingDot(color: SfColors.amber, size: 11);
    }
    return switch (job.conclusion) {
      'success' => const _FilledDot(color: SfColors.green, size: 11),
      'failure' || 'timed_out' || 'action_required' => const _FilledDot(color: SfColors.red, size: 11),
      _ => const _FilledDot(color: SfColors.muted, size: 11),
    };
  }

  Widget _badge() {
    if (job.status == null) return const StatusBadge('Niet getriggerd', BadgeTone.neutral);
    if (job.status == 'queued') return const StatusBadge('In wachtrij', BadgeTone.active);
    if (job.status == 'in_progress') return const StatusBadge('Bezig', BadgeTone.active);
    return switch (job.conclusion) {
      'success' => const StatusBadge('Geslaagd', BadgeTone.good),
      'failure' => const StatusBadge('Mislukt', BadgeTone.bad),
      'timed_out' => const StatusBadge('Timeout', BadgeTone.bad),
      'action_required' => const StatusBadge('Actie vereist', BadgeTone.bad),
      final conclusion? => StatusBadge(conclusion, BadgeTone.neutral),
      null => StatusBadge(job.status!, BadgeTone.neutral),
    };
  }
}

class _FilledDot extends StatelessWidget {
  final Color color;
  final double size;
  const _FilledDot({required this.color, this.size = 18});

  @override
  Widget build(BuildContext context) =>
      Container(width: size, height: size, decoration: BoxDecoration(shape: BoxShape.circle, color: color));
}

class _PulsingDot extends StatefulWidget {
  final Color color;
  final double size;
  const _PulsingDot({required this.color, this.size = 18});

  @override
  State<_PulsingDot> createState() => _PulsingDotState();
}

class _PulsingDotState extends State<_PulsingDot> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FadeTransition(
    opacity: Tween(begin: 0.4, end: 1.0).animate(_controller),
    child: _FilledDot(color: widget.color, size: widget.size),
  );
}

/// Gestippelde, holle cirkel — hand-rolled i.p.v. een extra pub-dependency (geen bestaande
/// dashed-border-package in deze app, zie pubspec.yaml).
class _DashedCircleDot extends StatelessWidget {
  final double size;
  const _DashedCircleDot({this.size = 18});

  @override
  Widget build(BuildContext context) =>
      SizedBox(width: size, height: size, child: CustomPaint(painter: _DashedCirclePainter()));
}

class _DashedCirclePainter extends CustomPainter {
  const _DashedCirclePainter();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = SfColors.faint
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2 - 1;
    const dashCount = 10;
    const gapFraction = 0.5;
    final anglePerDash = (2 * 3.141592653589793) / dashCount;
    for (var i = 0; i < dashCount; i++) {
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: radius),
        i * anglePerDash,
        anglePerDash * (1 - gapFraction),
        false,
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// Eén deploy-regel (alleen main-kaart): live-component-naam + [SyncStatusBadge] i.p.v. alleen een
/// gekleurd bolletje. Klik opent de OpenShift-console (als `consoleUrl` geconfigureerd is).
class DeployStatusTile extends StatelessWidget {
  final Map<String, dynamic> component;
  const DeployStatusTile({super.key, required this.component});

  @override
  Widget build(BuildContext context) {
    final syncStatus = text(component['syncStatus']);
    final consoleUrl = text(component['consoleUrl']);
    final label = text(component['label'], fallback: '?');
    final shortSha = text(component['shortSha'], fallback: '?');
    final color = switch (syncStatus) {
      'IN_SYNC' => SfColors.green,
      'OUT_OF_SYNC' => SfColors.amber,
      _ => SfColors.muted,
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          _FilledDot(color: color, size: 11),
          const SizedBox(width: 8),
          Expanded(
            child: Text(label, style: const TextStyle(fontSize: 12.5), overflow: TextOverflow.ellipsis),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
            decoration: BoxDecoration(
              color: const Color(0xfff1f0ec),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(shortSha, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ),
          const SizedBox(width: 8),
          SyncStatusBadge(status: syncStatus),
          SizedBox(
            width: 30,
            child: consoleUrl.isNotEmpty
                ? IconButton(
                    icon: const Icon(Icons.open_in_new, size: 15),
                    tooltip: 'Open OpenShift-console',
                    padding: EdgeInsets.zero,
                    onPressed: () =>
                        launchUrl(Uri.parse(consoleUrl), mode: LaunchMode.externalApplication),
                  )
                : null,
          ),
        ],
      ),
    );
  }
}
