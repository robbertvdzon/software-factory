import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

/// Story 5 (`deployedAt`/Rollout-tab): Done-stories (gemergd) die nog niet op alle geraakte
/// deploy-doelen bevestigd live staan. Zodra de backend `deployedAt` zet, verdwijnt de story hier
/// vanzelf uit de lijst (dezelfde `/api/v1/rollout`-query sluit 'm dan uit) — geen aparte
/// "verwijder"-actie nodig.
class RolloutScreen extends StatelessWidget {
  final AppState state;
  const RolloutScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Rollout',
      subtitle: 'Gemergde stories die nog niet op alle onderdelen bevestigd live staan.',
      fetch: (api) => api.getJson('/api/v1/rollout'),
      builder: (context, data) {
        final items = asList(data['items']);
        if (items.isEmpty) {
          return const EmptyState('Geen stories in afwachting van een live-bevestiging.');
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final item in items)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _RolloutStoryTile(state: state, item: item),
              ),
          ],
        );
      },
    );
  }
}

class _RolloutStoryTile extends StatelessWidget {
  final AppState state;
  final Map<String, dynamic> item;
  const _RolloutStoryTile({required this.state, required this.item});

  @override
  Widget build(BuildContext context) {
    final run = Map<String, dynamic>.from(item['run'] as Map? ?? {});
    final storyKey = text(run['storyKey']);
    final targetsRaw = item['targets'];
    final targets = targetsRaw is List ? asList(targetsRaw) : null;
    return Panel(
      child: InkWell(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => StoryDetailScreen(state: state, storyKey: storyKey)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(storyKey, style: const TextStyle(fontWeight: FontWeight.w800)),
                const SizedBox(width: 8),
                Text('gemergd ${formatTimestamp(run['endedAt'])}', style: const TextStyle(color: Colors.black54)),
                const Spacer(),
                if (text(run['prUrl']).isNotEmpty)
                  IconButton(
                    icon: const Icon(Icons.open_in_new),
                    tooltip: 'Open build/PR',
                    onPressed: () => launchUrl(
                      Uri.parse(text(run['prUrl'])),
                      mode: LaunchMode.externalApplication,
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            if (targets == null)
              const StatusBadge('status onbekend', BadgeTone.neutral)
            else if (targets.isEmpty)
              const StatusBadge('geen deploy-doelen geraakt', BadgeTone.neutral)
            else
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final target in targets)
                    StatusBadge(
                      '${text(target['name'])}: ${boolValue(target['live']) ? 'live' : 'nog niet live'}',
                      boolValue(target['live']) ? BadgeTone.good : BadgeTone.warn,
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
