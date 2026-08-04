import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// Historie van de nachtelijke maintenance-cleanup: per opruimronde (project × tick) één rij met
/// wanneer hij liep, voor welk project en hoeveel er is opgeruimd. Ook rondes zonder verwijderingen
/// en mislukte rondes staan erin — dat is juist het bewijs dat de opruimer echt gedraaid heeft.
/// Details (welke release-tags/package-versions precies weg zijn) staan in [_MaintenanceRunDetailScreen].
class MaintenanceScreen extends StatelessWidget {
  final AppState state;
  const MaintenanceScreen({super.key, required this.state});

  Future<Map<String, dynamic>> _fetch(ApiClient api) => api.getJson('/api/v1/maintenance/cleanups');

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      state: state,
      title: 'Maintenance',
      fetch: _fetch,
      builder: (context, data) {
        final runs = asList(data['runs']);
        // errors zijn losse strings, niet maps — asList verwacht maps, dus de ruwe lijst hier
        // direct pakken i.p.v. via asList.
        final rawErrors = (data['errors'] as List? ?? []).map((e) => e.toString()).toList();

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (rawErrors.isNotEmpty) ...[
              for (final error in rawErrors) ErrorBanner(error),
              const SizedBox(height: 12),
            ],
            if (runs.isEmpty)
              const EmptyState('Nog geen opruimrondes.')
            else
              for (final run in runs)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Panel(
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Row(
                        children: [
                          Flexible(
                            child: Text(
                              formatTimestamp(run['startedAt']),
                              style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                            ),
                          ),
                          if (boolValue(run['dryRun']))
                            const Padding(
                              padding: EdgeInsets.only(left: 8),
                              child: StatusBadge('dry-run', BadgeTone.warn),
                            ),
                          if (boolValue(run['failed']))
                            const Padding(
                              padding: EdgeInsets.only(left: 8),
                              child: StatusBadge('fout', BadgeTone.bad),
                            ),
                        ],
                      ),
                      subtitle: Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                          '${text(run['project'], fallback: '-')} — ${cleanupCountsLine(run)}',
                          style: const TextStyle(color: Colors.black54, fontSize: 13),
                        ),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => _MaintenanceRunDetailScreen(state: state, runId: number(run['id'])),
                        ),
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

/// "3 releases / 12 package-versions opgeruimd" — bij een dry-run gaat het om de *geplande*
/// aantallen, want er is dan niets echt verwijderd.
String cleanupCountsLine(Map<String, dynamic> run) {
  final releases = number(run['releasesDeleted']);
  final packages = number(run['packagesDeleted']);
  final verb = boolValue(run['dryRun']) ? 'zou worden opgeruimd' : 'opgeruimd';
  return '$releases releases / $packages package-versions $verb';
}

/// Detail van één opruimronde: de aantallen, de daadwerkelijk verwijderde release-tags en
/// package-versions en — als de ronde misging — de foutmelding. Eigen pagina (net als het
/// auditrapport-detail) omdat de opsommingen lang kunnen worden.
class _MaintenanceRunDetailScreen extends StatefulWidget {
  final AppState state;
  final int runId;
  const _MaintenanceRunDetailScreen({required this.state, required this.runId});

  @override
  State<_MaintenanceRunDetailScreen> createState() => _MaintenanceRunDetailScreenState();
}

class _MaintenanceRunDetailScreenState extends State<_MaintenanceRunDetailScreen> {
  late final Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.state.api.getJson('/api/v1/maintenance/cleanups/${widget.runId}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Opruimronde')),
      body: FutureBuilder<Map<String, dynamic>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Padding(padding: const EdgeInsets.all(16), child: ErrorBanner(snapshot.error.toString()));
          }
          final run = snapshot.data ?? {};
          final error = text(run['error']);
          return Align(
            alignment: Alignment.topLeft,
            child: ConstrainedBox(
              // Zelfde max-breedte als DataScreen en het auditrapport-detail.
              constraints: const BoxConstraints(maxWidth: 860),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            formatTimestamp(run['startedAt']),
                            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18),
                          ),
                        ),
                        if (boolValue(run['dryRun']))
                          const Padding(
                            padding: EdgeInsets.only(left: 8),
                            child: StatusBadge('dry-run', BadgeTone.warn),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 16,
                      children: [
                        Text('Project: ${text(run['project'], fallback: '-')}',
                            style: const TextStyle(color: Colors.black54)),
                        Text('Klaar: ${formatTimestamp(run['finishedAt'])}',
                            style: const TextStyle(color: Colors.black54)),
                        Text(
                          'Releases: ${number(run['releasesDeleted'])} opgeruimd, '
                          '${number(run['releasesKept'])} bewaard',
                          style: const TextStyle(color: Colors.black54),
                        ),
                        Text(
                          'Package-versions: ${number(run['packagesDeleted'])} opgeruimd, '
                          '${number(run['packagesKept'])} bewaard',
                          style: const TextStyle(color: Colors.black54),
                        ),
                      ],
                    ),
                    if (error.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      ErrorBanner(error),
                    ],
                    const Divider(height: 24),
                    _ItemList(title: 'Verwijderde releases', items: stringList(run['deletedReleaseTags'])),
                    const SizedBox(height: 16),
                    _ItemList(title: 'Verwijderde package-versions', items: stringList(run['deletedPackageVersions'])),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// De detailvelden zijn lijsten van losse strings (geen maps), dus [asList] past hier niet.
List<String> stringList(dynamic value) => (value as List? ?? []).map((item) => item.toString()).toList();

class _ItemList extends StatelessWidget {
  final String title;
  final List<String> items;
  const _ItemList({required this.title, required this.items});

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      SectionTitle(title),
      if (items.isEmpty)
        const Text('Niets verwijderd.', style: TextStyle(color: Colors.black54))
      else
        for (final item in items)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text('• $item'),
          ),
    ],
  );
}
