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
        final activeRuns = asList(data['activeRuns']);
        final recentRuns = asList(data['recentRuns']);
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
                _Metric('Laatste run', recentRuns.isEmpty ? '-' : text(recentRuns.first['storyKey'])),
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
                          builder: (_) => StoryDetailScreen(state: state, storyKey: text(run['storyKey'])),
                        ),
                      ),
                      child: Row(
                        children: [
                          Expanded(child: Text(text(run['storyKey']), style: const TextStyle(fontWeight: FontWeight.w700))),
                          Text(formatTimestamp(run['endedAt']), style: const TextStyle(color: Colors.black54, fontSize: 12)),
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
          Text(value, style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
        ],
      ),
    ),
  );
}

class AgentsScreen extends StatefulWidget {
  final AppState state;
  const AgentsScreen({super.key, required this.state});

  @override
  State<AgentsScreen> createState() => _AgentsScreenState();
}

class _AgentsScreenState extends State<AgentsScreen> {
  var _showRecent = false;

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: widget.state,
      title: 'Agents',
      fetch: (api) async {
        final results = await Future.wait([api.getJson('/api/v1/agents'), api.getJson('/api/v1/assistant/status')]);
        return {...results[0], 'assistantStatus': results[1]};
      },
      builder: (context, data) {
        final active = asList(data['activeAgentRuns']);
        final recent = asList(data['recentAgentRuns']);
        final assistant = Map<String, dynamic>.from(data['assistantStatus'] as Map? ?? {});
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _AssistantStatusPanel(assistant: assistant),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: Text('Actief (${active.length})', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                ),
                TextButton(
                  onPressed: () => setState(() => _showRecent = !_showRecent),
                  child: Text(_showRecent ? 'Verberg geschiedenis' : 'Toon geschiedenis'),
                ),
              ],
            ),
            if (active.isEmpty) const EmptyState('Geen actieve agents.') else ...active.map(_agentTile),
            if (_showRecent) ...[
              const SizedBox(height: 20),
              const SectionTitle('Recent'),
              if (recent.isEmpty) const EmptyState('Geen recente runs.') else ...recent.map(_agentTile),
            ],
          ],
        );
      },
    );
  }

  Widget _agentTile(Map<String, dynamic> run) => Container(
    margin: const EdgeInsets.only(bottom: 6),
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(10),
      border: Border.all(color: const Color(0x14000000)),
    ),
    child: Row(
      children: [
        Expanded(
          child: Text.rich(
            TextSpan(
              children: [
                TextSpan(text: text(run['storyKey']), style: const TextStyle(fontWeight: FontWeight.w700)),
                TextSpan(text: '  ·  ${text(run['role'])}', style: const TextStyle(color: Colors.black54)),
              ],
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
        const SizedBox(width: 8),
        StatusBadge.fromPhase(text(run['outcome'], fallback: 'running')),
      ],
    ),
  );
}

/// Toont of de Telegram-assistent draait — die is geen agent-run met een story-koppeling (geen
/// `agent_runs`-rij), dus stond hij nooit in de Agents-lijst zelf. Aparte, kleine status-operatie
/// (`assistant.status`, zie docs/ontwerp-bridge-dashboard.md §5).
class _AssistantStatusPanel extends StatelessWidget {
  final Map<String, dynamic> assistant;
  const _AssistantStatusPanel({required this.assistant});

  @override
  Widget build(BuildContext context) {
    final enabled = boolValue(assistant['enabled']);
    final busy = boolValue(assistant['busy']);
    final activeChatCount = number(assistant['activeChatCount']);
    final lastActivityAt = assistant['lastActivityAt'];
    final statusText = !enabled
        ? 'Uitgeschakeld (geen Claude-token)'
        : busy
            ? 'Bezig ($activeChatCount gesprek${activeChatCount == 1 ? '' : 'ken'})'
            : text(lastActivityAt) == '-'
                ? 'Idle'
                : 'Idle sinds ${formatTimestamp(lastActivityAt)}';
    return Panel(
      child: Row(
        children: [
          const Text('Assistent', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(width: 8),
          Expanded(child: Text(statusText, style: const TextStyle(color: Colors.black54))),
          StatusBadge(!enabled ? 'uit' : (busy ? 'bezig' : 'idle'), !enabled ? BadgeTone.neutral : (busy ? BadgeTone.warn : BadgeTone.good)),
        ],
      ),
    );
  }
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

class ProjectsScreen extends StatefulWidget {
  final AppState state;
  const ProjectsScreen({super.key, required this.state});

  @override
  State<ProjectsScreen> createState() => _ProjectsScreenState();
}

class _ProjectsScreenState extends State<ProjectsScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busy = false;

  Future<void> _forceDeploy(String name) async {
    setState(() => _busy = true);
    try {
      await widget.state.api.postJson('/api/v1/projects/$name/force-deploy');
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Deploy getriggerd voor $name.');
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
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
                      Row(
                        children: [
                          Expanded(
                            child: Text(text(project['name']), style: const TextStyle(fontWeight: FontWeight.w800)),
                          ),
                          if (boolValue(project['hasDeployConfig']))
                            FilledButton.tonal(
                              onPressed: _busy ? null : () => _forceDeploy(text(project['name'])),
                              child: const Text('Force deploy'),
                            ),
                        ],
                      ),
                      if (text(project['repoUrl']).isNotEmpty)
                        Text(text(project['repoUrl']), style: const TextStyle(color: Colors.black54, fontSize: 12)),
                      const SizedBox(height: 6),
                      Wrap(
                        spacing: 8,
                        runSpacing: 4,
                        children: [
                          Chip(label: Text('todo: ${number(project['storiesTodo'])}')),
                          Chip(label: Text('bezig: ${number(project['storiesInProgress'])}')),
                          Chip(label: Text('klaar: ${number(project['storiesDone'])}')),
                          Chip(label: Text('agents: ${number(project['activeAgentCount'])}')),
                          Chip(label: Text('kosten: \$${(project['totalCostUsd'] as num? ?? 0).toStringAsFixed(2)}')),
                        ],
                      ),
                      if (project['prdVersion'] != null) ...[
                        const SizedBox(height: 6),
                        Builder(builder: (context) {
                          final version = Map<String, dynamic>.from(project['prdVersion'] as Map);
                          return Text(
                            'Live: ${text(version['branch'])} · ${text(version['commitShort'])} (${text(version['commitDate'])})',
                            style: const TextStyle(color: Colors.black54, fontSize: 12),
                          );
                        }),
                      ],
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

class NightlyScreen extends StatefulWidget {
  final AppState state;
  const NightlyScreen({super.key, required this.state});

  @override
  State<NightlyScreen> createState() => _NightlyScreenState();
}

class _NightlyScreenState extends State<NightlyScreen> {
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
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Nightly',
      fetch: (api) => api.getJson('/api/v1/nightly'),
      actions: (context) => [
        TextButton(
          onPressed: _busy
              ? null
              : () => _runAction(
                  () => widget.state.api.postJson('/api/v1/nightly/run-now'),
                  successMessage: 'Nightly-run gestart.',
                ),
          child: const Text('Run nu'),
        ),
        TextButton(
          onPressed: _busy
              ? null
              : () => _runAction(
                  () => widget.state.api.postJson('/api/v1/nightly/stop'),
                  successMessage: 'Nightly-run gestopt.',
                ),
          child: const Text('Stop'),
        ),
      ],
      builder: (context, data) {
        final jobs = asList(data['jobs']);
        final run = data['run'] as Map?;
        final jobsByProject = <String, List<Map<String, dynamic>>>{};
        for (final job in jobs) {
          jobsByProject.putIfAbsent(text(job['project'], fallback: '—'), () => []).add(job);
        }
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
              for (final entry in jobsByProject.entries)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Panel(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(entry.key, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                        const SizedBox(height: 4),
                        for (final job in entry.value)
                          ListTile(
                            contentPadding: EdgeInsets.zero,
                            title: Text(text(job['title'])),
                            subtitle: Text(boolValue(job['enabled']) ? 'ingeschakeld' : 'uitgeschakeld'),
                            trailing: TextButton(
                              onPressed: _busy
                                  ? null
                                  : () => _runAction(
                                      () => widget.state.api.postJson('/api/v1/nightly/stories', {
                                        'project': text(job['project']),
                                        'jobName': text(job['name']),
                                      }),
                                      successMessage: 'Story aangemaakt voor ${text(job['name'])}.',
                                    ),
                              child: const Text('Nu draaien'),
                            ),
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

class SettingsScreen extends StatefulWidget {
  final AppState state;
  const SettingsScreen({super.key, required this.state});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  var _busy = false;

  Future<void> _restartOrStop(String path, String label) async {
    final confirmed = await confirmDestructive(
      context,
      title: '$label bevestigen',
      message: 'Dit $label de factory-JVM. Weet je het zeker?',
      confirmLabel: label,
    );
    if (!confirmed) return;
    setState(() => _busy = true);
    try {
      await widget.state.api.postJson(path);
      if (mounted) showActionResult(context, success: true, message: '$label aangevraagd.');
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: widget.state,
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
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${text(version['branch'])} · ${text(version['commitShort'])}'),
                  Text(text(version['commitSubject'])),
                  const SizedBox(height: 8),
                  Text(
                    'Commit: ${text(version['commitDate'], fallback: '-')}',
                    style: const TextStyle(color: Colors.black54, fontSize: 12),
                  ),
                  Text(
                    'Factory gestart: ${formatTimestamp(version['startedAt'])}',
                    style: const TextStyle(color: Colors.black54, fontSize: 12),
                  ),
                ],
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
            const SizedBox(height: 20),
            const SectionTitle('Nightly-instellingen'),
            _NightlySettingsPanel(state: widget.state, nightly: Map<String, dynamic>.from(data['nightly'] as Map? ?? {})),
            const SizedBox(height: 20),
            const SectionTitle('Factory-proces (destructief)'),
            Panel(
              child: Wrap(
                spacing: 8,
                children: [
                  FilledButton.tonal(
                    onPressed: _busy ? null : () => _restartOrStop('/api/v1/factory/restart', 'Herstart'),
                    child: const Text('Herstart'),
                  ),
                  FilledButton.tonal(
                    style: FilledButton.styleFrom(foregroundColor: const Color(0xffb42318)),
                    onPressed: _busy ? null : () => _restartOrStop('/api/v1/factory/stop', 'Stop'),
                    child: const Text('Stop'),
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

/// Nightly enabled/startTime/summaryTime bewerken (§9-feedback: ontbrak in de Flutter-app, wel
/// mogelijk in de oude Kotlin SettingsView). Bridge-operatie `nightly.saveSettings` bestond al
/// sinds fase D, alleen de UI ervoor ontbrak.
class _NightlySettingsPanel extends StatefulWidget {
  final AppState state;
  final Map<String, dynamic> nightly;
  const _NightlySettingsPanel({required this.state, required this.nightly});

  @override
  State<_NightlySettingsPanel> createState() => _NightlySettingsPanelState();
}

class _NightlySettingsPanelState extends State<_NightlySettingsPanel> {
  late var _enabled = boolValue(widget.nightly['enabled']);
  late var _startTime = text(widget.nightly['startTime'], fallback: '02:00');
  late var _summaryTime = text(widget.nightly['summaryTime'], fallback: '07:00');
  var _saving = false;

  Future<void> _pickTime(bool isStart) async {
    final current = _parseTime(isStart ? _startTime : _summaryTime);
    final picked = await showTimePicker(context: context, initialTime: current);
    if (picked == null) return;
    setState(() {
      final formatted = '${picked.hour.toString().padLeft(2, '0')}:${picked.minute.toString().padLeft(2, '0')}';
      if (isStart) {
        _startTime = formatted;
      } else {
        _summaryTime = formatted;
      }
    });
  }

  TimeOfDay _parseTime(String value) {
    final parts = value.split(':');
    return TimeOfDay(hour: int.tryParse(parts.elementAtOrNull(0) ?? '') ?? 0, minute: int.tryParse(parts.elementAtOrNull(1) ?? '') ?? 0);
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await widget.state.api.postJson('/api/v1/nightly/settings', {
        'enabled': _enabled,
        'startTime': _startTime,
        'summaryTime': _summaryTime,
      });
      if (mounted) showActionResult(context, success: true, message: 'Nightly-instellingen opgeslagen.');
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Nightly ingeschakeld'),
          value: _enabled,
          onChanged: _saving ? null : (v) => setState(() => _enabled = v),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Starttijd'),
          trailing: Text(_startTime, style: const TextStyle(fontWeight: FontWeight.w700)),
          onTap: _saving ? null : () => _pickTime(true),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Samenvattingstijd'),
          trailing: Text(_summaryTime, style: const TextStyle(fontWeight: FontWeight.w700)),
          onTap: _saving ? null : () => _pickTime(false),
        ),
        const SizedBox(height: 8),
        FilledButton(onPressed: _saving ? null : _save, child: Text(_saving ? 'Opslaan...' : 'Opslaan')),
      ],
    ),
  );
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
