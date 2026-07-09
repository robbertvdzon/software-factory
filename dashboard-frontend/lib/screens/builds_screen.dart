import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// GitHub Actions build-status per beheerde repository (naar het patroon van
/// docs/factory/ux/wireframes2/builds.html): projectfilter-pills boven per-repo panelen met de
/// laatste run per workflow.
class BuildsScreen extends StatefulWidget {
  final AppState state;
  const BuildsScreen({super.key, required this.state});

  @override
  State<BuildsScreen> createState() => _BuildsScreenState();
}

class _BuildsScreenState extends State<BuildsScreen> {
  String? _selectedProject;

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: widget.state,
      title: 'Builds',
      fetch: (api) => api.getJson('/api/v1/builds'),
      builder: (context, data) {
        final repos = asList(data['repos']);
        if (repos.isEmpty) {
          return const EmptyState('No GitHub Actions workflows found for the configured repositories.');
        }
        final projectKeys = repos.map((r) => text(r['projectKey'])).toList();
        final visibleRepos = _selectedProject == null
            ? repos
            : repos.where((r) => text(r['projectKey']) == _selectedProject).toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ChoiceChip(
                  label: const Text('Alle'),
                  selected: _selectedProject == null,
                  onSelected: (_) => setState(() => _selectedProject = null),
                ),
                for (final key in projectKeys)
                  ChoiceChip(
                    label: Text(key),
                    selected: _selectedProject == key,
                    onSelected: (_) => setState(() => _selectedProject = key),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            for (final repo in visibleRepos) _RepoBuildsPanel(repo: repo),
          ],
        );
      },
    );
  }
}

class _RepoBuildsPanel extends StatelessWidget {
  final Map<String, dynamic> repo;
  const _RepoBuildsPanel({required this.repo});

  @override
  Widget build(BuildContext context) {
    final runs = asList(repo['runs']);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(text(repo['projectKey']), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            const SizedBox(height: 8),
            if (runs.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 4),
                child: Text(
                  'No GitHub Actions workflows found. This repository can still be handled by the '
                  'factory, but it has no visible buildstraat yet.',
                  style: TextStyle(color: Colors.black54),
                ),
              )
            else ...[
              const _BuildsTableHeader(),
              for (final run in runs) _WorkflowRunRow(run: run),
            ],
          ],
        ),
      ),
    );
  }
}

/// Kolomtitel-rij boven de builds-tabel (ontbrak in de Flutter-UI, wél aanwezig in de wireframe
/// docs/factory/ux/wireframes2/builds.html); zelfde kolom-flexen als [_WorkflowRunRow].
class _BuildsTableHeader extends StatelessWidget {
  const _BuildsTableHeader();

  @override
  Widget build(BuildContext context) {
    const headerStyle = TextStyle(fontWeight: FontWeight.w700, fontSize: 12, color: Colors.black54);
    return const Padding(
      padding: EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(flex: 3, child: Text('Workflow', style: headerStyle)),
          Expanded(flex: 2, child: Text('Resultaat', style: headerStyle)),
          Expanded(flex: 2, child: Text('Branch', style: headerStyle)),
          Expanded(flex: 2, child: Text('Event', style: headerStyle)),
          Expanded(flex: 2, child: Text('Duur', style: headerStyle)),
          SizedBox(width: 48),
        ],
      ),
    );
  }
}

class _WorkflowRunRow extends StatelessWidget {
  final Map<String, dynamic> run;
  const _WorkflowRunRow({required this.run});

  @override
  Widget build(BuildContext context) {
    final htmlUrl = text(run['htmlUrl']);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(flex: 3, child: Text(text(run['workflowName']), overflow: TextOverflow.ellipsis)),
          Expanded(flex: 2, child: _ConclusionBadge(run: run)),
          Expanded(flex: 2, child: Text(text(run['branch']), overflow: TextOverflow.ellipsis)),
          Expanded(flex: 2, child: Text(text(run['event']))),
          Expanded(flex: 2, child: Text(formatDuration(run['durationSeconds']))),
          IconButton(
            icon: const Icon(Icons.open_in_new, size: 18),
            tooltip: 'Open',
            onPressed: htmlUrl.isEmpty ? null : () => launchUrl(Uri.parse(htmlUrl), mode: LaunchMode.externalApplication),
          ),
        ],
      ),
    );
  }
}

class _ConclusionBadge extends StatelessWidget {
  final Map<String, dynamic> run;
  const _ConclusionBadge({required this.run});

  @override
  Widget build(BuildContext context) {
    final conclusion = text(run['conclusion']);
    if (conclusion.isNotEmpty) {
      return StatusBadge(conclusion, _toneForConclusion(conclusion));
    }
    final status = text(run['status'], fallback: '-');
    return StatusBadge(status, BadgeTone.active);
  }

  BadgeTone _toneForConclusion(String conclusion) => switch (conclusion) {
    'success' => BadgeTone.good,
    'failure' || 'timed_out' || 'action_required' => BadgeTone.bad,
    'cancelled' || 'skipped' || 'neutral' => BadgeTone.neutral,
    _ => BadgeTone.warn,
  };
}

/// Formatteert een aantal seconden als `5m28s`/`10s`; `-` als er geen (geldige) duur bekend is.
String formatDuration(dynamic value) {
  final seconds = value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
  if (seconds == null || seconds <= 0) return '-';
  final minutes = seconds ~/ 60;
  final remaining = seconds % 60;
  if (minutes == 0) return '${remaining}s';
  return '${minutes}m${remaining.toString().padLeft(2, '0')}s';
}
