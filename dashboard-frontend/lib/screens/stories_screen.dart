import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

class StoriesScreen extends StatefulWidget {
  final AppState state;
  const StoriesScreen({super.key, required this.state});

  @override
  State<StoriesScreen> createState() => _StoriesScreenState();
}

/// Zelfde suppliers/modellen als core/AiRouting.kt (`AI_SUPPLIER_OPTIONS`/`MODELS_BY_SUPPLIER`);
/// hier gedupliceerd omdat er geen bridge-operatie is die deze catalogus opvraagt.
const _aiSuppliers = ['none', 'mock', 'claude', 'openai', 'copilot', 'microsoft'];
const _aiModelsBySupplier = {
  'claude': ['claude-opus-4-8', 'claude-opus-4-7', 'claude-sonnet-4-6', 'claude-haiku-4-5'],
  'copilot': ['claude-opus-4.5', 'claude-sonnet-4.5', 'claude-haiku-4.5', 'gpt-4.1'],
  'openai': ['gpt-4.1'],
  'mock': ['dummy-ai-client'],
};

/// Bucket-classificatie 1-op-1 met StoryStatusPresenter.classifyStatus (Kotlin): status-string van
/// de tracker → todo/bezig/klaar, voor de filterbalk (§9 pariteit met het oude bucket-filter).
enum _Bucket { todo, inProgress, finished }

_Bucket _classify(String status) {
  switch (status.trim().toLowerCase()) {
    case 'done':
    case 'fixed':
    case 'verified':
    case 'closed':
    case 'resolved':
      return _Bucket.finished;
    case 'in progress':
    case 'to verify':
    case 'develop':
    case 'developing':
      return _Bucket.inProgress;
    default:
      return _Bucket.todo;
  }
}

/// Sleutels voor het onthouden van de filterkeuze (§9: filters overleven navigatie/herstart, net
/// als de sessie zelf al via SharedPreferences bewaard blijft). Het oude `stories_filter_project`
/// is vervangen door een repo- en een zoekfilter (SF-818).
const _prefsBuckets = 'stories_filter_buckets';
const _prefsRepo = 'stories_filter_repo';
const _prefsSearch = 'stories_filter_search';

/// Storynummer uit een key als `SF-817` → 817 (voor het aflopend sorteren, ongeacht filters).
/// Valt terug op -1 als er geen numeriek suffix is, zodat zulke keys onderaan belanden.
int _storyNumber(String key) => int.tryParse(key.split('-').last) ?? -1;

/// Repo-waarde van een story: het vrije `Repo`-veld, met terugval op de run-`targetRepo` (net als
/// [_StoryTile] die toont). Leeg als geen van beide bekend is.
String _repoOf(Map<String, dynamic> issue, Map<String, dynamic> runsByStory) {
  final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
  final run = Map<String, dynamic>.from(runsByStory[issue['key']] as Map? ?? {});
  return text(fields['repo'], fallback: text(run['targetRepo'], fallback: ''));
}

