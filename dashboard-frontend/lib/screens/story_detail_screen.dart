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

class StoryDetailScreen extends StatelessWidget {
  final AppState state;
  final String storyKey;
  const StoryDetailScreen({super.key, required this.state, required this.storyKey});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: storyKey,
      fetch: (api) => api.getJson('/api/v1/stories/$storyKey'),
      actions: (context) => [
        IconButton(
          icon: const Icon(Icons.image_outlined),
          tooltip: 'Screenshots',
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ScreenshotsScreen(state: state, storyKey: storyKey)),
          ),
        ),
      ],
      builder: (context, data) {
        final issue = Map<String, dynamic>.from(data['issue'] as Map? ?? {});
        final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
        final run = Map<String, dynamic>.from(data['run'] as Map? ?? {});
        final subtasks = asList(data['subtasks']);
        final agentQuestions = Map<String, dynamic>.from(data['agentQuestions'] as Map? ?? {});
        final myQuestion = text(agentQuestions[storyKey]);
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
