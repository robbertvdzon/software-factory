import 'package:flutter/material.dart';

import '../app_state.dart';
import '../features/projects/branch_timeline_models.dart';
import '../features/projects/project_models.dart';
import '../widgets/build_history_tiles.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Builds-tab: project kiezen, dan een branch (main of een open PR), dan de commit-historie van
/// die branch (standaard 4 commits, "Meer"-knop laadt er telkens 4 bij) met per commit de
/// build-stappen (naam/gestart/klaar) en de deployed-status van dat exacte commit.
///
/// Branch-keuzes komen uit de bestaande `/branch-timeline`-response (main + open PR's) — geen apart
/// branch-lijst-endpoint nodig. Ververst alleen handmatig (geen SSE-auto-reload): dat zou anders de
/// project-/branch-keuze en de opgebouwde commit-lijst steeds resetten.
class BuildsScreen extends StatefulWidget {
  final AppState state;
  const BuildsScreen({super.key, required this.state});

  @override
  State<BuildsScreen> createState() => _BuildsScreenState();
}

class _BuildsScreenState extends State<BuildsScreen> {
  static const _perPage = 4;

  String? _selectedProject;
  List<BranchTimelineRow> _branchOptions = [];
  bool _loadingBranches = false;
  String? _branchError;

  String? _selectedBranch;
  List<BuildHistoryCommitRow> _commits = [];
  bool _hasMore = false;
  int _page = 1;
  bool _loadingCommits = false;
  String? _commitsError;

  @override
  void initState() {
    super.initState();
    _autoSelectMostRecent();
  }

  /// Zet project/branch bij het openen alvast op waar de elke-minuut-ververste "laatste
  /// commits"-snapshot (backend: `RecentCommitsPoller`) de meest recente commit zag — zodat je
  /// niet eerst zelf hoeft te zoeken welk project/branch net iets nieuws heeft. Faalt de call, of
  /// heeft de poller nog geen snapshot (bv. vlak na een backend-herstart) → gewoon stil niks doen,
  /// de gebruiker kiest dan zelf. Kiest de gebruiker ondertussen zelf al iets, dan wint dat: de
  /// check op `_selectedProject == null` vlak vóór het toepassen voorkomt dat dit een handmatige
  /// keuze overschrijft die tijdens het ophalen werd gemaakt.
  Future<void> _autoSelectMostRecent() async {
    try {
      final data = await widget.state.api.getJson('/api/v1/projects/recent-commits');
      final page = RecentCommitsPageData.fromJson(data);
      final project = page.mostRecentProject;
      final branch = page.mostRecentBranch;
      if (project == null || branch == null) return;
      if (!mounted || _selectedProject != null) return;
      await _selectProject(project);
      if (!mounted || _selectedBranch != null) return;
      await _selectBranch(branch);
    } catch (_) {
      // Stille no-op: dit is een gemaks-default, geen essentiële data.
    }
  }

  Future<void> _selectProject(String? project) async {
    setState(() {
      _selectedProject = project;
      _branchOptions = [];
      _selectedBranch = null;
      _commits = [];
      _hasMore = false;
      _branchError = null;
      _commitsError = null;
    });
    if (project == null) return;
    setState(() => _loadingBranches = true);
    try {
      final data = await widget.state.api.getJson('/api/v1/projects/$project/branch-timeline');
      final page = BranchTimelinePageData.fromJson(data);
      if (!mounted) return;
      setState(() {
        _branchOptions = page.rows;
        _loadingBranches = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _branchError = e.toString();
        _loadingBranches = false;
      });
    }
  }

  Future<void> _selectBranch(String? branch) async {
    setState(() {
      _selectedBranch = branch;
      _commits = [];
      _hasMore = false;
      _page = 1;
      _commitsError = null;
    });
    if (branch != null) await _loadCommitsPage(reset: true);
  }

  Future<void> _loadCommitsPage({bool reset = false}) async {
    final project = _selectedProject;
    final branch = _selectedBranch;
    if (project == null || branch == null) return;
    final page = reset ? 1 : _page + 1;
    setState(() => _loadingCommits = true);
    try {
      final data = await widget.state.api.getJson(
        '/api/v1/projects/$project/build-history'
        '?branch=${Uri.encodeQueryComponent(branch)}&page=$page&perPage=$_perPage',
      );
      final result = BuildHistoryPageData.fromJson(data);
      if (!mounted) return;
      setState(() {
        _commits = reset ? result.commits : [..._commits, ...result.commits];
        _hasMore = result.hasMore;
        _page = page;
        _loadingCommits = false;
        _commitsError = result.errors.isNotEmpty ? result.errors.join('\n') : null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _commitsError = e.toString();
        _loadingCommits = false;
      });
    }
  }

  String _branchLabel(BranchTimelineRow row) =>
      row.isMain ? row.branchName : 'PR #${row.prNumber}: ${row.branchName}';

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: widget.state,
      title: 'Builds',
      autoRefreshOnChange: false,
      fetch: (api) => api.getJson('/api/v1/projects'),
      // Ververst de getoonde commit-tabel voor de huidige project/branch-keuze — niet
      // DataScreenState.reload() (dat ververst alleen de project-lijst, niet de builds zelf).
      actions: (context) => [
        IconButton(
          icon: const Icon(Icons.refresh),
          tooltip: 'Ververs builds',
          onPressed: _selectedBranch == null ? null : () => _loadCommitsPage(reset: true),
        ),
      ],
      builder: (context, data) {
        final projectNames = ProjectsPageData.fromJson(data).projects.map((p) => p.name).toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<String>(
              // ValueKey dwingt een verse FormField-state af zodra _selectedProject programmatisch
              // verandert (auto-select) — DropdownButtonFormField's initialValue wordt anders alleen
              // bij de allereerste build gelezen en niet ververst op latere rebuilds.
              key: ValueKey('project-$_selectedProject'),
              initialValue: _selectedProject,
              decoration: const InputDecoration(labelText: 'Project'),
              items: [for (final name in projectNames) DropdownMenuItem(value: name, child: Text(name))],
              onChanged: _selectProject,
            ),
            if (_selectedProject != null) ...[
              const SizedBox(height: 12),
              if (_loadingBranches)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (_branchError != null)
                ErrorBanner(_branchError!)
              else
                DropdownButtonFormField<String>(
                  key: ValueKey('branch-$_selectedProject-$_selectedBranch'),
                  initialValue: _selectedBranch,
                  decoration: const InputDecoration(labelText: 'Branch'),
                  items: [
                    for (final row in _branchOptions)
                      DropdownMenuItem(value: row.branchName, child: Text(_branchLabel(row))),
                  ],
                  onChanged: _selectBranch,
                ),
            ],
            if (_selectedBranch != null) ...[
              const SizedBox(height: 16),
              if (_commitsError != null) ErrorBanner(_commitsError!),
              for (final commit in _commits) BuildHistoryCommitCard(row: commit),
              if (_loadingCommits)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (_commits.isEmpty)
                const EmptyState('Geen commits gevonden op deze branch.')
              else if (_hasMore)
                Center(
                  child: TextButton(onPressed: () => _loadCommitsPage(), child: const Text('Meer')),
                ),
            ],
          ],
        );
      },
    );
  }
}
