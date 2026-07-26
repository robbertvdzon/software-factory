import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../features/projects/branch_timeline_models.dart';
import '../features/projects/project_models.dart';
import '../main.dart';
import '../widgets/branch_timeline_tiles.dart';
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
      // De hele-pagina auto-herlaad op elke "changed"-SSE-push bleek vervelend hier: met panelen
      // die je zelf open- en dichtklapt (zie _ProjectPanel) herlaadde de pagina zo op elke
      // factory-poll, wat de uitklapstand liet resetten. Pull-to-refresh en de "Ververs
      // projecten"-knop per paneel blijven de manier om verse data te halen.
      autoRefreshOnChange: false,
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

/// Eén project: standaard ingeklapt tot alleen de naam ("gewoon als knop") — pas na tikken zie je
/// het volledige paneel (chips, build-/deploystatus, live-componenten, builds-en-downloads). De
/// build- en deploystatus per branch staat daarbinnen altijd meteen open (geen eigen toggle meer,
/// zie [_BranchTimelineSection]); "Builds en downloads" (de workflow-historie + apk's) blijft een
/// losse, standaard ingeklapte sectie.
class _ProjectPanel extends StatefulWidget {
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
  State<_ProjectPanel> createState() => _ProjectPanelState();
}

class _ProjectPanelState extends State<_ProjectPanel> {
  var _expanded = false;

  @override
  Widget build(BuildContext context) {
    final project = widget.project;
    final liveComponents = project.liveComponents;
    final runs = widget.builds != null ? asList(widget.builds!['runs']) : const <Map<String, dynamic>>[];
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: InkWell(
                  onTap: () => setState(() => _expanded = !_expanded),
                  child: Row(
                    children: [
                      Icon(
                        _expanded ? Icons.expand_more : Icons.chevron_right,
                        size: 20,
                        color: Colors.black54,
                      ),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          project.name,
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              if (_expanded) ...[
                IconButton(
                  icon: const Icon(Icons.refresh, size: 20),
                  tooltip: 'Ververs projecten',
                  onPressed: widget.busy ? null : widget.onRefresh,
                ),
                if (project.hasDeployConfig)
                  FilledButton.tonal(
                    onPressed: widget.busy ? null : widget.onForceDeploy,
                    child: const Text('Force deploy'),
                  ),
              ],
            ],
          ),
          if (_expanded) ...[
            if (project.repoUrl.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  project.repoUrl,
                  style: const TextStyle(color: Colors.black54, fontSize: 12),
                ),
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
            if (widget.downloads.isNotEmpty) ...[
              const SizedBox(height: 6),
              for (final download in widget.downloads)
                _ApkSyncRow(download: download),
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
                  if (widget.downloads.isEmpty)
                    const Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        "Geen APK's gevonden.",
                        style: TextStyle(color: Colors.black54),
                      ),
                    )
                  else
                    for (final download in widget.downloads)
                      _DownloadRow(download: download),
                ],
              ),
            ),
            const SizedBox(height: 6),
            _BranchTimelineSection(state: widget.state, projectName: project.name),
          ],
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
          SyncStatusBadge(status: text(component['syncStatus'])),
        ],
      ),
    );
  }
}

/// Compacte, altijd-zichtbare sync-regel per apk (app-naam + sha + [SyncStatusBadge]) — zelfde
/// gegevens als [_DownloadRow] verderop (in de standaard ingeklapte "Builds en downloads"-sectie,
/// met daar ook grootte/datum/download-knop), maar dan direct zichtbaar zodra het project-paneel
/// open is: zonder deze rij was nergens op één blik te zien of een apk nog in sync is met main,
/// tenzij je specifiek "Builds en downloads" openklapte.
class _ApkSyncRow extends StatelessWidget {
  final Map<String, dynamic> download;
  const _ApkSyncRow({required this.download});

  @override
  Widget build(BuildContext context) {
    final appName = _appNameFromReleaseTag(text(download['releaseTag']));
    final commitSha = text(download['commitSha']);
    final shortSha = commitSha.isEmpty ? null : commitSha.substring(0, commitSha.length < 7 ? commitSha.length : 7);
    return Padding(
      padding: const EdgeInsets.only(top: 2),
      child: Wrap(
        spacing: 8,
        runSpacing: 2,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          SizedBox(
            width: 96,
            child: Text(
              appName.isEmpty ? text(download['name'], fallback: '?') : appName,
              style: const TextStyle(color: Colors.black54, fontSize: 12),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (shortSha != null)
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
          SyncStatusBadge(status: text(download['syncStatus'])),
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
          SyncStatusBadge(status: text(download['syncStatus'])),
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
        SyncStatusBadge(status: text(buildStatus['syncStatus'])),
      ],
    );
  }
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

/// Build- en deploystatus per branch (main + open PR's), altijd zichtbaar zodra het project-paneel
/// is opengeklapt — geen eigen uitklap-toggle meer (dat kostte een extra tik, en de auto-refresh die
/// hier eerder op zat bleek samen met de rest van de pagina te vervelend, zie
/// `ProjectsScreen.autoRefreshOnChange`). Ververst daarom alleen nog handmatig via het
/// ververs-icoontje. Zelfde kaarten als de losse Buildstraat-pagina (`buildstraat_screen.dart`),
/// via [BranchTimelineRowCard].
class _BranchTimelineSection extends StatefulWidget {
  final AppState state;
  final String projectName;
  const _BranchTimelineSection({required this.state, required this.projectName});

  @override
  State<_BranchTimelineSection> createState() => _BranchTimelineSectionState();
}

class _BranchTimelineSectionState extends State<_BranchTimelineSection> {
  late Future<BranchTimelinePageData> _future;

  @override
  void initState() {
    super.initState();
    _future = _fetch();
  }

  Future<BranchTimelinePageData> _fetch() => widget.state.api
      .getJson('/api/v1/projects/${widget.projectName}/branch-timeline')
      .then(BranchTimelinePageData.fromJson);

  void _reload() => setState(() => _future = _fetch());

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          const Expanded(
            child: Text(
              'Build- en deploystatus per branch',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Colors.black54),
            ),
          ),
          SizedBox(
            width: 32,
            height: 32,
            child: IconButton(
              icon: const Icon(Icons.refresh, size: 16),
              tooltip: 'Ververs build- en deploystatus',
              padding: EdgeInsets.zero,
              onPressed: _reload,
            ),
          ),
        ],
      ),
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
          return Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final error in page.errors)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text(error, style: const TextStyle(color: SfColors.red, fontSize: 12)),
                  ),
                for (final row in page.rows) BranchTimelineRowCard(row: row),
              ],
            ),
          );
        },
      ),
    ],
  );
}
