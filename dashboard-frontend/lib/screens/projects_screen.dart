import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../features/projects/project_models.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

class ProjectsScreen extends StatefulWidget {
  final AppState state;
  const ProjectsScreen({super.key, required this.state});

  @override
  State<ProjectsScreen> createState() => _ProjectsScreenState();
}

class _ProjectsScreenState extends State<ProjectsScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busy = false;
  var _forceRefresh = false;

  Future<void> _forceDeploy(String name) async {
    setState(() => _busy = true);
    try {
      await widget.state.api.postJson('/api/v1/projects/$name/force-deploy');
      if (!mounted) return;
      showActionResult(
        context,
        success: true,
        message: 'Deploy getriggerd voor $name.',
      );
    } catch (e) {
      if (mounted) {
        showActionResult(context, success: false, message: e.toString());
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Ververst alle projecten (niet alleen dat ene paneel): met maar een handvol projecten is dat
  /// even goedkoop als per-project verversen (de backend haalt toch al parallel op), en scheelt
  /// een aparte per-project-cache-invalidatie op de server.
  Future<void> _refreshAll() async {
    setState(() => _busy = true);
    _forceRefresh = true;
    try {
      await _dataScreenKey.currentState?.reload();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<Map<String, dynamic>> _fetch(ApiClient api) async {
    final suffix = _forceRefresh ? '?refresh=true' : '';
    _forceRefresh = false;
    final results = await Future.wait([
      api.getJson('/api/v1/projects$suffix'),
      api.getJson('/api/v1/builds$suffix'),
      api.getJson('/api/v1/downloads$suffix'),
    ]);
    return {
      'projects': results[0]['projects'],
      'buildsRepos': results[1]['repos'],
      'downloads': results[2]['downloads'],
    };
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Projects',
      fetch: _fetch,
      builder: (context, data) {
        final page = ProjectsPageData.fromJson(data);
        if (page.projects.isEmpty) {
          return const EmptyState('Geen projecten geconfigureerd.');
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final project in page.projects)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _ProjectPanel(
                  project: project,
                  builds: page.buildsByProject[project.name],
                  downloads: page.downloadsByProject[project.name] ?? const [],
                  busy: _busy,
                  onRefresh: _refreshAll,
                  onForceDeploy: () => _forceDeploy(project.name),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _ProjectPanel extends StatelessWidget {
  final ProjectSummary project;
  final Map<String, dynamic>? builds;
  final List<Map<String, dynamic>> downloads;
  final bool busy;
  final VoidCallback onRefresh;
  final VoidCallback onForceDeploy;

  const _ProjectPanel({
    required this.project,
    required this.builds,
    required this.downloads,
    required this.busy,
    required this.onRefresh,
    required this.onForceDeploy,
  });

  @override
  Widget build(BuildContext context) {
    final liveComponents = project.liveComponents;
    final runs = builds != null
        ? asList(builds!['runs'])
        : const <Map<String, dynamic>>[];
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  project.name,
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
              ),
              IconButton(
                icon: const Icon(Icons.refresh, size: 20),
                tooltip: 'Ververs projecten',
                onPressed: busy ? null : onRefresh,
              ),
              if (project.hasDeployConfig)
                FilledButton.tonal(
                  onPressed: busy ? null : onForceDeploy,
                  child: const Text('Force deploy'),
                ),
            ],
          ),
          if (project.repoUrl.isNotEmpty)
            Text(
              project.repoUrl,
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              Chip(label: Text('todo: ${project.storiesTodo}')),
              Chip(label: Text('bezig: ${project.storiesInProgress}')),
              Chip(label: Text('klaar: ${project.storiesDone}')),
              Chip(label: Text('agents: ${project.activeAgentCount}')),
              Chip(
                label: Text(
                  'kosten: \$${project.totalCostUsd.toStringAsFixed(2)}',
                ),
              ),
            ],
          ),
          if (project.prdVersion != null) ...[
            const SizedBox(height: 6),
            Builder(
              builder: (context) {
                final version = project.prdVersion!;
                return Text(
                  'Live: ${text(version['branch'])} · ${text(version['commitShort'])} (${text(version['commitDate'])})',
                  style: const TextStyle(color: Colors.black54, fontSize: 12),
                );
              },
            ),
          ],
          if (project.buildStatus != null) ...[
            const SizedBox(height: 6),
            _ProjectBuildStatusRow(buildStatus: project.buildStatus!),
          ],
          if (liveComponents.isNotEmpty) ...[
            const SizedBox(height: 6),
            for (final component in liveComponents)
              _LiveComponentRow(component: component),
          ],
          Theme(
            data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
            child: ExpansionTile(
              tilePadding: EdgeInsets.zero,
              childrenPadding: const EdgeInsets.only(bottom: 4),
              title: const Text(
                'Builds en downloads',
                style: TextStyle(fontSize: 13, color: Colors.black54),
              ),
              children: [
                if (runs.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 4),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        'Geen GitHub Actions-workflows gevonden.',
                        style: TextStyle(color: Colors.black54),
                      ),
                    ),
                  )
                else ...[
                  const _BuildsTableHeader(),
                  for (final run in runs) _WorkflowRunRow(run: run),
                ],
                const SizedBox(height: 10),
                if (downloads.isEmpty)
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      "Geen APK's gevonden.",
                      style: TextStyle(color: Colors.black54),
                    ),
                  )
                else
                  for (final download in downloads)
                    _DownloadRow(download: download),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Live-versie+uptime van één OpenShift-component (zie `LiveComponentStatus` op de backend).
class _LiveComponentRow extends StatelessWidget {
  final Map<String, dynamic> component;
  const _LiveComponentRow({required this.component});

  @override
  Widget build(BuildContext context) {
    final shortSha = text(component['shortSha'], fallback: '?');
    final uptime = formatDuration(component['uptimeSeconds']);
    return Padding(
      padding: const EdgeInsets.only(top: 2),
      child: Wrap(
        spacing: 8,
        runSpacing: 2,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          SizedBox(
            width: 72,
            child: Text(
              text(component['label']),
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
            decoration: BoxDecoration(
              color: const Color(0xfff1f0ec),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              shortSha,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
          ),
          Text(
            uptime == '-' ? 'sinds onbekend' : 'sinds $uptime',
            style: const TextStyle(color: Colors.black54, fontSize: 12),
          ),
          _SyncStatusBadge(status: text(component['syncStatus'])),
        ],
      ),
    );
  }
}

/// Leidt een leesbare app-naam af uit de release-tag (bv. `wind-latest` -> `Wind`,
/// `robberts-assistent-latest` -> `Robberts Assistent`), zodat meerdere apk's binnen hetzelfde
/// project (repo met meerdere apps) van elkaar te onderscheiden zijn.
String _appNameFromReleaseTag(String? releaseTag) {
  if (releaseTag == null || releaseTag.isEmpty) return '';
  final withoutLatest = releaseTag.replaceFirst(RegExp(r'-latest$'), '');
  final words = withoutLatest.split('-').where((w) => w.isNotEmpty);
  return words.map((w) => w[0].toUpperCase() + w.substring(1)).join(' ');
}

/// Eén `.apk`-downloadregel binnen een project-paneel (was `DownloadsScreen`, nu per project).
class _DownloadRow extends StatelessWidget {
  final Map<String, dynamic> download;
  const _DownloadRow({required this.download});

  @override
  Widget build(BuildContext context) {
    final appName = _appNameFromReleaseTag(text(download['releaseTag']));
    final details =
        '${text(download['name'])} · ${formatBytes(number(download['size']))} · ${formatTimestamp(download['createdAt'])}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          const Icon(Icons.android, size: 18, color: Colors.black54),
          const SizedBox(width: 8),
          if (appName.isNotEmpty) ...[
            Text(
              appName,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
            ),
            const Text(' · ', style: TextStyle(fontSize: 13)),
          ],
          Expanded(
            child: Text(
              details,
              style: const TextStyle(fontSize: 13),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          TextButton(
            onPressed: () => launchUrl(
              Uri.parse(text(download['downloadUrl'])),
              mode: LaunchMode.externalApplication,
            ),
            child: const Text('Download'),
          ),
        ],
      ),
    );
  }
}

/// Builds-blok per project-panel (SF-890): laatste main-build-timestamp, actieve-build-badges
/// (main/PR, of 'geen actieve build') en de in-sync/out-of-sync-badge t.o.v. de productieversie.
class _ProjectBuildStatusRow extends StatelessWidget {
  final Map<String, dynamic> buildStatus;
  const _ProjectBuildStatusRow({required this.buildStatus});

  @override
  Widget build(BuildContext context) {
    final mainActive = boolValue(buildStatus['mainBuildActive']);
    final prActive = boolValue(buildStatus['prBuildActive']);
    final lastMainBuildAt = text(buildStatus['lastMainBuildAt']);
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(
          lastMainBuildAt.isEmpty
              ? 'Laatste main-build: onbekend'
              : 'Laatste main-build: ${formatTimestamp(lastMainBuildAt)}',
          style: const TextStyle(color: Colors.black54, fontSize: 12),
        ),
        if (mainActive || prActive) ...[
          if (mainActive)
            const StatusBadge('Main-build actief', BadgeTone.active),
          if (prActive) const StatusBadge('PR-build actief', BadgeTone.active),
        ] else
          const StatusBadge('Geen actieve build', BadgeTone.neutral),
        _SyncStatusBadge(status: text(buildStatus['syncStatus'])),
      ],
    );
  }
}

class _SyncStatusBadge extends StatelessWidget {
  final String status;
  const _SyncStatusBadge({required this.status});

  @override
  Widget build(BuildContext context) => switch (status) {
    'IN_SYNC' => const StatusBadge('In sync met main', BadgeTone.good),
    'OUT_OF_SYNC' => const StatusBadge('Loopt achter op main', BadgeTone.warn),
    _ => const StatusBadge(
      'Geen productieversie beschikbaar',
      BadgeTone.neutral,
    ),
  };
}

/// Kolomtitel-rij boven de builds-tabel binnen een project-paneel (was `_BuildsTableHeader` in het
/// losse Builds-scherm); zelfde kolom-flexen als [_WorkflowRunRow].
class _BuildsTableHeader extends StatelessWidget {
  const _BuildsTableHeader();

  @override
  Widget build(BuildContext context) {
    const headerStyle = TextStyle(
      fontWeight: FontWeight.w700,
      fontSize: 12,
      color: Colors.black54,
    );
    return const Padding(
      padding: EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(flex: 3, child: Text('Workflow', style: headerStyle)),
          Expanded(flex: 2, child: Text('Resultaat', style: headerStyle)),
          Expanded(flex: 2, child: Text('Branch', style: headerStyle)),
          Expanded(flex: 2, child: Text('Event', style: headerStyle)),
          Expanded(flex: 2, child: Text('Duur', style: headerStyle)),
          SizedBox(width: 48),
        ],
      ),
    );
  }
}

