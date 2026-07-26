import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../features/projects/branch_timeline_models.dart';
import 'branch_timeline_tiles.dart';
import 'common.dart';

/// Eén commit-kaart in de Builds-tab commit-historie: header (korte sha/datum/commitbericht +
/// [SyncStatusBadge] voor de deployed-status van dit hele commit — dat is een eigenschap van het
/// commit, niet van een losse build-stap) en daaronder een rij per workflow met naam/gestart/klaar
/// ([BuildStepRow]).
class BuildHistoryCommitCard extends StatelessWidget {
  final BuildHistoryCommitRow row;
  const BuildHistoryCommitCard({super.key, required this.row});

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
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: const Color(0xfff1f0ec),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                row.shortSha,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ),
            const SizedBox(width: 6),
            Text(
              formatRelativeTime(row.date),
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
            const Spacer(),
            SyncStatusBadge(status: row.deployed),
          ],
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Text(
            row.message,
            style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        ),
        if (row.jobs.isNotEmpty) ...[
          const SizedBox(height: 4),
          const SubsectionLabel('Builds'),
          for (final job in row.jobs) BuildStepRow(job: job),
        ],
      ],
    ),
  );
}

/// Eén build-stap-rij: workflow-naam + gestart/klaar (relatieve tijd) + status-badge. Zelfde
/// dot-/badge-logica als [JobStatusTile], maar met start-/eindtijd erbij (die de branch-timeline
/// niet toont — daar gaat het om "wat is de status nu", hier om "wanneer liep dit").
class BuildStepRow extends StatelessWidget {
  final BranchJobStatus job;
  const BuildStepRow({super.key, required this.job});

  @override
  Widget build(BuildContext context) {
    final htmlUrl = job.htmlUrl;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(
            flex: 3,
            child: Text(
              job.workflowName,
              style: const TextStyle(fontSize: 12.5),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              formatRelativeTime(job.startedAt),
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              formatRelativeTime(job.finishedAt),
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
          ),
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
