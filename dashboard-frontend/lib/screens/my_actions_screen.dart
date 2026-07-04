import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

/// Startscherm (§9): "wat wacht op mij?" — alle wachtende (sub)taken, per story
/// gegroepeerd, approve/reject in één tik (§9 "in één tik, mobiel-eerst").
class MyActionsScreen extends StatefulWidget {
  final AppState state;
  const MyActionsScreen({super.key, required this.state});

  @override
  State<MyActionsScreen> createState() => _MyActionsScreenState();
}

class _MyActionsScreenState extends State<MyActionsScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busyKey = '';

  Future<void> _act(String issueKey, String command) async {
    setState(() => _busyKey = issueKey);
    try {
      await widget.state.api.postJson('/api/v1/stories/$issueKey/command/$command');
      if (!mounted) return;
      showActionResult(context, success: true, message: '$command uitgevoerd op $issueKey.');
      await _dataScreenKey.currentState?.reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busyKey = '');
    }
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'My actions',
      subtitle: 'Wat wacht er op jou?',
      fetch: (api) => api.getJson('/api/v1/my-actions'),
      builder: (context, data) {
        final groups = asList(data['groups']);
        if (groups.isEmpty) {
          return const EmptyState('Niets wacht op een actie. 🎉');
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final group in groups)
              _StoryGroupCard(state: widget.state, group: group, busyKey: _busyKey, onAct: _act),
          ],
        );
      },
    );
  }
}

class _StoryGroupCard extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> group;
  final String busyKey;
  final Future<void> Function(String issueKey, String command) onAct;
  const _StoryGroupCard({required this.state, required this.group, required this.busyKey, required this.onAct});

  @override
  Widget build(BuildContext context) {
    final items = asList(group['items']);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            InkWell(
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => StoryDetailScreen(state: state, storyKey: text(group['storyKey'])),
                ),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(text(group['storyKey']), style: const TextStyle(fontWeight: FontWeight.w800)),
                        Text(text(group['storySummary']), style: const TextStyle(color: Colors.black54)),
                      ],
                    ),
                  ),
                  const Icon(Icons.chevron_right),
                ],
              ),
            ),
            const Divider(height: 20),
            for (final item in items)
              _ActionItemTile(state: state, item: item, storyKey: text(group['storyKey']), busyKey: busyKey, onAct: onAct),
          ],
        ),
      ),
    );
  }
}

class _ActionItemTile extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> item;
  final String storyKey;
  final String busyKey;
  final Future<void> Function(String issueKey, String command) onAct;
  const _ActionItemTile({
    required this.state,
    required this.item,
    required this.storyKey,
    required this.busyKey,
    required this.onAct,
  });

  @override
  Widget build(BuildContext context) {
    final issue = Map<String, dynamic>.from(item['issue'] as Map? ?? {});
    final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
    final phase = text(fields['subtaskPhase'], fallback: text(fields['storyPhase']));
    final question = text(item['question']);
    final issueKey = text(issue['key']);
    final busy = busyKey == issueKey;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: storyKey)),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(issueKey, style: const TextStyle(fontWeight: FontWeight.w700)),
                      if (question.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text(question, style: const TextStyle(color: Colors.black87)),
                        ),
                    ],
                  ),
                ),
                StatusBadge.fromPhase(phase),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              FilledButton.tonal(
                onPressed: busy ? null : () => onAct(issueKey, 'approve'),
                child: const Text('Approve'),
              ),
              const SizedBox(width: 8),
              FilledButton.tonal(
                style: FilledButton.styleFrom(foregroundColor: const Color(0xffb42318)),
                onPressed: busy ? null : () => onAct(issueKey, 'reject'),
                child: const Text('Reject'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
