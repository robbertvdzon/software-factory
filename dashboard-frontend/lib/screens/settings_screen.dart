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

