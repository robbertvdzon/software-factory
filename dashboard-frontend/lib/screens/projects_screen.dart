import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../features/projects/branch_timeline_models.dart';
import '../features/projects/project_models.dart';
import '../main.dart';
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
                  state: widget.state,
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
  final AppState state;
  final ProjectSummary project;
  final Map<String, dynamic>? builds;
  final List<Map<String, dynamic>> downloads;
  final bool busy;
  final VoidCallback onRefresh;
  final VoidCallback onForceDeploy;

  const _ProjectPanel({
    required this.state,
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
          _BranchTimelineSection(state: state, projectName: project.name),
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
/// Toont sinds SF-1213-story-3 dezelfde sync-badge als `_LiveComponentRow`/`_ProjectBuildStatusRow`
/// (`download['syncStatus']`, zie `DownloadInfo.syncStatus` op de backend): vergelijkt de commit
/// waarop de release is gebaseerd met de laatste main-build-sha. `UNAVAILABLE` (geen APK-commit of
/// geen main-build-referentie) toont dezelfde neutrale badge als elders — geen foutmelding.
class _DownloadRow extends StatelessWidget {
  final Map<String, dynamic> download;
  const _DownloadRow({required this.download});

  @override
  Widget build(BuildContext context) {
    final appName = _appNameFromReleaseTag(text(download['releaseTag']));
    final details =
        '${text(download['name'])} · ${formatBytes(number(download['size']))} · ${formatTimestamp(download['createdAt'])}';
    // Wrap i.p.v. Row (zelfde recept als _LiveComponentRow): de sync-badge kan best lang zijn
    // ("Geen productieversie beschikbaar"), en samen met een lange appnaam paste dat niet altijd op
    // één regel binnen het panel — Wrap laat het dan naar een volgende regel vallen i.p.v. een harde
    // RenderFlex-overflow.
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Wrap(
        spacing: 8,
        runSpacing: 2,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
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
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 320),
                child: Text(
                  details,
                  style: const TextStyle(fontSize: 13),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          _SyncStatusBadge(status: text(download['syncStatus'])),
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

/// Build- en deploystatus per branch (main + open PR's): per branch een lijst met
/// build-regels (één per workflow) en, alleen voor main, een lijst met deploy-regels (één per
/// OpenShift-live-component) — elk met naam + tekstuele status, zie [_JobStatusTile] en
/// [_DeployStatusTile]. Lazy: haalt pas iets op zodra uitgeklapt — de backend doet hiervoor extra
/// GitHub-calls per branch, zie `DashboardQueryService.branchTimelineFor`.
///
/// Ververst zichzelf automatisch op [AppState.changedTick] zodra 'ie een keer is opengeklapt (zelfde
/// signaal als [DataScreen]) — daarvóór (SF-1274) bleef de eerste snapshot voor altijd hangen, ook
/// als de build inmiddels was afgerond, want er was helemaal geen herlaad-pad na de eerste fetch.
/// Plus een expliciete ververs-knop voor een direct verse blik zonder op de volgende poll te wachten.
class _BranchTimelineSection extends StatefulWidget {
  final AppState state;
  final String projectName;
  const _BranchTimelineSection({required this.state, required this.projectName});

  @override
  State<_BranchTimelineSection> createState() => _BranchTimelineSectionState();
}

class _BranchTimelineSectionState extends State<_BranchTimelineSection> {
  Future<BranchTimelinePageData>? _future;
  bool _expanded = false;
  int _lastTick = -1;

  @override
  void initState() {
    super.initState();
    widget.state.addListener(_onStateChanged);
  }

  @override
  void dispose() {
    widget.state.removeListener(_onStateChanged);
    super.dispose();
  }

  void _onStateChanged() {
    if (_expanded && widget.state.changedTick != _lastTick) _load();
  }

  void _load() {
    _lastTick = widget.state.changedTick;
    setState(() {
      _future = widget.state.api
          .getJson('/api/v1/projects/${widget.projectName}/branch-timeline')
          .then(BranchTimelinePageData.fromJson);
    });
  }

  @override
  Widget build(BuildContext context) => Theme(
    data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
    child: ExpansionTile(
      tilePadding: EdgeInsets.zero,
      childrenPadding: const EdgeInsets.only(bottom: 4),
      title: Row(
        children: [
          const Expanded(
            child: Text(
              'Build- en deploystatus per branch',
              style: TextStyle(fontSize: 13, color: Colors.black54),
            ),
          ),
          if (_expanded)
            SizedBox(
              width: 32,
              height: 32,
              child: IconButton(
                icon: const Icon(Icons.refresh, size: 16),
                tooltip: 'Ververs build- en deploystatus',
                padding: EdgeInsets.zero,
                onPressed: _load,
              ),
            ),
        ],
      ),
      onExpansionChanged: (expanded) {
        _expanded = expanded;
        if (expanded && _future == null) _load();
      },
      children: [
        if (_future == null)
          const SizedBox.shrink()
        else
          FutureBuilder<BranchTimelinePageData>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: LinearProgressIndicator(),
                );
              }
              if (snapshot.hasError) {
                return ErrorBanner('${snapshot.error}');
              }
              final page = snapshot.data!;
              if (page.rows.isEmpty) {
                return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 4),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Geen branch-data gevonden.',
                      style: TextStyle(color: Colors.black54),
                    ),
                  ),
                );
              }
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final error in page.errors)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Text(error, style: const TextStyle(color: SfColors.red, fontSize: 12)),
                    ),
                  for (final row in page.rows) _BranchTimelineRowWidget(row: row),
                ],
              );
            },
          ),
      ],
    ),
  );
}

/// Eén branch/PR-kaart: header (naam/sha/datum/commit-message/PR-link) + een verticale
/// "Builds"-lijst (één regel per workflow, met naam) en, alleen voor main, een verticale
/// "Deploy"-lijst (één regel per live-component, met naam) — zie [_JobStatusTile] en
/// [_DeployStatusTile]. Was een horizontale rij losse bolletjes zonder namen (niet te duiden zonder
/// per bolletje te hoveren); dit kost meer verticale ruimte maar is in één oogopslag leesbaar.
class _BranchTimelineRowWidget extends StatelessWidget {
  final BranchTimelineRow row;
  const _BranchTimelineRowWidget({required this.row});

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
          const _SubsectionLabel('Builds'),
          for (final job in row.jobs) _JobStatusTile(job: job),
        ],
        if (row.isMain && row.liveComponents.isNotEmpty) ...[
          const SizedBox(height: 8),
          const _SubsectionLabel('Deploy'),
          for (final component in row.liveComponents) _DeployStatusTile(component: component),
        ],
      ],
    ),
  );
}

class _SubsectionLabel extends StatelessWidget {
  final String label;
  const _SubsectionLabel(this.label);

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
class _JobStatusTile extends StatelessWidget {
  final BranchJobStatus job;
  const _JobStatusTile({required this.job});

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

/// Eén deploy-regel (alleen main-kaart): live-component-naam + [_SyncStatusBadge] (zelfde tekstuele
/// badge als elders op deze pagina, bv. [_ProjectBuildStatusRow]) i.p.v. alleen een gekleurd bolletje.
/// Klik opent de OpenShift-console (als [LiveComponentConfig.consoleUrl] geconfigureerd is).
class _DeployStatusTile extends StatelessWidget {
  final Map<String, dynamic> component;
  const _DeployStatusTile({required this.component});

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
          _SyncStatusBadge(status: syncStatus),
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
