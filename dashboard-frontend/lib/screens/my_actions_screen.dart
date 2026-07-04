import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

/// Startscherm (§9): "wat wacht op mij?" — alle wachtende (sub)taken, per story
/// gegroepeerd, in één tik naar het story-detail voor approve/reject.
class MyActionsScreen extends StatelessWidget {
  final AppState state;
  const MyActionsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
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
            for (final group in groups) _StoryGroupCard(state: state, group: group),
          ],
        );
      },
    );
  }
}

class _StoryGroupCard extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> group;
  const _StoryGroupCard({required this.state, required this.group});

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
            for (final item in items) _ActionItemTile(state: state, item: item, storyKey: text(group['storyKey'])),
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
  const _ActionItemTile({required this.state, required this.item, required this.storyKey});

  @override
  Widget build(BuildContext context) {
    final issue = Map<String, dynamic>.from(item['issue'] as Map? ?? {});
    final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
    final phase = text(fields['subtaskPhase'], fallback: text(fields['storyPhase']));
    final question = text(item['question']);
    return InkWell(
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: storyKey)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(text(issue['key']), style: const TextStyle(fontWeight: FontWeight.w700)),
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
    );
  }
}
