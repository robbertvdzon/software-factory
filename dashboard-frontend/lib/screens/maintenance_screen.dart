import 'package:flutter/material.dart';

import '../api_client.dart';
import '../app_state.dart';
import '../widgets/common.dart';
import 'data_screen.dart';

/// De vijf opruimmechanismen die de factory logt, in de volgorde van het filter. Bewust dezelfde
/// letterlijke waarden als `CleanupKinds` in de backend: het zijn geen vertaalde labels maar de
/// soort-sleutel zelf, zodat een rij in het scherm één-op-één te herleiden is tot de opruimer.
const cleanupKinds = <String>[
  'github-releases',
  'agent-events',
  'agent-runs',
  'completion-payloads',
  'workspaces',
];

/// Sentinel voor de "alle soorten"-stand van het filter; `null` als waarde zou het dropdown-item
/// niet selecteerbaar maken.
const _allKinds = '*';

/// Historie van álle opruimrondes van de factory: GitHub-releases, agent-events, agent-runs,
/// completion-payloads en work/-mappen. Per ronde één rij met wanneer hij liep, welk soort, voor
/// welk project (leeg = factory-breed) en hoeveel er is opgeruimd. Details staan in
/// [_MaintenanceRunDetailScreen].
///
/// Stateful omdat het soort-filter een nieuwe fetch vraagt: het filter gaat als `kind`-queryparam
/// mee naar `/api/v1/maintenance/cleanups`, en de [ValueKey] op de [DataScreen] zorgt dat die
/// opnieuw laadt zodra de keuze verandert.
class MaintenanceScreen extends StatefulWidget {
  final AppState state;
  const MaintenanceScreen({super.key, required this.state});

  @override
  State<MaintenanceScreen> createState() => _MaintenanceScreenState();
}

class _MaintenanceScreenState extends State<MaintenanceScreen> {
  String _kind = _allKinds;

  Future<Map<String, dynamic>> _fetch(ApiClient api) => api.getJson(
        _kind == _allKinds
            ? '/api/v1/maintenance/cleanups'
            : '/api/v1/maintenance/cleanups?kind=$_kind',
      );

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: ValueKey(_kind),
      state: widget.state,
      title: 'Opruimen',
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
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Row(
                children: [
                  const Text('Soort: ', style: TextStyle(color: Colors.black54)),
                  DropdownButton<String>(
                    value: _kind,
                    onChanged: (value) => setState(() => _kind = value ?? _allKinds),
                    items: [
                      const DropdownMenuItem(value: _allKinds, child: Text('alle soorten')),
                      for (final kind in cleanupKinds) DropdownMenuItem(value: kind, child: Text(kind)),
                    ],
                  ),
                ],
              ),
            ),
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
                          Padding(
                            padding: const EdgeInsets.only(left: 8),
                            child: StatusBadge(text(run['kind'], fallback: 'onbekend'), BadgeTone.neutral),
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
                          cleanupCountsLine(run),
                          style: const TextStyle(color: Colors.black54, fontSize: 13),
                        ),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => _MaintenanceRunDetailScreen(state: widget.state, runId: number(run['id'])),
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

/// "SF — 3 opgeruimd / 12 bewaard" — bij een dry-run gaat het om de *geplande* aantallen, want er is
/// dan niets echt verwijderd. Factory-brede rondes hebben geen project en tonen dat ook niet.
String cleanupCountsLine(Map<String, dynamic> run) {
  final project = text(run['project']);
  final verb = boolValue(run['dryRun']) ? 'zou worden opgeruimd' : 'opgeruimd';
  final counts = '${number(run['itemsDeleted'])} $verb / ${number(run['itemsKept'])} bewaard';
  return project.isEmpty ? counts : '$project — $counts';
}

/// Detail van één opruimronde: de aantallen en — als de ronde misging — de foutmelding. Voor
/// `github-releases` ook de uitsplitsing naar releases/package-versions en de verwijderde namen;
/// de andere soorten hebben niets uit te splitsen. Eigen pagina (net als het auditrapport-detail)
/// omdat de opsommingen lang kunnen worden.
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
          final project = text(run['project']);
          final isGitHubCleanup = text(run['kind']) == 'github-releases';
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
                        Padding(
                          padding: const EdgeInsets.only(left: 8),
                          child: StatusBadge(text(run['kind'], fallback: 'onbekend'), BadgeTone.neutral),
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
                        // Factory-brede rondes horen bij geen enkel project; dan ook geen lege regel.
                        if (project.isNotEmpty)
                          Text('Project: $project', style: const TextStyle(color: Colors.black54)),
                        Text('Klaar: ${formatTimestamp(run['finishedAt'])}',
                            style: const TextStyle(color: Colors.black54)),
                        Text(
                          'Opgeruimd: ${number(run['itemsDeleted'])}, '
                          '${number(run['itemsKept'])} bewaard',
                          style: const TextStyle(color: Colors.black54),
                        ),
                        if (isGitHubCleanup) ...[
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
                      ],
                    ),
                    if (error.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      ErrorBanner(error),
                    ],
                    if (isGitHubCleanup) ...[
                      const Divider(height: 24),
                      _ItemList(title: 'Verwijderde releases', items: stringList(run['deletedReleaseTags'])),
                      const SizedBox(height: 16),
                      _ItemList(title: 'Verwijderde package-versions', items: stringList(run['deletedPackageVersions'])),
                    ],
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
