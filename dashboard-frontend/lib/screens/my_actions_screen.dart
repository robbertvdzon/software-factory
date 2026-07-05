import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../pending_action.dart';
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

  Future<void> _openWorkspace(String storyKey) async {
    try {
      await widget.state.api.postJson('/api/v1/stories/$storyKey/open-workspace');
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Workspace geopend in IntelliJ.');
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
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
              _StoryGroupCard(
                state: widget.state,
                group: group,
                onOpenWorkspace: () => _openWorkspace(text(group['storyKey'])),
                onDone: () => _dataScreenKey.currentState?.reload(),
              ),
          ],
        );
      },
    );
  }
}

class _StoryGroupCard extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> group;
  final VoidCallback onOpenWorkspace;
  final VoidCallback onDone;
  const _StoryGroupCard({required this.state, required this.group, required this.onOpenWorkspace, required this.onDone});

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
                  IconButton(
                    icon: const Icon(Icons.code),
                    tooltip: 'Open in IntelliJ',
                    onPressed: onOpenWorkspace,
                  ),
                  const Icon(Icons.chevron_right),
                ],
              ),
            ),
            const Divider(height: 20),
            for (final item in items) _ActionItemTile(state: state, item: item, storyKey: text(group['storyKey']), onDone: onDone),
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
  final VoidCallback onDone;
  const _ActionItemTile({required this.state, required this.item, required this.storyKey, required this.onDone});

  @override
  Widget build(BuildContext context) {
    final issue = Map<String, dynamic>.from(item['issue'] as Map? ?? {});
    final fields = Map<String, dynamic>.from(issue['fields'] as Map? ?? {});
    final isStory = text(issue['issueType']) == 'STORY';
    final phase = text(isStory ? fields['storyPhase'] : fields['subtaskPhase']);
    final question = text(item['question']);
    final issueKey = text(issue['key']);
    final action = pendingActionFor(isStory: isStory, phase: phase, subtaskType: text(fields['subtaskType']));
    if (action == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: PendingActionCard(
        state: state,
        issueKey: issueKey,
        isStory: isStory,
        action: action,
        question: action.kind == PendingKind.question ? question : null,
        onDone: onDone,
      ),
    );
  }
}
