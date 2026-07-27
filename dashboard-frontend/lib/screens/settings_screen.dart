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

  Future<void> _saveAuditProjectSettings(String project, String startTime, int auditCount) async {
    try {
      await widget.state.api.postJson('/api/v1/audits/project-settings', {
        'project': project,
        'startTime': startTime,
        'auditCount': auditCount,
      });
      if (!mounted) return;
      showActionResult(context, success: true, message: 'Audit-instellingen voor $project opgeslagen.');
      await _dataScreenKey.currentState?.reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
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
                        projectSettings: projectSettings,
                        onSave: _saveAuditProjectSettings,
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

class _AuditProjectSettingsRow extends StatefulWidget {
  final Map<String, dynamic> projectSettings;
  final Future<void> Function(String project, String startTime, int auditCount) onSave;

  const _AuditProjectSettingsRow({required this.projectSettings, required this.onSave});

  @override
  State<_AuditProjectSettingsRow> createState() => _AuditProjectSettingsRowState();
}

class _AuditProjectSettingsRowState extends State<_AuditProjectSettingsRow> {
  late final TextEditingController _startTimeController;
  late final TextEditingController _auditCountController;
  var _saving = false;

  @override
  void initState() {
    super.initState();
    _startTimeController = TextEditingController(text: text(widget.projectSettings['startTime'], fallback: '08:00'));
    _auditCountController = TextEditingController(text: number(widget.projectSettings['auditCount']).toString());
  }

  @override
  void dispose() {
    _startTimeController.dispose();
    _auditCountController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final startTime = _startTimeController.text.trim();
    final auditCount = int.tryParse(_auditCountController.text.trim());
    if (!RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(startTime)) {
      showActionResult(context, success: false, message: 'Starttijd moet HH:MM zijn (bv. 08:00).');
      return;
    }
    if (auditCount == null || auditCount < 0) {
      showActionResult(context, success: false, message: 'Aantal audits moet 0 of hoger zijn.');
      return;
    }
    setState(() => _saving = true);
    await widget.onSave(text(widget.projectSettings['project']), startTime, auditCount);
    if (mounted) setState(() => _saving = false);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(
            flex: 2,
            child: Text(text(widget.projectSettings['project'])),
          ),
          SizedBox(
            width: 90,
            child: TextField(
              controller: _startTimeController,
              decoration: const InputDecoration(labelText: 'Starttijd'),
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 70,
            child: TextField(
              controller: _auditCountController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Aantal'),
            ),
          ),
          const SizedBox(width: 12),
          IconButton(
            icon: _saving
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.save_outlined),
            onPressed: _saving ? null : _save,
          ),
        ],
      ),
    );
  }
}

