import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
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
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              spacing: 14,
              runSpacing: 14,
              children: [
                _Metric('Stories', '${issues.length}'),
                _Metric('Actieve agents', '${activeAgentRuns.length}'),
              ],
            ),
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
                          builder: (_) => StoryDetailScreen(state: state, storyKey: text(run['storyKey'])),
                        ),
                      ),
                      child: Text('${text(run['storyKey'])} · ${text(run['role'])}'),
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
          Text(value, style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
        ],
      ),
    ),
  );
}

class AgentsScreen extends StatelessWidget {
  final AppState state;
  const AgentsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Agents',
      fetch: (api) => api.getJson('/api/v1/agents'),
      builder: (context, data) {
        final active = asList(data['activeAgentRuns']);
        final recent = asList(data['recentAgentRuns']);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SectionTitle('Actief'),
            if (active.isEmpty) const EmptyState('Geen actieve agents.') else ...active.map(_agentTile),
            const SizedBox(height: 20),
            const SectionTitle('Recent'),
            if (recent.isEmpty) const EmptyState('Geen recente runs.') else ...recent.map(_agentTile),
          ],
        );
      },
    );
  }

  Widget _agentTile(Map<String, dynamic> run) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Panel(
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${text(run['storyKey'])} · ${text(run['role'])}', style: const TextStyle(fontWeight: FontWeight.w700)),
                Text(text(run['summaryText'], fallback: text(run['containerName'])), style: const TextStyle(color: Colors.black54)),
              ],
            ),
          ),
          StatusBadge.fromPhase(text(run['outcome'], fallback: 'running')),
        ],
      ),
    ),
  );
}

class MergedScreen extends StatelessWidget {
  final AppState state;
  const MergedScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Merged',
      fetch: (api) => api.getJson('/api/v1/merged'),
      builder: (context, data) {
        final runs = asList(data['mergedRuns']);
        if (runs.isEmpty) return const EmptyState('Nog geen gemergede stories.');
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final run in runs)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Panel(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(text(run['storyKey'])),
                    subtitle: Text('PR ${text(run['prNumber'], fallback: '-')} · ${formatTimestamp(run['endedAt'])}'),
                    trailing: text(run['prUrl']).isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.open_in_new),
                            onPressed: () => launchUrl(Uri.parse(text(run['prUrl'])), mode: LaunchMode.externalApplication),
                          )
                        : null,
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class ProjectsScreen extends StatelessWidget {
  final AppState state;
  const ProjectsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Projects',
      fetch: (api) => api.getJson('/api/v1/projects'),
      builder: (context, data) {
        final projects = asList(data['projects']);
        if (projects.isEmpty) return const EmptyState('Geen projecten geconfigureerd.');
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final project in projects)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Panel(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(text(project['name']), style: const TextStyle(fontWeight: FontWeight.w800)),
                      const SizedBox(height: 6),
                      Wrap(
                        spacing: 8,
                        children: [
                          Chip(label: Text('todo: ${number(project['storiesTodo'])}')),
                          Chip(label: Text('bezig: ${number(project['storiesInProgress'])}')),
                          Chip(label: Text('klaar: ${number(project['storiesDone'])}')),
                          Chip(label: Text('agents: ${number(project['activeAgentCount'])}')),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class NightlyScreen extends StatelessWidget {
  final AppState state;
  const NightlyScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Nightly',
      fetch: (api) => api.getJson('/api/v1/nightly'),
      builder: (context, data) {
        final jobs = asList(data['jobs']);
        final run = data['run'] as Map?;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (run != null) ...[
              const SectionTitle('Huidige/laatste run'),
              Panel(child: Text('${text(run['status'])} · ${text(run['kind'])} · ${formatTimestamp(run['startedAt'])}')),
              const SizedBox(height: 20),
            ],
            const SectionTitle('Jobs'),
            if (jobs.isEmpty)
              const EmptyState('Geen nightly-jobs geconfigureerd.')
            else
              for (final job in jobs)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Panel(
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text('${text(job['project'])} — ${text(job['title'])}'),
                      subtitle: Text(boolValue(job['enabled']) ? 'ingeschakeld' : 'uitgeschakeld'),
                    ),
                  ),
                ),
          ],
        );
      },
    );
  }
}

class SettingsScreen extends StatelessWidget {
  final AppState state;
  const SettingsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Settings',
      fetch: (api) => api.getJson('/api/v1/settings'),
      builder: (context, data) {
        final configuration = Map<String, dynamic>.from(data['configuration'] as Map? ?? {});
        final version = Map<String, dynamic>.from(data['version'] as Map? ?? {});
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SectionTitle('Versie'),
            Panel(
              child: Text(
                '${text(version['branch'])} · ${text(version['commitShort'])}\n${text(version['commitSubject'])}',
              ),
            ),
            const SizedBox(height: 20),
            const SectionTitle('Configuratie'),
            Panel(
              child: Column(
                children: [
                  for (final entry in configuration.entries)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        children: [
                          Expanded(child: Text(entry.key, style: const TextStyle(color: Colors.black54))),
                          Expanded(child: Text(text(entry.value))),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class DownloadsScreen extends StatelessWidget {
  final AppState state;
  const DownloadsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Downloads',
      fetch: (api) => api.getJson('/api/v1/downloads'),
      builder: (context, data) {
        final downloads = asList(data['downloads']);
        if (downloads.isEmpty) {
          return const EmptyState("Geen APK's gevonden.");
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final download in downloads)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Panel(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.android),
                    title: Text(text(download['projectKey'])),
                    subtitle: Text(
                      '${text(download['name'])} · ${formatBytes(number(download['size']))} · ${formatTimestamp(download['createdAt'])}',
                    ),
                    trailing: FilledButton.tonal(
                      onPressed: () => launchUrl(Uri.parse(text(download['downloadUrl'])), mode: LaunchMode.externalApplication),
                      child: const Text('Download'),
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
