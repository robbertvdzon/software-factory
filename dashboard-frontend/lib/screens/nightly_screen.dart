import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

class NightlyScreen extends StatefulWidget {
  final AppState state;
  const NightlyScreen({super.key, required this.state});

  @override
  State<NightlyScreen> createState() => _NightlyScreenState();
}

class _NightlyScreenState extends State<NightlyScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busy = false;

  Future<void> _runAction(
    Future<void> Function() action, {
    required String successMessage,
  }) async {
    setState(() => _busy = true);
    try {
      await action();
      if (!mounted) return;
      showActionResult(context, success: true, message: successMessage);
      await _dataScreenKey.currentState?.reload();
    } catch (e) {
      if (mounted) {
        showActionResult(context, success: false, message: e.toString());
      }
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
          jobsByProject
              .putIfAbsent(text(job['project'], fallback: '—'), () => [])
              .add(job);
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (run != null) ...[
              const SectionTitle('Huidige/laatste run'),
              Panel(
                child: Text(
                  '${text(run['status'])} · ${text(run['kind'])} · ${formatTimestamp(run['startedAt'])}',
                ),
              ),
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
                        Text(
                          entry.key,
                          style: const TextStyle(
                            fontWeight: FontWeight.w800,
                            fontSize: 16,
                          ),
                        ),
                        const SizedBox(height: 4),
                        for (final job in entry.value)
                          ListTile(
                            contentPadding: EdgeInsets.zero,
                            title: Text(text(job['title'])),
                            subtitle: Text(
                              boolValue(job['enabled'])
                                  ? 'ingeschakeld'
                                  : 'uitgeschakeld',
                            ),
                            trailing: TextButton(
                              onPressed: _busy
                                  ? null
                                  : () => _runAction(
                                      () => widget.state.api
                                          .postJson('/api/v1/nightly/stories', {
                                            'project': text(job['project']),
                                            'jobName': text(job['name']),
                                          }),
                                      successMessage:
                                          'Story aangemaakt voor ${text(job['name'])}.',
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
