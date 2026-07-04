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
                if (text(fields['error']).isNotEmpty) const StatusBadge('blocked', BadgeTone.bad),
              ],
            ),
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
            const SizedBox(height: 20),
            const SectionTitle('Keten'),
            _ChainVisualization(subtasks: subtasks),
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
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final (command, label, destructive) in _commands)
                        FilledButton.tonal(
                          style: destructive
                              ? FilledButton.styleFrom(foregroundColor: const Color(0xffb42318))
                              : null,
                          onPressed: _busy ? null : () => _command(command, destructive),
                          child: Text(label),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            const SectionTitle('Details'),
            Panel(
              child: _KeyValueList({
                'Target repo': text(fields['targetRepo'], fallback: '-'),
                'PR': text(run['prUrl'], fallback: '-'),
                'Preview': text(data['previewUrl'], fallback: '-'),
                'Started': formatTimestamp(run['startedAt']),
                'Ended': formatTimestamp(run['endedAt']),
                'Kosten': text(run['totalCostUsdEst'], fallback: '-'),
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
