import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../app_updates.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Overzicht van de laatste app-versie per geconfigureerd project (alle projecten uit
/// `projects.yaml`, niet alleen robberts-assistent) — generalisatie van robberts_assistent's
/// eigen "Updates"-tabblad (`update_checker.dart`/`updates_screen.dart`), maar dan voor alle
/// apps van alle projecten in beheer. Zie [groupAppReleases] voor hoe de ruwe downloadslijst
/// wordt teruggebracht tot één rij per app.
class AppUpdatesScreen extends StatefulWidget {
  final AppState state;
  const AppUpdatesScreen({super.key, required this.state});

  @override
  State<AppUpdatesScreen> createState() => _AppUpdatesScreenState();
}

class _AppUpdatesScreenState extends State<AppUpdatesScreen> {
  final _installer = AppUpdateInstaller();
  final _installing = <String>{};
  String? _lastError;

  String _keyOf(AppRelease release) => '${release.repository}::${release.appLabel}';

  Future<Map<String, dynamic>> _fetch(ApiClient api) async {
    final response = await api.getJson('/api/v1/downloads');
    final releases = groupAppReleases(response['downloads'] as List<dynamic>? ?? const []);
    final lastInstalled = <String, String?>{};
    for (final release in releases) {
      lastInstalled[_keyOf(release)] = await _installer.lastInstalledAt(release);
    }
    return {'releases': releases, 'lastInstalled': lastInstalled};
  }

  Future<void> _install(AppRelease release) async {
    setState(() {
      _installing.add(_keyOf(release));
      _lastError = null;
    });
    try {
      if (defaultTargetPlatform == TargetPlatform.android) {
        await _installer.install(release);
      } else {
        // Geen native install-brug buiten Android — gewoon de apk-download openen, zoals de
        // bestaande downloadknop op het Projects-scherm.
        await launchUrl(Uri.parse(release.downloadUrl), mode: LaunchMode.externalApplication);
      }
    } on UpdatePermissionRequiredException {
      if (mounted) {
        setState(() => _lastError =
            '${release.appLabel}: zet in de systeeminstellingen "installeren van deze app toestaan" aan en probeer opnieuw.');
      }
    } catch (e) {
      if (mounted) setState(() => _lastError = '${release.appLabel}: bijwerken mislukt ($e).');
    } finally {
      if (mounted) setState(() => _installing.remove(_keyOf(release)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: widget.state,
      title: 'App-updates',
      subtitle: 'Laatste APK-versie per app, over alle projecten in beheer.',
      fetch: _fetch,
      builder: (context, data) {
        final releases = data['releases'] as List<AppRelease>;
        final lastInstalled = data['lastInstalled'] as Map<String, String?>;
        if (releases.isEmpty) {
          return const EmptyState("Geen APK's gevonden bij de geconfigureerde projecten.");
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_lastError != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: ErrorBanner(_lastError!),
              ),
            for (final release in releases)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _AppReleaseCard(
                  release: release,
                  lastInstalledAt: lastInstalled[_keyOf(release)],
                  installing: _installing.contains(_keyOf(release)),
                  onInstall: () => _install(release),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _AppReleaseCard extends StatelessWidget {
  final AppRelease release;
  final String? lastInstalledAt;
  final bool installing;
  final VoidCallback onInstall;

  const _AppReleaseCard({
    required this.release,
    required this.lastInstalledAt,
    required this.installing,
    required this.onInstall,
  });

  @override
  Widget build(BuildContext context) {
    final upToDate = lastInstalledAt != null && lastInstalledAt == release.createdAt;
    final details =
        '${release.assetName} · ${formatBytes(release.size)} · ${formatTimestamp(release.createdAt)}';
    final statusText = upToDate
        ? 'Laatst geïnstalleerd via dit scherm op ${formatTimestamp(lastInstalledAt)}'
        : lastInstalledAt == null
            ? 'Nog niet via dit scherm geïnstalleerd'
            : 'Update beschikbaar (laatst geïnstalleerd via dit scherm: ${formatTimestamp(lastInstalledAt)})';
    return Panel(
      child: Row(
        children: [
          const Icon(Icons.android, size: 22, color: Colors.black54),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(release.appLabel, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
                const SizedBox(height: 2),
                Text(details, style: const TextStyle(fontSize: 12, color: Colors.black54)),
                const SizedBox(height: 2),
                Text(
                  statusText,
                  style: TextStyle(
                    fontSize: 12,
                    color: upToDate ? Colors.green.shade700 : Colors.black54,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          if (installing)
            const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2))
          else
            FilledButton(onPressed: onInstall, child: const Text('Installeren')),
        ],
      ),
    );
  }
}
