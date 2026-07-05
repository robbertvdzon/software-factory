import 'package:flutter/material.dart';

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

class _StoriesScreenState extends State<StoriesScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  final _buckets = {_Bucket.todo, _Bucket.inProgress, _Bucket.finished};
  String? _projectFilter;

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Stories',
      fetch: (api) => api.getJson('/api/v1/stories'),
      actions: (context) => [
        IconButton(
          icon: const Icon(Icons.add),
          tooltip: 'Nieuwe story',
          onPressed: () async {
            final data = await widget.state.api.getJson('/api/v1/stories');
            if (!context.mounted) return;
            final created = await showDialog<bool>(
              context: context,
              builder: (_) => _CreateStoryDialog(
                api: widget.state.api,
                projects: asList(data['projects']),
              ),
            );
            if (created == true) await _dataScreenKey.currentState?.reload();
          },
        ),
      ],
      builder: (context, data) {
        final allIssues = asList(data['issues']);
        final merged = (data['mergedStoryKeys'] as List? ?? []).map((e) => e.toString()).toSet();
        if (allIssues.isEmpty) {
          return const EmptyState('Geen stories gevonden.');
        }
        final projectKeys = allIssues.map((i) => text(i['projectKey'])).where((p) => p.isNotEmpty).toSet().toList()..sort();
        final issues = allIssues.where((issue) {
          if (!_buckets.contains(_classify(text(issue['status'])))) return false;
          if (_projectFilter != null && text(issue['projectKey']) != _projectFilter) return false;
          return true;
        }).toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilterChip(
                  label: const Text('Todo'),
                  selected: _buckets.contains(_Bucket.todo),
                  onSelected: (v) => setState(() => v ? _buckets.add(_Bucket.todo) : _buckets.remove(_Bucket.todo)),
                ),
                FilterChip(
                  label: const Text('Bezig'),
                  selected: _buckets.contains(_Bucket.inProgress),
                  onSelected: (v) =>
                      setState(() => v ? _buckets.add(_Bucket.inProgress) : _buckets.remove(_Bucket.inProgress)),
                ),
                FilterChip(
                  label: const Text('Klaar'),
                  selected: _buckets.contains(_Bucket.finished),
                  onSelected: (v) => setState(() => v ? _buckets.add(_Bucket.finished) : _buckets.remove(_Bucket.finished)),
                ),
                if (projectKeys.length > 1) ...[
                  const SizedBox(width: 8, height: 24, child: VerticalDivider()),
                  ChoiceChip(
                    label: const Text('Alle projecten'),
                    selected: _projectFilter == null,
                    onSelected: (_) => setState(() => _projectFilter = null),
                  ),
                  for (final project in projectKeys)
                    ChoiceChip(
                      label: Text(project),
                      selected: _projectFilter == project,
                      onSelected: (_) => setState(() => _projectFilter = project),
                    ),
                ],
              ],
            ),
            const SizedBox(height: 12),
            if (issues.isEmpty) const EmptyState('Geen stories voor deze filters.'),
            for (final issue in issues)
              _StoryTile(state: widget.state, issue: issue, merged: merged.contains(issue['key'])),
          ],
        );
      },
    );
  }
}

class _StoryTile extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> issue;
  final bool merged;
  const _StoryTile({required this.state, required this.issue, required this.merged});

  @override
  Widget build(BuildContext context) {
    final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
    final error = text(fields['error']);
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
                      ],
                    ),
                    Text(text(issue['summary']), style: const TextStyle(color: Colors.black87)),
                    Text(
                      '${text(fields['storyPhase'], fallback: '-')} · ${text(issue['status'])}',
                      style: const TextStyle(color: Colors.black54, fontSize: 12),
                    ),
                  ],
                ),
              ),
              if (error.isNotEmpty) const StatusBadge('blocked', BadgeTone.bad) else const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }
}

class _CreateStoryDialog extends StatefulWidget {
  final ApiClient api;
  final List<Map<String, dynamic>> projects;
  const _CreateStoryDialog({required this.api, required this.projects});

  @override
  State<_CreateStoryDialog> createState() => _CreateStoryDialogState();
}

class _CreateStoryDialogState extends State<_CreateStoryDialog> {
  final _formKey = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _description = TextEditingController();
  final _repo = TextEditingController();
  String? _projectKey;
  var _aiSupplier = 'claude';
  String? _aiModel;
  var _autoApprove = false;
  var _start = true;
  var _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.projects.isNotEmpty) _projectKey = text(widget.projects.first['key']);
  }

  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    _repo.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await widget.api.postJson('/api/v1/stories', {
        'projectKey': _projectKey,
        'title': _title.text.trim(),
        'description': _description.text.trim(),
        'repo': _repo.text.trim(),
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
                DropdownButtonFormField<String>(
                  initialValue: _projectKey,
                  decoration: const InputDecoration(labelText: 'Project'),
                  items: [
                    for (final project in widget.projects)
                      DropdownMenuItem(
                        value: text(project['key']),
                        child: Text('${text(project['key'])} — ${text(project['name'])}'),
                      ),
                  ],
                  onChanged: _saving ? null : (value) => setState(() => _projectKey = value),
                  validator: (value) => (value == null || value.isEmpty) ? 'Kies een project' : null,
                ),
                const SizedBox(height: 12),
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
                TextFormField(
                  controller: _repo,
                  decoration: const InputDecoration(labelText: 'Repo (projectnaam uit projects.yaml)'),
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