class _StoriesScreenState extends State<StoriesScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  final _searchController = TextEditingController();
  var _buckets = {_Bucket.todo, _Bucket.inProgress, _Bucket.finished};
  String? _repoFilter;
  var _search = '';

  @override
  void initState() {
    super.initState();
    _loadFilters();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadFilters() async {
    final prefs = await SharedPreferences.getInstance();
    final storedBuckets = prefs.getStringList(_prefsBuckets);
    final storedRepo = prefs.getString(_prefsRepo);
    final storedSearch = prefs.getString(_prefsSearch) ?? '';
    if (!mounted) return;
    setState(() {
      if (storedBuckets != null) {
        _buckets = storedBuckets.map((name) => _Bucket.values.byName(name)).toSet();
      }
      _repoFilter = storedRepo;
      _search = storedSearch;
      _searchController.text = storedSearch;
    });
  }

  Future<void> _saveFilters() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_prefsBuckets, _buckets.map((b) => b.name).toList());
    if (_repoFilter == null) {
      await prefs.remove(_prefsRepo);
    } else {
      await prefs.setString(_prefsRepo, _repoFilter!);
    }
    if (_search.isEmpty) {
      await prefs.remove(_prefsSearch);
    } else {
      await prefs.setString(_prefsSearch, _search);
    }
  }

  void _setBuckets(void Function() update) {
    setState(update);
    _saveFilters();
  }

  void _setRepoFilter(String? repo) {
    setState(() => _repoFilter = repo);
    _saveFilters();
  }

  void _setSearch(String value) {
    setState(() => _search = value);
    _saveFilters();
  }

  Future<void> _createStory(BuildContext context) async {
    final data = await widget.state.api.getJson('/api/v1/stories');
    if (!context.mounted) return;
    final created = await showDialog<bool>(
      context: context,
      builder: (_) => _CreateStoryDialog(
        api: widget.state.api,
        repoNames: (data['repoNames'] as List? ?? []).map((e) => e.toString()).toList(),
      ),
    );
    if (created == true) await _dataScreenKey.currentState?.reload();
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Stories',
      fetch: (api) => api.getJson('/api/v1/stories'),
      builder: (context, data) {
        // Alleen stories tonen, geen subtaken — 1-op-1 met StoriesView.kt (Kotlin): `onlyStories`.
        // Altijd aflopend op storynummer sorteren (hoogste/nieuwste bovenaan), ongeacht de filters.
        final allIssues = asList(data['issues']).where((issue) => text(issue['issueType']) == 'STORY').toList()
          ..sort((a, b) => _storyNumber(text(b['key'])).compareTo(_storyNumber(text(a['key']))));
        final merged = (data['mergedStoryKeys'] as List? ?? []).map((e) => e.toString()).toSet();
        if (allIssues.isEmpty) {
          return const EmptyState('Geen stories gevonden.');
        }
        final runsByStory = Map<String, dynamic>.from(data['runsByStory'] as Map? ?? {});
        // Distinct repos van de getoonde stories, voor het repo-filter.
        final repos = allIssues.map((i) => _repoOf(i, runsByStory)).where((r) => r.isNotEmpty).toSet().toList()..sort();
        // Een geselecteerde repo die niet meer voorkomt, negeren we (val terug op "alle repos").
        final activeRepo = (_repoFilter != null && repos.contains(_repoFilter)) ? _repoFilter : null;
        final query = _search.trim().toLowerCase();
        final issues = allIssues.where((issue) {
          if (!_buckets.contains(_classify(text(issue['status'])))) return false;
          if (activeRepo != null && _repoOf(issue, runsByStory) != activeRepo) return false;
          if (query.isNotEmpty && !text(issue['summary']).toLowerCase().contains(query)) return false;
          return true;
        }).toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilterChip(
                        label: const Text('Todo'),
                        selected: _buckets.contains(_Bucket.todo),
                        onSelected: (v) => _setBuckets(() => v ? _buckets.add(_Bucket.todo) : _buckets.remove(_Bucket.todo)),
                      ),
                      FilterChip(
                        label: const Text('Bezig'),
                        selected: _buckets.contains(_Bucket.inProgress),
                        onSelected: (v) =>
                            _setBuckets(() => v ? _buckets.add(_Bucket.inProgress) : _buckets.remove(_Bucket.inProgress)),
                      ),
                      FilterChip(
                        label: const Text('Klaar'),
                        selected: _buckets.contains(_Bucket.finished),
                        onSelected: (v) =>
                            _setBuckets(() => v ? _buckets.add(_Bucket.finished) : _buckets.remove(_Bucket.finished)),
                      ),
                      if (repos.length > 1) ...[
                        const SizedBox(width: 8, height: 24, child: VerticalDivider()),
                        ChoiceChip(
                          label: const Text('Alle repos'),
                          selected: activeRepo == null,
                          onSelected: (_) => _setRepoFilter(null),
                        ),
                        for (final repo in repos)
                          ChoiceChip(
                            label: Text(repo),
                            selected: activeRepo == repo,
                            onSelected: (_) => _setRepoFilter(repo),
                          ),
                      ],
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.add),
                  tooltip: 'Nieuwe story',
                  onPressed: () => _createStory(context),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _searchController,
              decoration: InputDecoration(
                isDense: true,
                prefixIcon: const Icon(Icons.search, size: 18),
                hintText: 'Zoek in story-titel',
                border: const OutlineInputBorder(),
                suffixIcon: _search.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        tooltip: 'Wissen',
                        onPressed: () {
                          _searchController.clear();
                          _setSearch('');
                        },
                      ),
              ),
              onChanged: _setSearch,
            ),
            const SizedBox(height: 12),
            if (issues.isEmpty) const EmptyState('Geen stories voor deze filters.'),
            for (final issue in issues)
              _StoryTile(
                state: widget.state,
                issue: issue,
                merged: merged.contains(issue['key']),
                run: Map<String, dynamic>.from(runsByStory[issue['key']] as Map? ?? {}),
              ),
          ],
        );
      },
    );
  }
}

