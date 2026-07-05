import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'screenshots_screen.dart';

/// Stagevolgorde van de subtaak-keten (zie core/SubtaskPhase.kt): development → review →
/// test → summary → documentation → manual-approve → merge → deploy (§9 keten-visualisatie).
const _chainStages = [
  ('development', 'Dev'),
  ('review', 'Review'),
  ('test', 'Test'),
  ('summary', 'Summary'),
  ('documentation', 'Docs'),
  ('manual', 'Approve'),
  ('merge', 'Merge'),
  ('deploy', 'Deploy'),
];

/// Commando's uit core/TrackerModels.kt FactoryCommand; destructief/onomkeerbaar (§8 fase D)
/// vraagt een bevestigingsdialoog vóór het versturen.
const _commands = [
  ('approve', 'Approve', false),
  ('reject', 'Reject', false),
  ('pause', 'Pause', false),
  ('resume', 'Resume', false),
  ('clear-error', 'Clear error', false),
  ('retry-current-step', 'Retry step', false),
  ('merge', 'Merge', false),
  ('re-implement', 'Re-implement', true),
  ('kill', 'Kill', true),
  ('delete', 'Delete', true),
];

/// Story/subtask heeft een fout — zelfde regel als StoryStatusPresenter.realStatus (Kotlin):
/// het eigen error-veld ÓF dat van een van de subtaken.
bool _hasError(Map<String, dynamic> fields, List<Map<String, dynamic>> subtasks) =>
    text(fields['error']).isNotEmpty ||
    subtasks.any((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['error']).isNotEmpty);

class StoryDetailScreen extends StatefulWidget {
  final AppState state;
  final String storyKey;
  const StoryDetailScreen({super.key, required this.state, required this.storyKey});

  @override
  State<StoryDetailScreen> createState() => _StoryDetailScreenState();
}

