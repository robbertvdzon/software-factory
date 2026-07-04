import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

class StoriesScreen extends StatelessWidget {
  final AppState state;
  const StoriesScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Stories',
      fetch: (api) => api.getJson('/api/v1/stories'),
      builder: (context, data) {
        final issues = asList(data['issues']);
        final merged = (data['mergedStoryKeys'] as List? ?? []).map((e) => e.toString()).toSet();
        if (issues.isEmpty) {
          return const EmptyState('Geen stories gevonden.');
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final issue in issues) _StoryTile(state: state, issue: issue, merged: merged.contains(issue['key'])),
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
