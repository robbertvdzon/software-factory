import 'dart:async';

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

/// De `kind`-waarde van de "Alles draaien"-knop — geen opruimsoort maar de verzamelopdracht.
/// Spiegelt `CleanupKinds.ALL_KINDS` in de backend.
const allCleanupKinds = 'all';

/// Hoe vaak het scherm herlaadt zolang de backend meldt dat er nog een ronde loopt. Kort genoeg om
/// een afgeronde ronde vanzelf te zien verschijnen, lang genoeg om de bridge niet te belasten.
const _pollInterval = Duration(seconds: 3);

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
  /// Nodig om de lijst te herladen zonder de [DataScreen] opnieuw op te bouwen: na het starten van
  /// een ronde, bij een filterwissel en tijdens het pollen. Zelfde patroon als `audit_screen.dart`.
  final _dataScreenKey = GlobalKey<DataScreenState>();
  String _kind = _allKinds;

  /// De soort waarvoor nu een start-verzoek onderweg is; zolang die gezet is staan alle knoppen uit,
  /// zodat twee snelle klikken nooit twee verzoeken opleveren.
  String? _busyKind;

  /// Loopt alleen zolang de backend soorten als "draait" meldt; zie [_syncPolling].
  Timer? _pollTimer;

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<Map<String, dynamic>> _fetch(ApiClient api) => api.getJson(
        _kind == _allKinds
            ? '/api/v1/maintenance/cleanups'
            : '/api/v1/maintenance/cleanups?kind=$_kind',
      );

  Future<void> _reload() async => _dataScreenKey.currentState?.reload();

  /// Herlaadt licht door zolang er nog een ronde loopt en stopt zodra er niets meer draait; zo
  /// verschijnt een afgeronde ronde vanzelf in de lijst zonder permanent te pollen.
  void _syncPolling(List<String> runningKinds) {
    if (runningKinds.isEmpty) {
      _pollTimer?.cancel();
      _pollTimer = null;
    } else {
      _pollTimer ??= Timer.periodic(_pollInterval, (_) => _reload());
    }
  }

  /// Een soort is te starten zolang er geen eigen verzoek loopt én de backend 'm niet als draaiend
  /// meldt (ook als die ronde uit een tweede tab of van de scheduler komt). "Alles draaien" blijft
  /// beschikbaar zolang er nog minstens één vrije soort is.
  bool _canStart(String kind, List<String> runningKinds) {
    if (_busyKind != null) return false;
    return kind == allCleanupKinds
        ? !cleanupKinds.every(runningKinds.contains)
        : !runningKinds.contains(kind);
  }

  Future<void> _runNow(String kind) async {
    setState(() => _busyKind = kind);
    try {
      final result = await widget.state.api.postJson('/api/v1/maintenance/run', {'kind': kind});
      if (!mounted) return;
      final started = boolValue(result['started']);
      final status = text(result['status']);
      showActionResult(
        context,
        // "draait al" is geen fout maar een normale uitkomst — de completion-payload-purge bezet de
        // bewaking elke ~2s kortstondig. Alleen een echte weigering wordt rood.
        success: started || status == 'already_running',
        message: _runNowMessage(status, started, kind, stringMap(result['kinds'])),
      );
      // Ook na een weigering herladen: `runningKinds` is dan net veranderd en zet de knoppen goed.
      await _reload();
    } catch (e) {
      if (mounted) showActionResult(context, success: false, message: e.toString());
    } finally {
      if (mounted) setState(() => _busyKind = null);
    }
  }

  /// Meldtekst per status van `maintenance.runNow`. [started] (= "verzoek geaccepteerd") is de
  /// fallback voor een backend die nog geen `status` meestuurt — zelfde opzet als `audit_screen.dart`.
  String _runNowMessage(String status, bool started, String kind, Map<String, String> kinds) {
    if (kind == allCleanupKinds && kinds.isNotEmpty) return _allKindsMessage(kinds);
    switch (status) {
      case 'started':
        return 'Opruimronde gestart voor $kind.';
      case 'already_running':
        return 'De opruimronde voor $kind draait al.';
      case 'disabled':
        return 'De opruimer $kind staat uit; er is niets gestart.';
      case 'unknown_kind':
        return 'Onbekende opruimsoort: $kind.';
      default:
        return started ? 'Opruimronde gestart voor $kind.' : 'Kon de opruimronde niet starten.';
    }
  }

  /// "Alles draaien" start de vrije soorten en slaat de bezette/uitgezette over; het antwoord noemt
  /// allebei, zodat zichtbaar is wat er níet gebeurd is.
  String _allKindsMessage(Map<String, String> kinds) {
    final started = kinds.entries.where((entry) => entry.value == 'started').map((entry) => entry.key);
    final skipped = kinds.entries
        .where((entry) => entry.value != 'started')
        .map((entry) => '${entry.key} (${_skipReason(entry.value)})');
    final startedPart = started.isEmpty ? 'Niets gestart' : 'Gestart: ${started.join(', ')}';
    return skipped.isEmpty ? '$startedPart.' : '$startedPart. Overgeslagen: ${skipped.join(', ')}.';
  }

  String _skipReason(String status) {
    switch (status) {
      case 'already_running':
        return 'draait al';
      case 'disabled':
        return 'uit';
      case 'unknown_kind':
        return 'onbekend';
      default:
        return status;
    }
  }

  /// Knoppenrij: per soort één "nu draaien" plus één "Alles draaien" — zelfde `Wrap` met
  /// [TextButton]s als de acties op het Audits-scherm, inclusief het stapelen onder 560px.
  Widget _runNowBar(List<String> runningKinds) {
    const label = Text('Nu draaien:', style: TextStyle(color: Colors.black54));
    final buttons = Wrap(
      spacing: 4,
      children: [
        for (final kind in cleanupKinds)
          TextButton(
            onPressed: _canStart(kind, runningKinds) ? () => _runNow(kind) : null,
            child: Text(kind),
          ),
        TextButton(
          onPressed: _canStart(allCleanupKinds, runningKinds) ? () => _runNow(allCleanupKinds) : null,
          child: const Text('Alles draaien'),
        ),
      ],
    );
    return Panel(
      child: LayoutBuilder(
        builder: (context, constraints) {
          if (constraints.maxWidth < 560) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [label, const SizedBox(height: 8), buttons],
            );
          }
          return Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [label, const SizedBox(width: 12), Expanded(child: buttons)],
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return DataScreen(
      key: _dataScreenKey,
      state: widget.state,
      title: 'Opruimen',
      fetch: _fetch,
      builder: (context, data) {
        final runs = asList(data['runs']);
        // errors zijn losse strings, niet maps — asList verwacht maps, dus de ruwe lijst hier
        // direct pakken i.p.v. via asList.
        final rawErrors = (data['errors'] as List? ?? []).map((e) => e.toString()).toList();
        final runningKinds = (data['runningKinds'] as List? ?? []).map((e) => e.toString()).toList();
        _syncPolling(runningKinds);

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (rawErrors.isNotEmpty) ...[
              for (final error in rawErrors) ErrorBanner(error),
              const SizedBox(height: 12),
            ],
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _runNowBar(runningKinds),
            ),
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Row(
                children: [
                  const Text('Soort: ', style: TextStyle(color: Colors.black54)),
                  DropdownButton<String>(
                    value: _kind,
                    onChanged: (value) {
                      setState(() => _kind = value ?? _allKinds);
                      // De DataScreen houdt nu een vaste GlobalKey (nodig voor herladen na een
                      // start), dus de nieuwe keuze moet zelf een fetch aanvragen.
                      _reload();
                    },
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
                          if (isManual(run))
                            const Padding(
                              padding: EdgeInsets.only(left: 8),
                              child: StatusBadge('handmatig', BadgeTone.active),
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
                        if (isManual(run))
                          const Padding(
                            padding: EdgeInsets.only(left: 8),
                            child: StatusBadge('handmatig', BadgeTone.active),
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

/// `trigger` is `scheduled` of `manual` (zie `CleanupTriggers`); alleen een handmatig gestarte ronde
/// krijgt de "handmatig"-badge. Een backend zonder het veld valt terug op "gepland".
bool isManual(Map<String, dynamic> run) => text(run['trigger']) == 'manual';

/// De `kinds`-map uit het runNow-antwoord: per soort de status, als losse strings.
Map<String, String> stringMap(dynamic value) =>
    (value as Map?)?.map((key, item) => MapEntry(key.toString(), item.toString())) ?? const {};

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