class _StoryDetailScreenState extends State<StoryDetailScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busy = false;

  Future<void> _runAction(Future<void> Function() action, {required String successMessage}) async {
    setState(() => _busy = true);
    try {
      await action();
      if (!mounted) return;
      showActionResult(context, success: true, message: successMessage);
      await _dataScreenKey.currentState?.reload();
    } catch (e) {
      if (!mounted) return;
      showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _command(String command, bool destructive) async {
    if (destructive) {
      final confirmed = await confirmDestructive(
        context,
        title: '$command bevestigen',
        message: 'Weet je zeker dat je "$command" wilt uitvoeren op ${widget.storyKey}? Dit kan niet ongedaan gemaakt worden.',
        confirmLabel: command,
      );
      if (!confirmed) return;
    }
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/command/$command'),
      successMessage: '$command uitgevoerd.',
    );
  }

  Future<void> _purge() async {
    final confirmed = await confirmDestructive(
      context,
      title: 'Story purgen',
      message:
          'Dit verwijdert ${widget.storyKey} volledig (issue, subtaken, branch en workspace). Dit kan niet ongedaan gemaakt worden.',
      confirmLabel: 'Purge',
    );
    if (!confirmed) return;
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/purge'),
      successMessage: '${widget.storyKey} gepurged.',
    );
    if (mounted) Navigator.of(context).pop();
  }

  Future<void> _openWorkspace() async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/open-workspace'),
      successMessage: 'Workspace geopend in IntelliJ.',
    );
  }

  Future<void> _toggleAutoApprove(bool enabled) async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/auto-approve', {'enabled': enabled}),
      successMessage: enabled ? 'Auto-approve ingeschakeld.' : 'Auto-approve uitgeschakeld.',
    );
  }

  Future<void> _startRefining() async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/start-refining'),
      successMessage: 'Refining gestart.',
    );
  }

  Future<void> _startDeveloping() async {
    await _runAction(
      () => widget.state.api.postJson('/api/v1/stories/${widget.storyKey}/start-developing'),
      successMessage: 'Developing gestart.',
    );
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: widget.storyKey,
      fetch: (api) => api.getJson('/api/v1/stories/${widget.storyKey}'),
      actions: (context) => [
        IconButton(
          icon: const Icon(Icons.code),
          tooltip: 'Open in IntelliJ',
          onPressed: _busy ? null : _openWorkspace,
        ),
        Builder(
          builder: (context) => IconButton(
            icon: const Icon(Icons.forum_outlined),
            tooltip: 'Briefing',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => BriefingScreen(state: widget.state, storyKey: widget.storyKey)),
            ),
          ),
        ),
        IconButton(
          icon: const Icon(Icons.image_outlined),
          tooltip: 'Screenshots',
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ScreenshotsScreen(state: widget.state, storyKey: widget.storyKey)),
          ),
        ),
        IconButton(
          icon: const Icon(Icons.delete_outline),
          tooltip: 'Purge (destructief)',
          onPressed: _busy ? null : _purge,
        ),
      ],
      builder: (context, data) {
        final issue = Map<String, dynamic>.from(data['issue'] as Map? ?? {});
        final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
        final run = Map<String, dynamic>.from(data['run'] as Map? ?? {});
        final subtasks = asList(data['subtasks']);
        final agentQuestions = Map<String, dynamic>.from(data['agentQuestions'] as Map? ?? {});
        final myQuestion = text(agentQuestions[widget.storyKey]);
        final isStory = text(issue['issueType']) == 'STORY';
        final showStartRefining = isStory && text(fields['storyPhase']).isEmpty;
        final showStartDeveloping = isStory &&
            text(fields['storyPhase']) == 'planning-approved' &&
            subtasks.isNotEmpty &&
            subtasks.every((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskPhase']).isEmpty);
        final hasError = _hasError(fields, subtasks);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(text(issue['summary']), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                StatusBadge.fromPhase(text(fields['storyPhase'], fallback: '-')),
                if (boolValue(fields['paused'])) const StatusBadge('paused', BadgeTone.warn),
                if (hasError) const StatusBadge('blocked', BadgeTone.bad),
              ],
            ),
            if (hasError) ...[
              const SizedBox(height: 12),
              ErrorBanner(
                text(fields['error']).isNotEmpty
                    ? text(fields['error'])
                    : subtasks
                        .map((s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['error']))
                        .firstWhere((e) => e.isNotEmpty, orElse: () => 'Onbekende fout.'),
              ),
            ],
            if (myQuestion.isNotEmpty) ...[
              const SizedBox(height: 12),
              Panel(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Vraag van de agent', style: TextStyle(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 6),
                    Text(myQuestion),
                  ],
                ),
              ),
            ],
            if (showStartRefining || showStartDeveloping) ...[
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: _busy ? null : (showStartRefining ? _startRefining : _startDeveloping),
                icon: const Icon(Icons.play_arrow),
                label: Text(showStartRefining ? 'Start refining' : 'Start developing'),
              ),
            ],
            if (text(issue['description']).isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Omschrijving'),
              Panel(child: Text(text(issue['description']))),
            ],
            const SizedBox(height: 20),
            const SectionTitle('Keten'),
            _ChainVisualization(subtasks: subtasks),
            if (subtasks.isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Subtaken'),
              _SubtasksPanel(state: widget.state, subtasks: subtasks),
            ],
            const SizedBox(height: 20),
            const SectionTitle('Acties'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Auto-approve'),
                    value: boolValue(fields['autoApprove']),
                    onChanged: _busy ? null : _toggleAutoApprove,
                  ),
                  const SizedBox(height: 8),
                  _CommandsMenu(busy: _busy, onSelect: _command),
                ],
              ),
            ),
            const SizedBox(height: 20),
            const SectionTitle('Details'),
            Panel(
              child: _KeyValueList({
                'Target repo': text(fields['targetRepo'], fallback: '-'),
                'AI-supplier': text(fields['aiSupplier'], fallback: '-'),
                'AI-level': text(fields['aiLevel'], fallback: '-'),
                'PR': text(run['prUrl'], fallback: '-'),
                'Preview': text(data['previewUrl'], fallback: '-'),
                'Started': formatTimestamp(run['startedAt']),
                'Ended': formatTimestamp(run['endedAt']),
                'Agent-runs': '${asList(data['agentRuns']).length}',
                'Tokens in/uit': '${number(run['totalInputTokens'])} / ${number(run['totalOutputTokens'])}',
                'Tokens cache': '${number(run['totalCacheReadTokens'])} gelezen · ${number(run['totalCacheCreationTokens'])} aangemaakt',
                'Kosten': run['totalCostUsdEst'] != null ? '\$${(run['totalCostUsdEst'] as num).toStringAsFixed(2)}' : '-',
              }),
            ),
            if (text(run['previewUrl']).isNotEmpty || text(data['previewUrl']).isNotEmpty) ...[
              const SizedBox(height: 12),
              FilledButton.tonalIcon(
                onPressed: () => _open(text(data['previewUrl'], fallback: text(run['previewUrl']))),
                icon: const Icon(Icons.open_in_new),
                label: const Text('Open preview'),
              ),
            ],
            if (text(data['youTrackUrl']).isNotEmpty) ...[
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                onPressed: () => _open(text(data['youTrackUrl'])),
                icon: const Icon(Icons.link),
                label: const Text('Open in YouTrack'),
              ),
            ],
          ],
        );
      },
    );
  }

  Future<void> _open(String url) async {
    if (url.isEmpty) return;
    await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
  }
}