class _WorkflowRunRow extends StatelessWidget {
  final Map<String, dynamic> run;
  const _WorkflowRunRow({required this.run});

  @override
  Widget build(BuildContext context) {
    final htmlUrl = text(run['htmlUrl']);
    final workflowName = text(run['workflowName']);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(
            flex: 3,
            child: Tooltip(
              message: workflowName,
              child: Text(
                workflowName,
                overflow: TextOverflow.ellipsis,
                maxLines: 2,
                softWrap: true,
              ),
            ),
          ),
          Expanded(flex: 2, child: _ConclusionBadge(run: run)),
          Expanded(
            flex: 2,
            child: Text(text(run['branch']), overflow: TextOverflow.ellipsis),
          ),
          Expanded(flex: 2, child: Text(text(run['event']))),
          Expanded(
            flex: 2,
            child: Text(formatDuration(run['durationSeconds'])),
          ),
          IconButton(
            icon: const Icon(Icons.open_in_new, size: 18),
            tooltip: 'Open',
            onPressed: htmlUrl.isEmpty
                ? null
                : () => launchUrl(
                    Uri.parse(htmlUrl),
                    mode: LaunchMode.externalApplication,
                  ),
          ),
        ],
      ),
    );
  }
}

class _ConclusionBadge extends StatelessWidget {
  final Map<String, dynamic> run;
  const _ConclusionBadge({required this.run});

  @override
  Widget build(BuildContext context) {
    final conclusion = text(run['conclusion']);
    if (conclusion.isNotEmpty) {
      return StatusBadge(conclusion, _toneForConclusion(conclusion));
    }
    final status = text(run['status'], fallback: '-');
    return StatusBadge(status, BadgeTone.active);
  }

  BadgeTone _toneForConclusion(String conclusion) => switch (conclusion) {
    'success' => BadgeTone.good,
    'failure' || 'timed_out' || 'action_required' => BadgeTone.bad,
    'cancelled' || 'skipped' || 'neutral' => BadgeTone.neutral,
    _ => BadgeTone.warn,
  };
}