/// Rij met dezelfde kolommen als de oude Kotlin-stories-tabel (ListComponents.kt's `issueTable`):
/// story, project, fase, tokens, kosten.
class _StoryTile extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> issue;
  final bool merged;
  final Map<String, dynamic> run;
  const _StoryTile({required this.state, required this.issue, required this.merged, required this.run});

  @override
  Widget build(BuildContext context) {
    final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
    final error = text(fields['error']);
    final project = text(fields['repo'], fallback: text(run['targetRepo'], fallback: '-'));
    final tokens = number(run['totalInputTokens']) + number(run['totalOutputTokens']);
    final cost = run['totalCostUsdEst'] != null ? '\$${(run['totalCostUsdEst'] as num).toStringAsFixed(2)}' : '-';
    // Tijdstempel per rij: voor een afgeronde story het afrondmoment (updatedAt, zie story-aanname),
    // anders het aanmaakmoment. Robuust bij ontbrekende updatedAt: val terug op createdAt.
    final finished = _classify(text(issue['status'])) == _Bucket.finished;
    final timestampRaw = finished ? text(fields['updatedAt'], fallback: text(fields['createdAt'])) : text(fields['createdAt']);
    final timestamp = formatTimestamp(timestampRaw);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Panel(
        child: InkWell(
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: text(issue['key']))),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(text(issue['key']), style: const TextStyle(fontWeight: FontWeight.w800)),
                        if (merged) ...[
                          const SizedBox(width: 8),
                          const StatusBadge('merged', BadgeTone.good),
                        ],
                        const Spacer(),
                        if (error.isNotEmpty)
                          const StatusBadge('blocked', BadgeTone.bad)
                        else if (finished)
                          // storyPhase blijft na de refinement/planningfase bewust op 'in-progress'
                          // staan (development is subtaak-gedreven, niet story-fase-gedreven) — dus
                          // voor een afgeronde story is `status` (== "Done") de juiste bron, niet
                          // storyPhase, anders blijft de badge voor altijd "in-progress" tonen.
                          const StatusBadge('done', BadgeTone.good)
                        else
                          StatusBadge.fromPhase(text(fields['storyPhase'])),
                      ],
                    ),
                    Text(text(issue['summary']), style: const TextStyle(color: Colors.black87)),
                    const SizedBox(height: 4),
                    Text(
                      '$project · $timestamp · ${tokens > 0 ? '$tokens tokens' : '- tokens'} · $cost',
                      style: const TextStyle(color: Colors.black54, fontSize: 12),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.chevron_right, size: 18),
            ],
          ),
        ),
      ),
    );
  }
}

class _CreateStoryDialog extends StatefulWidget {
  final ApiClient api;
  final List<String> repoNames;
  const _CreateStoryDialog({required this.api, required this.repoNames});

  @override
  State<_CreateStoryDialog> createState() => _CreateStoryDialogState();
}

class _CreateStoryDialogState extends State<_CreateStoryDialog> {
  final _formKey = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _description = TextEditingController();
  String? _repo;
  var _aiSupplier = 'claude';
  String? _aiModel;
  var _autoApprove = false;
  var _start = true;
  var _saving = false;
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      // SF-818 — geen projectKey meer: de backend valt terug op het enige geconfigureerde project.
      await widget.api.postJson('/api/v1/stories', {
        'title': _title.text.trim(),
        'description': _description.text.trim(),
        'repo': _repo ?? '',
        'aiSupplier': _aiSupplier,
        if (_aiModel != null) 'aiModel': _aiModel,
        'start': _start,
        'autoApprove': _autoApprove,
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _saving = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Nieuwe story'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  controller: _title,
                  decoration: const InputDecoration(labelText: 'Titel'),
                  validator: (value) => (value == null || value.trim().isEmpty) ? 'Verplicht' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _description,
                  decoration: const InputDecoration(labelText: 'Beschrijving'),
                  maxLines: 4,
                  minLines: 2,
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _repo,
                  decoration: const InputDecoration(labelText: 'Repo (projectnaam uit projects.yaml)'),
                  items: [
                    for (final repo in widget.repoNames) DropdownMenuItem(value: repo, child: Text(repo)),
                  ],
                  onChanged: _saving ? null : (value) => setState(() => _repo = value),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _aiSupplier,
                  decoration: const InputDecoration(labelText: 'AI-supplier'),
                  items: [
                    for (final supplier in _aiSuppliers) DropdownMenuItem(value: supplier, child: Text(supplier)),
                  ],
                  onChanged: _saving
                      ? null
                      : (value) => setState(() {
                          _aiSupplier = value ?? 'claude';
                          _aiModel = null;
                        }),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _aiModel,
                  decoration: const InputDecoration(labelText: 'AI-model'),
                  items: [
                    const DropdownMenuItem(value: null, child: Text('— automatisch (op AI-niveau) —')),
                    for (final model in _aiModelsBySupplier[_aiSupplier] ?? const <String>[])
                      DropdownMenuItem(value: model, child: Text(model)),
                  ],
                  onChanged: _saving ? null : (value) => setState(() => _aiModel = value),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Direct starten'),
                  value: _start,
                  onChanged: _saving ? null : (value) => setState(() => _start = value),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Auto-approve'),
                  value: _autoApprove,
                  onChanged: _saving ? null : (value) => setState(() => _autoApprove = value),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  ErrorBanner(_error!),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _saving ? null : () => Navigator.of(context).pop(false), child: const Text('Annuleren')),
        FilledButton(onPressed: _saving ? null : _submit, child: Text(_saving ? 'Aanmaken...' : 'Aanmaken')),
      ],
    );
  }
}