/// Dropdown-menu voor de story-commando's — zelfde acties als de knoppenrij hiervoor, maar als
/// uitklapmenu (§9-feedback: "die dropdown vond ik veel fijner" — zelfde patroon als de oude
/// Kotlin-actionsBar).
class _CommandsMenu extends StatelessWidget {
  final bool busy;
  final void Function(String command, bool destructive) onSelect;
  const _CommandsMenu({required this.busy, required this.onSelect});

  @override
  Widget build(BuildContext context) => PopupMenuButton<int>(
    enabled: !busy,
    onSelected: (index) {
      final (command, _, destructive) = _commands[index];
      onSelect(command, destructive);
    },
    itemBuilder: (context) => [
      for (var i = 0; i < _commands.length; i++)
        PopupMenuItem(
          value: i,
          child: Text(
            _commands[i].$2,
            style: _commands[i].$3 ? const TextStyle(color: Color(0xffb42318)) : null,
          ),
        ),
    ],
    child: InputDecorator(
      decoration: const InputDecoration(
        labelText: 'Commando',
        border: OutlineInputBorder(),
        suffixIcon: Icon(Icons.arrow_drop_down),
      ),
      child: const Text('Kies een actie...'),
    ),
  );
}

/// Subtaken-lijst — 1-op-1 met StoryDetailView.kt's subtasksPanel: key, samenvatting, type/status/
/// error-badges en fase, met doorklik naar het subtaak-detailscherm.
class _SubtasksPanel extends StatelessWidget {
  final AppState state;
  final List<Map<String, dynamic>> subtasks;
  const _SubtasksPanel({required this.state, required this.subtasks});

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final subtask in subtasks) _subtaskRow(context, subtask),
      ],
    ),
  );

  Widget _subtaskRow(BuildContext context, Map<String, dynamic> subtask) {
    final fields = Map<String, dynamic>.from(subtask['fields'] as Map? ?? {});
    final phase = text(fields['subtaskPhase']);
    final subtaskType = text(fields['subtaskType']);
    final hasError = text(fields['error']).isNotEmpty;
    return InkWell(
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: text(subtask['key']))),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            SizedBox(width: 60, child: Text(text(subtask['key']), style: const TextStyle(fontWeight: FontWeight.w700))),
            Expanded(
              child: Text(text(subtask['summary']), maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
            const SizedBox(width: 8),
            if (subtaskType.isNotEmpty) Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Text(subtaskType, style: const TextStyle(color: Colors.black54, fontSize: 12)),
            ),
            if (hasError) const Padding(
              padding: EdgeInsets.only(right: 8),
              child: StatusBadge('fout', BadgeTone.bad),
            ),
            StatusBadge.fromPhase(phase.isEmpty ? null : phase),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right, size: 18),
          ],
        ),
      ),
    );
  }
}

/// Briefing als eigen scherm (§9-feedback): agent-run-samenvattingen en gebruikers-antwoorden,
/// chronologisch (nieuwste eerst) — zelfde bron als de oude Kotlin-briefingpagina.
class BriefingScreen extends StatelessWidget {
  final AppState state;
  final String storyKey;
  const BriefingScreen({super.key, required this.state, required this.storyKey});

