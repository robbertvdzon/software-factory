import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../main.dart';
import '../widgets/common.dart';
import 'data_screen.dart';
import 'story_detail_screen.dart';

class DashboardOverviewScreen extends StatelessWidget {
  final AppState state;
  const DashboardOverviewScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Dashboard',
      fetch: (api) => api.getJson('/api/v1/dashboard'),
      builder: (context, data) {
        final issues = asList(data['issues']);
        final activeAgentRuns = asList(data['activeAgentRuns']);
        final activeRuns = asList(data['activeRuns']);
        final recentRuns = asList(data['recentRuns']);
        final attentionBuilds = asList(data['attentionBuilds']);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              spacing: 14,
              runSpacing: 14,
              children: [
                _Metric('Stories', '${issues.length}'),
                _Metric('Lopende runs', '${activeAgentRuns.length}'),
                _Metric('Open story-runs', '${activeRuns.length}'),
                _Metric(
                  'Laatste run',
                  recentRuns.isEmpty ? '-' : text(recentRuns.first['storyKey']),
                ),
              ],
            ),
            if (attentionBuilds.isNotEmpty) ...[
              const SizedBox(height: 20),
              const SectionTitle('Aandacht nodig'),
              for (final build in attentionBuilds)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Panel(
                    child: Row(
                      children: [
                        const Icon(Icons.error_outline, color: SfColors.red),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            '${text(build['projectKey'])} · ${text(build['workflowName'])} gefaald op '
                            '${text(build['branch'])}',
                          ),
                        ),
                        if (text(build['htmlUrl']).isNotEmpty)
                          IconButton(
                            icon: const Icon(Icons.open_in_new),
                            onPressed: () => launchUrl(
                              Uri.parse(text(build['htmlUrl'])),
                              mode: LaunchMode.externalApplication,
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
            ],
            const SizedBox(height: 20),
            const SectionTitle('Actieve agents'),
            if (activeAgentRuns.isEmpty)
              const EmptyState('Geen actieve agents.')
            else
              for (final run in activeAgentRuns)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Panel(
                    child: InkWell(
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => StoryDetailScreen(
                            state: state,
                            storyKey: text(run['storyKey']),
                          ),
                        ),
                      ),
                      child: Text(
                        '${text(run['storyKey'])} · ${text(run['role'])}',
                      ),
                    ),
                  ),
                ),
            const SizedBox(height: 20),
            const SectionTitle('Recente runs'),
            if (recentRuns.isEmpty)
              const EmptyState('Nog geen runs.')
            else
              for (final run in recentRuns)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Panel(
                    child: InkWell(
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => StoryDetailScreen(
                            state: state,
                            storyKey: text(run['storyKey']),
                          ),
                        ),
                      ),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              text(run['storyKey']),
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          Text(
                            formatTimestamp(run['endedAt']),
                            style: const TextStyle(
                              color: Colors.black54,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
          ],
        );
      },
    );
  }
}

class _Metric extends StatelessWidget {
  final String label;
  final String value;
  const _Metric(this.label, this.value);

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 160,
    child: Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(color: Colors.black54)),
          const SizedBox(height: 6),
          Text(
            value,
            style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
          ),
        ],
      ),
    ),
  );
}
