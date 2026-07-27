import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../main.dart';
import '../text_scale_preference.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

class SettingsScreen extends StatefulWidget {
  final AppState state;
  final TextScalePreference textScale;
  const SettingsScreen({
    super.key,
    required this.state,
    required this.textScale,
  });

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _dataScreenKey = GlobalKey<DataScreenState>();
  var _busy = false;
  var _savingAuditSettings = false;
  bool? _auditEnabled;
  final Map<String, TextEditingController> _auditStartTimeControllers = {};
  final Map<String, TextEditingController> _auditCountControllers = {};

  @override
  void dispose() {
    for (final controller in _auditStartTimeControllers.values) {
      controller.dispose();
    }
    for (final controller in _auditCountControllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  TextEditingController _auditStartTimeController(Map<String, dynamic> row) => _auditStartTimeControllers.putIfAbsent(
        text(row['project']),
        () => TextEditingController(text: text(row['startTime'], fallback: '08:00')),
      );

  TextEditingController _auditCountController(Map<String, dynamic> row) => _auditCountControllers.putIfAbsent(
        text(row['project']),
        () => TextEditingController(text: number(row['auditCount']).toString()),
      );

  // Eén gezamenlijke save voor de hele "Audits per project"-tabel + de globale schakelaar,
  // i.p.v. een los save-knopje per projectrij.
  Future<void> _saveAuditSettings(List<dynamic> auditProjectSettings) async {
    final projects = <Map<String, dynamic>>[];
    for (final row in auditProjectSettings) {
      final project = text(row['project']);
      final startTime = _auditStartTimeControllers[project]!.text.trim();
      final auditCount = int.tryParse(_auditCountControllers[project]!.text.trim());
      if (!RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(startTime)) {
        showActionResult(context, success: false, message: 'Starttijd voor $project moet HH:MM zijn (bv. 08:00).');
        return;
      }
      if (auditCount == null || auditCount < 0) {
        showActionResult(context, success: false, message: 'Aantal audits voor $project moet 0 of hoger zijn.');
        return;
      }
      projects.add({'project': project, 'startTime': startTime, 'auditCount': auditCount});
    }
    setState(() => _savingAuditSettings = true);
    try {
      await widget.state.api.postJson('/api/v1/audits/settings', {
        'enabled': _auditEnabled ?? false,
        'projects': projects,
      });
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Audit-instellingen opgeslagen.');
      await _dataScreenKey.currentState?.reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _savingAuditSettings = false);
    }
  }

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
      if (mounted) {
        showActionResult(
          context,
          success: true,
          message: '$label aangevraagd.',
        );
      }
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
      title: 'Settings',
      fetch: (api) => api.getJson('/api/v1/settings'),
      builder: (context, data) {
        final configuration = Map<String, dynamic>.from(
          data['configuration'] as Map? ?? {},
        );
        final version = Map<String, dynamic>.from(
          data['version'] as Map? ?? {},
        );
        final auditProjectSettings = asList(data['auditProjectSettings']);
        _auditEnabled ??= data['auditEnabled'] == true;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SectionTitle('Versie'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${text(version['branch'])} · ${text(version['commitShort'])}',
                  ),
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
                  const SizedBox(height: 12),
                  FilledButton.tonalIcon(
                    onPressed: () => launchUrl(
                      Uri.parse(
                        'https://github.com/robbertvdzon/software-factory/actions',
                      ),
                      mode: LaunchMode.externalApplication,
                    ),
                    icon: const Icon(Icons.open_in_new),
                    label: const Text('GitHub Actions'),
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
                          Expanded(
                            child: Text(
                              entry.key,
                              style: const TextStyle(color: Colors.black54),
                            ),
                          ),
                          Expanded(child: Text(text(entry.value))),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            const SectionTitle('Audits per project'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Audit-scheduler ingeschakeld'),
                    subtitle: const Text(
                      'Staat dit uit, dan draaien er geen automatische (geplande) audits meer — '
                      '"Nu draaien" in het Audits-scherm blijft altijd werken.',
                    ),
                    value: _auditEnabled ?? false,
                    onChanged: (v) => setState(() => _auditEnabled = v),
                  ),
                  const Divider(height: 24),
                  const Text(
                    'Starttijd en aantal audits per nacht, per project. Meerdere audits voor '
                    'hetzelfde project draaien achter elkaar, niet tegelijk.',
                    style: TextStyle(color: Colors.black54, fontSize: 12),
                  ),
                  const SizedBox(height: 8),
                  if (auditProjectSettings.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 8),
                      child: Text('Geen projecten geconfigureerd.'),
                    )
                  else
                    for (final projectSettings in auditProjectSettings)
                      _AuditProjectSettingsRow(
                        key: ValueKey(text(projectSettings['project'])),
                        project: text(projectSettings['project']),
                        startTimeController: _auditStartTimeController(projectSettings),
                        auditCountController: _auditCountController(projectSettings),
                      ),
                  const SizedBox(height: 12),
                  Align(
                    alignment: Alignment.centerRight,
                    child: FilledButton.icon(
                      onPressed: _savingAuditSettings ? null : () => _saveAuditSettings(auditProjectSettings),
                      icon: _savingAuditSettings
                          ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.save_outlined),
                      label: const Text('Opslaan'),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            const SectionTitle('Weergave'),
            Panel(
              child: ListenableBuilder(
                listenable: widget.textScale,
                builder: (context, _) => SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Grote letters'),
                  subtitle: const Text(
                    'Vergroot het lettertype op alle pagina\'s van het dashboard.',
                  ),
                  value: widget.textScale.enabled,
                  onChanged: (v) => widget.textScale.setEnabled(v),
                ),
              ),
            ),
            const SizedBox(height: 20),
            const SectionTitle('Factory-proces (destructief)'),
            Panel(
              child: Wrap(
                spacing: 8,
                children: [
                  FilledButton.tonal(
                    onPressed: _busy
                        ? null
                        : () => _restartOrStop(
                            '/api/v1/factory/restart',
                            'Herstart',
                          ),
                    child: const Text('Herstart'),
                  ),
                  FilledButton.tonal(
                    style: FilledButton.styleFrom(
                      foregroundColor: SfColors.red,
                    ),
                    onPressed: _busy
                        ? null
                        : () => _restartOrStop('/api/v1/factory/stop', 'Stop'),
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

// Puur de invoervelden; opslaan gebeurt gezamenlijk via de ene "Opslaan"-knop in
// _SettingsScreenState._saveAuditSettings (de controllers leven daar, gekeyed per project).
class _AuditProjectSettingsRow extends StatelessWidget {
  final String project;
  final TextEditingController startTimeController;
  final TextEditingController auditCountController;

  const _AuditProjectSettingsRow({
    super.key,
    required this.project,
    required this.startTimeController,
    required this.auditCountController,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(flex: 2, child: Text(project)),
          SizedBox(
            width: 90,
            child: TextField(
              controller: startTimeController,
              decoration: const InputDecoration(labelText: 'Starttijd'),
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 70,
            child: TextField(
              controller: auditCountController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Aantal'),
            ),
          ),
        ],
      ),
    );
  }
}