  @override
  Widget build(BuildContext context) => DataScreen(
    state: state,
    title: 'Briefing — $storyKey',
    fetch: (api) => api.getJson('/api/v1/stories/$storyKey'),
    builder: (context, data) {
      final issue = Map<String, dynamic>.from(data['issue'] as Map? ?? {});
      final subtasks = asList(data['subtasks']);
      final allAgentRuns = asList(data['allAgentRuns']);
      return _BriefingPanel(issue: issue, subtasks: subtasks, allAgentRuns: allAgentRuns);
    },
  );
}

class _BriefingPanel extends StatelessWidget {
  final Map<String, dynamic> issue;
  final List<Map<String, dynamic>> subtasks;
  final List<Map<String, dynamic>> allAgentRuns;
  const _BriefingPanel({required this.issue, required this.subtasks, required this.allAgentRuns});

  @override
  Widget build(BuildContext context) {
    final items = <_BriefingItem>[
      for (final run in allAgentRuns)
        if (text(run['summaryText']).isNotEmpty)
          _BriefingItem(
            timestamp: text(run['endedAt'], fallback: text(run['startedAt'])),
            title: '${text(run['storyKey'])} · ${text(run['role'])}',
            body: text(run['summaryText']),
          ),
      for (final comment in _userComments(issue))
        _BriefingItem(
          timestamp: text(comment['created']),
          title: text(comment['authorDisplayName'], fallback: 'Onbekend'),
          body: text(comment['body']),
        ),
      for (final subtask in subtasks)
        for (final comment in _userComments(subtask))
          _BriefingItem(
            timestamp: text(comment['created']),
            title: '${text(subtask['key'])} · ${text(comment['authorDisplayName'], fallback: 'Onbekend')}',
            body: text(comment['body']),
          ),
    ]..sort((a, b) => b.timestamp.compareTo(a.timestamp));

    if (items.isEmpty) return const EmptyState('Nog geen agent-runs of gebruikers-antwoorden gevonden.');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final item in items)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(child: Text(item.title, style: const TextStyle(fontWeight: FontWeight.w700))),
                      Text(formatTimestamp(item.timestamp), style: const TextStyle(color: Colors.black54, fontSize: 12)),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(item.body),
                ],
              ),
            ),
          ),
      ],
    );
  }

  List<Map<String, dynamic>> _userComments(Map<String, dynamic> issueOrSubtask) =>
      asList(issueOrSubtask['comments']).where((c) => !boolValue(c['isAgentComment']) && text(c['created']).isNotEmpty).toList();
}

class _BriefingItem {
  final String timestamp;
  final String title;
  final String body;
  _BriefingItem({required this.timestamp, required this.title, required this.body});
}

class _ChainVisualization extends StatelessWidget {
  final List<Map<String, dynamic>> subtasks;
  const _ChainVisualization({required this.subtasks});

  @override
  Widget build(BuildContext context) {
    return Panel(
      child: Wrap(
        spacing: 8,
        runSpacing: 12,
        children: [
          for (final (type, label) in _chainStages) _ChainStep(type: type, label: label, subtasks: subtasks),
        ],
      ),
    );
  }
}

class _ChainStep extends StatelessWidget {
  final String type;
  final String label;
  final List<Map<String, dynamic>> subtasks;
  const _ChainStep({required this.type, required this.label, required this.subtasks});

  @override
  Widget build(BuildContext context) {
    final subtask = subtasks.firstWhere(
      (s) => text(Map<String, dynamic>.from(s['fields'] as Map? ?? {})['subtaskType']) == type,
      orElse: () => const {},
    );
    final fields = Map<String, dynamic>.from(subtask['fields'] as Map? ?? {});
    final phase = text(fields['subtaskPhase']);
    return Column(
      children: [
        Text(label, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.black54)),
        const SizedBox(height: 4),
        StatusBadge.fromPhase(phase.isEmpty ? null : phase),
      ],
    );
  }
}

class _KeyValueList extends StatelessWidget {
  final Map<String, String> values;
  const _KeyValueList(this.values);

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final entry in values.entries)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(width: 120, child: Text(entry.key, style: const TextStyle(color: Colors.black54))),
              Expanded(child: Text(entry.value, style: const TextStyle(fontWeight: FontWeight.w600))),
            ],
          ),
        ),
    ],
  );
}
